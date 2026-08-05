package com.teknologiia.dmarc.dto.user;

/**
 * Result of creating an account.
 *
 * @param generatedPassword the password, when the platform produced it. Present
 *                          exactly once, in this response: it is stored only as a
 *                          hash, so it cannot be retrieved later — only reset.
 */
public record UserCreated(
        UserResponse user,
        String generatedPassword
) {}
