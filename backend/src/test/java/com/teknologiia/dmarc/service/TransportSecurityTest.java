package com.teknologiia.dmarc.service;

import com.teknologiia.dmarc.dto.analysis.TransportSecurityResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the transport check that must be right without a network.
 *
 * <p>The ClientHello is bytes assembled by hand, and a field written at the wrong
 * offset produces a hello that servers reject — which reads as "this server does
 * not support TLS 1.0" rather than as a bug. That failure is indistinguishable from
 * a real answer, so its shape is asserted here rather than trusted.
 *
 * <p>No sockets and no Spring context, matching the rest of the suite.
 */
class TransportSecurityTest {

    // ─── The ClientHello, byte by byte ──────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "TLS 1.0, 0x0301",
            "TLS 1.1, 0x0302",
            "TLS 1.2, 0x0303",
    })
    @DisplayName("the version asked for is the one written into the hello")
    void writesTheRequestedVersion(String label, String codeText) throws IOException {
        int code = Integer.decode(codeText);
        byte[] hello = TlsProbe.clientHello("mx.example.com",
                new TlsProbe.Version(label, code, false));

        // Record header is 5 bytes, then handshake type and a 3-byte length, then the
        // version. Getting this offset wrong is the whole risk of writing TLS by hand.
        int version = ((hello[9] & 0xFF) << 8) | (hello[10] & 0xFF);
        assertThat(version).isEqualTo(code);
    }

    @Test
    @DisplayName("TLS 1.3 keeps the legacy field at 1.2 and says 1.3 in an extension")
    void tls13UsesTheExtension() throws IOException {
        byte[] hello = TlsProbe.clientHello("mx.example.com",
                new TlsProbe.Version("TLS 1.3", 0x0304, false));

        // RFC 8446: a 1.3 hello carries 0x0303 in legacy_version, and a server that
        // saw 0x0304 there would reject it outright.
        int legacy = ((hello[9] & 0xFF) << 8) | (hello[10] & 0xFF);
        assertThat(legacy).isEqualTo(0x0303);

        assertThat(containsExtension(hello, 0x002B))
                .as("supported_versions is the only way to ask for 1.3")
                .isTrue();
        assertThat(containsExtension(hello, 0x0033))
                .as("without a key_share a 1.3 server answers a retry, which reads as a refusal")
                .isTrue();
    }

    @Test
    @DisplayName("the record layer always claims 1.0, whatever is being asked for")
    void recordLayerStaysAtOne() throws IOException {
        for (TlsProbe.Version version : TlsProbe.VERSIONS) {
            byte[] hello = TlsProbe.clientHello("mx.example.com", version);

            assertThat(hello[0]).as("handshake record").isEqualTo((byte) 0x16);
            int recordVersion = ((hello[1] & 0xFF) << 8) | (hello[2] & 0xFF);
            // Middleboxes drop records claiming a newer version before the handshake
            // even starts, which is why every real client does this too.
            assertThat(recordVersion).isEqualTo(0x0301);
        }
    }

    @Test
    @DisplayName("the declared lengths match what was actually written")
    void lengthsAreConsistent() throws IOException {
        byte[] hello = TlsProbe.clientHello("mx.example.com", TlsProbe.VERSIONS.get(2));

        int recordLength = ((hello[3] & 0xFF) << 8) | (hello[4] & 0xFF);
        assertThat(hello.length).isEqualTo(5 + recordLength);

        int handshakeLength = ((hello[6] & 0xFF) << 16)
                | ((hello[7] & 0xFF) << 8) | (hello[8] & 0xFF);
        assertThat(recordLength).isEqualTo(4 + handshakeLength);
    }

    @Test
    @DisplayName("a 1.0 or 1.1 hello carries no signature_algorithms")
    void oldVersionsOmitTheTlsTwelveExtension() throws IOException {
        // Found against a real server, not in review. cloudflare.com answered a
        // fatal handshake_failure to a hello announcing TLS 1.0 that carried
        // signature_algorithms — a TLS 1.2 extension — while accepting the same
        // version from openssl, which omits it.
        //
        // The cost of the bug was a false negative: reporting that a server refuses
        // TLS 1.0 when it accepts it. In a check whose entire purpose is finding
        // deprecated protocols, that is worse than not running at all.
        for (TlsProbe.Version version : TlsProbe.VERSIONS) {
            byte[] hello = TlsProbe.clientHello("mx.example.com", version);
            boolean present = containsExtension(hello, 0x000D);

            assertThat(present)
                    .as("%s should %scarry signature_algorithms",
                            version.label(), version.code() >= 0x0303 ? "" : "not ")
                    .isEqualTo(version.code() >= 0x0303);
        }
    }

    @Test
    @DisplayName("the host is carried in SNI, or a shared server answers for someone else")
    void includesServerName() throws IOException {
        byte[] hello = TlsProbe.clientHello("mx.example.com", TlsProbe.VERSIONS.get(2));

        assertThat(containsExtension(hello, 0x0000)).isTrue();
        assertThat(new String(hello, java.nio.charset.StandardCharsets.US_ASCII))
                .contains("mx.example.com");
    }

    // ─── Reading the answer ─────────────────────────────────────────

    @Test
    @DisplayName("an alert record means the version was refused")
    void alertMeansRefused() throws IOException {
        // 0x15 is alert. A server that will not speak the version asked for answers
        // with one of these rather than a handshake.
        byte[] alert = {0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x46};

        assertThat(TlsProbe.isServerHelloAt(
                new ByteArrayInputStream(alert), TlsProbe.VERSIONS.get(0))).isFalse();
    }

    @Test
    @DisplayName("a server answering a different version has not accepted the one asked for")
    void differentVersionIsNotAcceptance() throws IOException {
        byte[] serverHello = serverHello(0x0303);

        assertThat(TlsProbe.isServerHelloAt(
                new ByteArrayInputStream(serverHello), TlsProbe.VERSIONS.get(0)))
                .as("asked for 1.0, answered 1.2 — 1.0 is not supported")
                .isFalse();
        assertThat(TlsProbe.isServerHelloAt(
                new ByteArrayInputStream(serverHello), TlsProbe.VERSIONS.get(2)))
                .isTrue();
    }

    @Test
    @DisplayName("an empty or truncated answer is not an acceptance")
    void truncatedIsNotAcceptance() throws IOException {
        assertThat(TlsProbe.isServerHelloAt(
                new ByteArrayInputStream(new byte[0]), TlsProbe.VERSIONS.get(2))).isFalse();
        assertThat(TlsProbe.isServerHelloAt(
                new ByteArrayInputStream(new byte[]{0x16, 0x03}), TlsProbe.VERSIONS.get(2)))
                .isFalse();
    }

    // ─── Certificate names ──────────────────────────────────────────

    @Test
    @DisplayName("a wildcard spans one label, not any number of them")
    void wildcardSpansOneLabel() {
        var certificate = certificateNamed("*.example.com");

        assertThat(TransportSecurityService.matches("mx.example.com", certificate)).isTrue();
        assertThat(TransportSecurityService.matches("a.mx.example.com", certificate))
                .as("*.example.com must not cover a.mx.example.com")
                .isFalse();
        assertThat(TransportSecurityService.matches("example.com", certificate))
                .as("a wildcard does not cover the bare domain")
                .isFalse();
    }

    @Test
    @DisplayName("an exact name matches, and a lookalike does not")
    void exactNames() {
        var certificate = certificateNamed("mx.example.com");

        assertThat(TransportSecurityService.matches("mx.example.com", certificate)).isTrue();
        assertThat(TransportSecurityService.matches("MX.EXAMPLE.COM", certificate))
                .as("host names are case-insensitive")
                .isTrue();
        assertThat(TransportSecurityService.matches("mx.example.com.evil.test", certificate))
                .isFalse();
        assertThat(TransportSecurityService.matches("notmx.example.com", certificate)).isFalse();
    }

    @Test
    @DisplayName("the common name is read when there is no matching alternative name")
    void fallsBackToCommonName() {
        var certificate = new TlsProbe.Certificate(
                "CN=mx.example.com, O=Example Ltd", "CN=Some CA",
                null, null, "RSA", 2048, "SHA256withRSA", List.of());

        assertThat(TransportSecurityService.matches("mx.example.com", certificate)).isTrue();
    }

    // ─── MTA-STS parsing ────────────────────────────────────────────

    @Test
    @DisplayName("tags are read out of the record whatever the spacing")
    void readsTags() {
        assertThat(MtaStsClient.tag("v=STSv1; id=20210803T010101;", "id"))
                .isEqualTo("20210803T010101");
        assertThat(MtaStsClient.tag("v=TLSRPTv1;rua=mailto:reports@example.com", "rua"))
                .isEqualTo("mailto:reports@example.com");
        assertThat(MtaStsClient.tag("v=STSv1; id=abc", "missing")).isNull();
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static TlsProbe.Certificate certificateNamed(String name) {
        return new TlsProbe.Certificate("CN=something-else", "CN=CA",
                null, null, "RSA", 2048, "SHA256withRSA", List.of(name));
    }

    /** A minimal ServerHello record declaring one version. */
    private static byte[] serverHello(int version) {
        byte[] payload = new byte[42];
        payload[0] = 0x02;                                  // server_hello
        payload[3] = 38;                                    // handshake length
        payload[4] = (byte) ((version >> 8) & 0xFF);
        payload[5] = (byte) (version & 0xFF);

        byte[] record = new byte[5 + payload.length];
        record[0] = 0x16;
        record[1] = 0x03;
        record[2] = 0x01;
        record[3] = (byte) ((payload.length >> 8) & 0xFF);
        record[4] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, record, 5, payload.length);
        return record;
    }

    /** Walks the extension block looking for one type. */
    private static boolean containsExtension(byte[] hello, int type) {
        int cursor = 5 + 4 + 2 + 32;                        // record, handshake, version, random
        cursor += 1 + (hello[cursor] & 0xFF);               // session id
        int suites = ((hello[cursor] & 0xFF) << 8) | (hello[cursor + 1] & 0xFF);
        cursor += 2 + suites;
        cursor += 1 + (hello[cursor] & 0xFF);               // compression methods
        int extensionsLength = ((hello[cursor] & 0xFF) << 8) | (hello[cursor + 1] & 0xFF);
        cursor += 2;
        int end = cursor + extensionsLength;

        while (cursor + 4 <= end && cursor + 4 <= hello.length) {
            int found = ((hello[cursor] & 0xFF) << 8) | (hello[cursor + 1] & 0xFF);
            int length = ((hello[cursor + 2] & 0xFF) << 8) | (hello[cursor + 3] & 0xFF);
            if (found == type) {
                return true;
            }
            cursor += 4 + length;
        }
        return false;
    }
}
