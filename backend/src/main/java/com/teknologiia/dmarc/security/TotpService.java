package com.teknologiia.dmarc.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords, RFC 6238.
 *
 * <p>Deliberately dependency-free. The algorithm is an HMAC of the current 30-second
 * counter, truncated to six digits — small enough that a library would add a supply
 * chain for no benefit, and the parameters below are the ones Google Authenticator,
 * Microsoft Authenticator, 1Password and Authy all assume by default.
 */
@Component
public class TotpService {

    /** Seconds per code. Every authenticator app assumes 30. */
    private static final int STEP_SECONDS = 30;

    /** Digits shown to the user. */
    private static final int DIGITS = 6;

    /**
     * How many steps either side of now are accepted.
     *
     * <p>One step: a code entered as it rolls over, or a device whose clock is up to
     * half a minute out, still works. Widening this weakens the control — every extra
     * step is another 30 seconds during which a stolen code stays usable.
     */
    private static final int WINDOW = 1;

    /** 160 bits, the size RFC 4226 recommends for HMAC-SHA1. */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    /** A fresh shared secret, base32 as authenticator apps expect. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Whether {@code code} is valid for {@code secret} right now.
     *
     * <p>Rejects anything that is not exactly six digits before doing any work, so a
     * recovery code pasted into the wrong box fails fast rather than being hashed.
     */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return false;
        }

        long counter = Instant.now().getEpochSecond() / STEP_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (constantTimeEquals(generate(secret, counter + offset), digits)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans.
     *
     * <p>The issuer appears twice on purpose: as a label prefix for apps that only
     * read the label, and as a parameter for those that read parameters.
     */
    public String otpauthUri(String secret, String account, String issuer) {
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** The code for one counter value. */
    String generate(String secret, long counter) {
        byte[] key = base32Decode(secret);
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xff);
            counter >>= 8;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            // Dynamic truncation, RFC 4226 §5.3.
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);

            int modulo = (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", binary % modulo);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA1 is required by the JVM spec", e);
        }
    }

    /** Length is already fixed at six digits, so only the content needs hiding. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String clean = encoded.trim().replace("=", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        int index = 0;

        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 secret");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }
}
