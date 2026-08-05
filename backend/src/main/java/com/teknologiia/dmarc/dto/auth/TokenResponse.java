package com.teknologiia.dmarc.dto.auth;

/**
 * The outcome of signing in.
 *
 * <p>Carries either an access token or, when the account has a second factor, a
 * short-lived challenge token and nothing else. Keeping both shapes in one record
 * means the caller has to look at {@code mfa_required} — there is no way to read
 * {@code access_token} and get a usable session when one was not granted.
 */
public record TokenResponse(
    String access_token,
    String token_type,
    String role,
    String username,

    /** True when the password was set by someone else and must be replaced. */
    Boolean must_change_password,

    /** True when the password was right but a second factor is still needed. */
    boolean mfa_required,

    /** Proof the password stage passed. Valid for minutes, and for nothing else. */
    String mfa_token
) {

    public static TokenResponse session(String accessToken, String role, String username,
                                        Boolean mustChangePassword) {
        return new TokenResponse(accessToken, "Bearer", role, username,
                mustChangePassword, false, null);
    }

    public static TokenResponse challenge(String mfaToken, String username) {
        return new TokenResponse(null, null, null, username, null, true, mfaToken);
    }
}
