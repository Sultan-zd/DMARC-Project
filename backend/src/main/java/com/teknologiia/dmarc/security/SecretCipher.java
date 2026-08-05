package com.teknologiia.dmarc.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Reversible encryption for the few secrets this application has to be able to
 * read back.
 *
 * <p>Passwords are hashed, not encrypted — nothing ever needs the original. A
 * mailbox password is different: the server has to present it to an IMAP host on
 * every run, so it must be recoverable. That makes it the one thing here worth
 * stealing a database for, and it is stored encrypted rather than in clear.
 *
 * <p>AES-GCM, with a fresh random nonce per value prepended to the ciphertext. The
 * key comes from configuration and is never generated on the fly: a generated key
 * would change at every restart and silently orphan every password already stored.
 * Without a key, storing a mailbox password is refused outright.
 */
@Component
@Slf4j
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public SecretCipher(@Value("${app.secrets.key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("No SECRETS_KEY configured — mailbox passwords cannot be stored. "
                    + "Generate one with: openssl rand -base64 32");
            return;
        }

        byte[] bytes = Base64.getDecoder().decode(configuredKey.trim());
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "app.secrets.key must decode to exactly " + KEY_BYTES + " bytes; got " + bytes.length);
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    /** @return nonce and ciphertext together, base64 */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the value", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(combined, 0, nonce, 0, NONCE_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(
                    cipher.doFinal(combined, NONCE_BYTES, combined.length - NONCE_BYTES),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Almost always a changed key. Saying so beats "decryption failed".
            throw new IllegalStateException(
                    "Could not decrypt a stored secret — has SECRETS_KEY changed?", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This server cannot store mailbox passwords: no encryption key is configured. "
                            + "Ask the administrator to set SECRETS_KEY.");
        }
    }
}
