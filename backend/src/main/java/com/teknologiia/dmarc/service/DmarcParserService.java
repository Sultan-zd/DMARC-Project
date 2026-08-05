package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.model.DmarcRecord;
import com.teknologiia.dmarc.model.DmarcReport;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses DMARC aggregate (RUA) reports into persistable entities.
 *
 * <p>The expected document shape is the {@code <feedback>} element described in
 * RFC 7489 Appendix C: report metadata, the published policy, and one
 * {@code <record>} per reporting source IP.
 */
@Service
public class DmarcParserService {

    /**
     * Reads a DMARC aggregate report.
     *
     * <p>The returned report is detached: records are attached to it on both sides
     * of the relationship but nothing has been persisted yet.
     *
     * @throws DmarcParseException if the document is not a readable DMARC report
     */
    public DmarcReport parse(InputStream xml) {
        Document document = readDocument(xml);
        Element feedback = document.getDocumentElement();

        if (feedback == null || !localName(feedback).equals("feedback")) {
            throw new DmarcParseException(
                    "Not a DMARC aggregate report: expected a <feedback> root element, found "
                            + (feedback == null ? "an empty document" : "<" + feedback.getNodeName() + ">"));
        }

        Element metadata = requireChild(feedback, "report_metadata");
        Element policy = requireChild(feedback, "policy_published");

        DmarcReport report = DmarcReport.builder()
                .reportId(requireText(metadata, "report_id"))
                .orgName(text(metadata, "org_name"))
                .orgEmail(text(metadata, "email"))
                .domain(requireText(policy, "domain"))
                .adkim(text(policy, "adkim"))
                .aspf(text(policy, "aspf"))
                .policy(text(policy, "p"))
                .spPolicy(text(policy, "sp"))
                .pct(integer(policy, "pct"))
                .build();

        child(metadata, "date_range").ifPresent(range -> {
            report.setDateBegin(epochSeconds(range, "begin"));
            report.setDateEnd(epochSeconds(range, "end"));
        });

        List<DmarcRecord> records = new ArrayList<>();
        for (Element recordElement : children(feedback, "record")) {
            records.add(parseRecord(recordElement, report));
        }

        if (records.isEmpty()) {
            throw new DmarcParseException(
                    "DMARC report '" + report.getReportId() + "' contains no <record> entries");
        }
        report.setRecords(records);

        return report;
    }

    /** Convenience overload for callers that already hold the document as a string. */
    public DmarcReport parse(String xml) {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private DmarcRecord parseRecord(Element recordElement, DmarcReport report) {
        Element row = requireChild(recordElement, "row");

        DmarcRecord.DmarcRecordBuilder record = DmarcRecord.builder()
                .report(report)
                .sourceIp(requireText(row, "source_ip"))
                .count(intOrZero(text(row, "count")));

        // <policy_evaluated> holds the DMARC alignment outcome, which is what actually
        // explains the disposition. Raw authentication results live in <auth_results>.
        child(row, "policy_evaluated").ifPresent(evaluated -> record
                .disposition(text(evaluated, "disposition"))
                .dkimResult(text(evaluated, "dkim"))
                .spfResult(text(evaluated, "spf")));

        child(recordElement, "identifiers").ifPresent(identifiers -> record
                .headerFrom(text(identifiers, "header_from"))
                .envelopeFrom(text(identifiers, "envelope_from")));

        child(recordElement, "auth_results").ifPresent(auth -> {
            child(auth, "dkim").ifPresent(dkim -> record
                    .dkimDomain(text(dkim, "domain"))
                    .dkimSelector(text(dkim, "selector")));
            child(auth, "spf").ifPresent(spf -> record.spfDomain(text(spf, "domain")));
        });

        return record.build();
    }

    private Document readDocument(InputStream xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // These reports arrive from outside the perimeter, so the parser must not
            // resolve doctypes or external entities (XXE / billion laughs).
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new DmarcParseException("External entities are not allowed in DMARC reports");
            });

            Document document = builder.parse(xml);
            document.getDocumentElement().normalize();
            return document;
        } catch (DmarcParseException e) {
            throw e;
        } catch (Exception e) {
            throw new DmarcParseException("Malformed XML: " + e.getMessage(), e);
        }
    }

    // ---- DOM helpers -------------------------------------------------------
    // Navigation is by direct child rather than getElementsByTagName: <dkim> and
    // <spf> appear under both <policy_evaluated> and <auth_results>, so a
    // descendant search would silently mix the two.

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName(node).equals(name)) {
                matches.add((Element) node);
            }
        }
        return matches;
    }

    private static java.util.Optional<Element> child(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(matches.get(0));
    }

    private static Element requireChild(Element parent, String name) {
        return child(parent, name).orElseThrow(() -> new DmarcParseException(
                "Missing required <" + name + "> element inside <" + parent.getNodeName() + ">"));
    }

    /** Text of a direct child element, or {@code null} when absent or blank. */
    private static String text(Element parent, String name) {
        return child(parent, name)
                .map(Element::getTextContent)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }

    private static String requireText(Element parent, String name) {
        String value = text(parent, name);
        if (value == null) {
            throw new DmarcParseException(
                    "Missing required <" + name + "> value inside <" + parent.getNodeName() + ">");
        }
        return value;
    }

    private static Integer integer(Element parent, String name) {
        String value = text(parent, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOrZero(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** DMARC date ranges are Unix timestamps in seconds, always UTC. */
    private static LocalDateTime epochSeconds(Element parent, String name) {
        String value = text(parent, name);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(value)), ZoneOffset.UTC);
        } catch (NumberFormatException e) {
            throw new DmarcParseException("Invalid <" + name + "> timestamp: " + value, e);
        }
    }
}
