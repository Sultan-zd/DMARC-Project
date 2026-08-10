package com.teknologiia.dmarc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * What an SMTP server actually offers, as opposed to what its domain claims.
 *
 * <p>Two things are asked of each host, and they need different tools.
 *
 * <p><strong>The certificate</strong> comes from a normal handshake, with a trust
 * manager that accepts anything. That is deliberate and it is not a weakness: this
 * is an audit, not a delivery. Refusing to complete the handshake because a
 * certificate is expired would hide the very fact being looked for.
 *
 * <p><strong>The protocol versions</strong> cannot come from {@code SSLSocket} at
 * all. Java 17 disables TLS 1.0 and 1.1 in {@code jdk.tls.disabledAlgorithms}, so
 * asking it to try them fails inside the JVM before a packet is sent — a server
 * happily accepting TLS 1.0 would be reported as refusing it, which is exactly
 * backwards for a check whose purpose is finding deprecated protocols. The
 * alternative, editing that security property, is global: it would weaken every
 * other TLS connection this process makes, to run a diagnostic.
 *
 * <p>So the ClientHello is written by hand and the ServerHello read back. About
 * eighty lines of bytes, and the only way to ask the question honestly.
 */
@Component
@Slf4j
public class TlsProbe {

    private static final int SMTP_PORT = 25;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    /** RFC 8996 deprecates both; finding either is the point of the check. */
    public static final List<Version> VERSIONS = List.of(
            new Version("TLS 1.0", 0x0301, true),
            new Version("TLS 1.1", 0x0302, true),
            new Version("TLS 1.2", 0x0303, false),
            new Version("TLS 1.3", 0x0304, false));

    public record Version(String label, int code, boolean deprecated) {}

    public record Certificate(
            String subject, String issuer,
            java.util.Date notBefore, java.util.Date notAfter,
            String keyAlgorithm, Integer keyBits, String signatureAlgorithm,
            List<String> names) {}

    // ─── The certificate ────────────────────────────────────────────

    /**
     * Completes a STARTTLS handshake and reads the certificate the server presented.
     *
     * @return null when the host refused, offered no STARTTLS, or never answered
     */
    public Certificate certificateOf(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, SMTP_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            if (!negotiateStartTls(socket)) {
                return null;
            }

            SSLSocketFactory factory = permissiveContext().getSocketFactory();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, SMTP_PORT, false)) {
                // SNI matters: many mail hosts sit behind shared infrastructure and
                // answer with the wrong certificate without it.
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                tls.setUseClientMode(true);
                tls.startHandshake();

                java.security.cert.Certificate[] chain = tls.getSession().getPeerCertificates();
                if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                    return null;
                }
                return describe(leaf);
            }
        } catch (Exception e) {
            log.debug("Certificate probe failed for {}: {}", host, e.getMessage());
            return null;
        }
    }

    private Certificate describe(X509Certificate leaf) {
        Integer bits = null;
        var key = leaf.getPublicKey();
        if (key instanceof java.security.interfaces.RSAPublicKey rsa) {
            bits = rsa.getModulus().bitLength();
        } else if (key instanceof java.security.interfaces.ECPublicKey ec) {
            bits = ec.getParams().getCurve().getField().getFieldSize();
        }

        List<String> names = new ArrayList<>();
        try {
            var alternatives = leaf.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (var entry : alternatives) {
                    // Type 2 is dNSName; the rest (IP, email) do not name a host.
                    if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0))) {
                        names.add(String.valueOf(entry.get(1)).toLowerCase());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read subject alternative names: {}", e.getMessage());
        }

        return new Certificate(
                leaf.getSubjectX500Principal().getName(),
                leaf.getIssuerX500Principal().getName(),
                leaf.getNotBefore(), leaf.getNotAfter(),
                key.getAlgorithm(), bits, leaf.getSigAlgName(), names);
    }

    // ─── The protocol versions ──────────────────────────────────────

    /** Whether this host completes a handshake at exactly this version. */
    public boolean accepts(String host, Version version) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, SMTP_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            if (!negotiateStartTls(socket)) {
                return false;
            }

            socket.getOutputStream().write(clientHello(host, version));
            socket.getOutputStream().flush();

            return isServerHelloAt(socket.getInputStream(), version);
        } catch (Exception e) {
            log.debug("{} probe failed for {}: {}", version.label(), host, e.getMessage());
            return false;
        }
    }

    /**
     * A ClientHello offering exactly one version.
     *
     * <p>The record layer says 1.0 whatever is being asked for, which is what every
     * real client does — some middleboxes drop records claiming a newer version
     * before the handshake starts.
     */
    static byte[] clientHello(String host, Version version) throws IOException {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // TLS 1.3 keeps the legacy field at 1.2 and states the real version in an
        // extension; anything older states it here.
        int legacy = version.code() == 0x0304 ? 0x0303 : version.code();
        write16(body, legacy);
        body.write(random);
        body.write(0);                                    // no session id

        // A broad offer, so an old server can still find something it knows. The
        // cipher suites are not what is being tested — the version is.
        int[] suites = {
                0x1302, 0x1303, 0x1301,                   // TLS 1.3
                0xC02F, 0xC030, 0xC02B, 0xC02C,           // ECDHE-RSA / ECDSA with AES-GCM
                0x009C, 0x009D,                           // RSA AES-GCM
                0xC013, 0xC014,                           // ECDHE-RSA AES-CBC
                0x002F, 0x0035,                           // RSA AES-CBC, for very old servers
                0x000A,                                   // 3DES, last resort
        };
        write16(body, suites.length * 2);
        for (int suite : suites) {
            write16(body, suite);
        }

        body.write(1);                                    // one compression method
        body.write(0);                                    // null

        byte[] extensions = extensions(host, version);
        write16(body, extensions.length);
        body.write(extensions);

        byte[] hello = body.toByteArray();

        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(0x01);                            // client_hello
        handshake.write((hello.length >> 16) & 0xFF);
        handshake.write((hello.length >> 8) & 0xFF);
        handshake.write(hello.length & 0xFF);
        handshake.write(hello);

        byte[] payload = handshake.toByteArray();

        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(0x16);                               // handshake
        write16(record, 0x0301);                          // record version, always 1.0
        write16(record, payload.length);
        record.write(payload);

        return record.toByteArray();
    }

    private static byte[] extensions(String host, Version version) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // server_name — many hosts answer with a different certificate without it
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream sni = new ByteArrayOutputStream();
        write16(sni, name.length + 3);
        sni.write(0);                                     // host_name
        write16(sni, name.length);
        sni.write(name);
        extension(out, 0x0000, sni.toByteArray());

        // supported_groups — without one, ECDHE suites cannot be chosen
        ByteArrayOutputStream groups = new ByteArrayOutputStream();
        int[] curves = {0x001D, 0x0017, 0x0018, 0x0019};  // x25519, secp256r1/384/521
        write16(groups, curves.length * 2);
        for (int curve : curves) {
            write16(groups, curve);
        }
        extension(out, 0x000A, groups.toByteArray());

        // ec_point_formats — uncompressed
        extension(out, 0x000B, new byte[]{1, 0});

        // signature_algorithms — introduced by TLS 1.2, and sent only for 1.2 and
        // above.
        //
        // Found by testing: cloudflare.com answered a fatal handshake_failure to a
        // hello that announced TLS 1.0 and carried this extension, while accepting
        // the same version from openssl, which omits it. RFC 5246 says a server must
        // ignore extensions it does not know, but a 1.0 hello carrying a 1.2-only
        // extension is contradictory and strict stacks refuse it. The cost of
        // getting this wrong is a false negative — reporting that a server rejects
        // TLS 1.0 when it accepts it, which in a security product is worse than
        // saying nothing.
        if (version.code() >= 0x0303) {
            ByteArrayOutputStream signatures = new ByteArrayOutputStream();
            int[] algorithms = {0x0403, 0x0503, 0x0603, 0x0804, 0x0805, 0x0806,
                                0x0401, 0x0501, 0x0601, 0x0201};
            write16(signatures, algorithms.length * 2);
            for (int algorithm : algorithms) {
                write16(signatures, algorithm);
            }
            extension(out, 0x000D, signatures.toByteArray());
        }

        // supported_versions — the only way to ask for 1.3, and asking for exactly
        // one version is what makes this a test of that version rather than of the
        // server's preference.
        if (version.code() == 0x0304) {
            extension(out, 0x002B, new byte[]{2, 0x03, 0x04});

            // key_share with an x25519 key. TLS 1.3 servers answer a hello without
            // one by asking for a retry, which reads the same as a refusal.
            byte[] keyShare = new byte[32];
            new SecureRandom().nextBytes(keyShare);
            ByteArrayOutputStream shares = new ByteArrayOutputStream();
            write16(shares, 36);
            write16(shares, 0x001D);
            write16(shares, 32);
            shares.write(keyShare);
            extension(out, 0x0033, shares.toByteArray());
        }

        return out.toByteArray();
    }

    private static void extension(ByteArrayOutputStream out, int type, byte[] data)
            throws IOException {
        write16(out, type);
        write16(out, data.length);
        out.write(data);
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Reads the answer and decides whether it is a ServerHello at the version asked
     * for.
     *
     * <p>An alert record means refused. A handshake record means accepted — but for
     * 1.3 the version in the ServerHello still says 1.2, and the truth is in the
     * supported_versions extension, so that one is read out.
     */
    static boolean isServerHelloAt(InputStream input, Version version) throws IOException {
        byte[] header = input.readNBytes(5);
        if (header.length < 5) {
            return false;
        }
        int type = header[0] & 0xFF;
        if (type != 0x16) {
            return false;                                 // alert, or not TLS at all
        }

        int length = ((header[3] & 0xFF) << 8) | (header[4] & 0xFF);
        byte[] payload = input.readNBytes(Math.min(length, 16 * 1024));
        if (payload.length < 6 || (payload[0] & 0xFF) != 0x02) {
            return false;                                 // not a ServerHello
        }

        int declared = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);

        if (version.code() == 0x0304) {
            return declared == 0x0303 && announcesTls13(payload);
        }
        // A server that was asked for one version and answered with another has not
        // accepted the one asked for.
        return declared == version.code();
    }

    /** Looks for supported_versions = 0x0304 among the ServerHello extensions. */
    private static boolean announcesTls13(byte[] hello) {
        try {
            int cursor = 4 + 2 + 32;                      // header, version, random
            int sessionId = hello[cursor] & 0xFF;
            cursor += 1 + sessionId + 2 + 1;              // session id, cipher, compression
            if (cursor + 2 > hello.length) {
                return false;
            }
            int extensionsLength = ((hello[cursor] & 0xFF) << 8) | (hello[cursor + 1] & 0xFF);
            cursor += 2;
            int end = Math.min(cursor + extensionsLength, hello.length);

            while (cursor + 4 <= end) {
                int type = ((hello[cursor] & 0xFF) << 8) | (hello[cursor + 1] & 0xFF);
                int length = ((hello[cursor + 2] & 0xFF) << 8) | (hello[cursor + 3] & 0xFF);
                cursor += 4;
                if (type == 0x002B && length == 2 && cursor + 1 < hello.length) {
                    return ((hello[cursor] & 0xFF) << 8 | (hello[cursor + 1] & 0xFF)) == 0x0304;
                }
                cursor += length;
            }
        } catch (RuntimeException e) {
            // A malformed ServerHello is not a 1.3 ServerHello.
            return false;
        }
        return false;
    }

    // ─── SMTP ───────────────────────────────────────────────────────

    /** Greeting, EHLO, STARTTLS. True once the socket is ready for a handshake. */
    private boolean negotiateStartTls(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        String greeting = readResponse(input);
        if (!greeting.startsWith("220")) {
            return false;
        }

        output.write("EHLO dmarc-dashboard.teknologiia.com\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String capabilities = readResponse(input);
        if (!capabilities.toUpperCase().contains("STARTTLS")) {
            return false;
        }

        output.write("STARTTLS\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return readResponse(input).startsWith("220");
    }

    /**
     * One SMTP reply, including a multi-line one.
     *
     * <p>A multi-line reply marks every line but the last with a hyphen after the
     * code. Stopping at the first newline would leave the rest in the buffer and
     * desynchronise everything after it.
     */
    private String readResponse(InputStream input) throws IOException {
        StringBuilder all = new StringBuilder();
        StringBuilder line = new StringBuilder();
        int character;

        while ((character = input.read()) != -1) {
            if (character == '\n') {
                String complete = line.toString().trim();
                all.append(complete).append('\n');
                // "250-" continues, "250 " ends.
                if (complete.length() < 4 || complete.charAt(3) != '-') {
                    break;
                }
                line.setLength(0);
            } else if (character != '\r') {
                line.append((char) character);
                if (line.length() > 2048) {
                    break;                                // not a well-behaved server
                }
            }
        }
        return all.toString();
    }

    /**
     * Accepts any certificate.
     *
     * <p>The point is to read what the server presents, including when it is expired
     * or self-signed. Verifying it would throw away the finding.
     */
    private SSLContext permissiveContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }
}
