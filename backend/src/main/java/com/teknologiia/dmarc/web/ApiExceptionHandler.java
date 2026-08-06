package com.teknologiia.dmarc.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Turns exceptions into a single, predictable error shape: {@code {"detail": "…"}}.
 *
 * <p>Two reasons this exists rather than relying on Spring's default page:
 *
 * <ul>
 *   <li>Spring omits exception messages by default, so a deliberately worded
 *       message — "this account is not activated yet" — never reached the client
 *       and the UI could only show a bare status code.</li>
 *   <li>Turning messages on globally would also expose the text of <em>unexpected</em>
 *       exceptions, which routinely name classes, tables or file paths. Here, only
 *       messages this application chose to write are returned; anything unforeseen
 *       is logged in full and answered with a generic sentence.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    /** Deliberate, user-facing failures raised by the application. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        String detail = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();

        HttpHeaders headers = new HttpHeaders();
        if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            headers.add(HttpHeaders.RETRY_AFTER, "60");
        }

        return ResponseEntity.status(e.getStatusCode()).headers(headers)
                .body(Map.of("detail", detail, "status", e.getStatusCode().value()));
    }

    /**
     * Failed sign-in. Answered as 401 rather than falling through to the catch-all,
     * which would report a server error for an ordinary wrong password.
     *
     * <p>The message is deliberately identical whether the username exists or not:
     * a distinguishable response would let an attacker enumerate accounts.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e) {
        // Spring wraps anything thrown while loading the account — a broken query, a
        // column it cannot map — in an InternalAuthenticationServiceException. Left
        // in the branch below it would be reported as a wrong password, hiding a real
        // fault behind a message that sends everyone looking in the wrong place.
        if (e instanceof InternalAuthenticationServiceException) {
            log.error("Authentication could not be completed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("detail", "Sign-in is temporarily unavailable.", "status", 500));
        }

        log.debug("Authentication failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("detail", "Invalid username or password.", "status", 401));
    }

    /** Bean-validation failures: report the first field message, which is written for users. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Some of the submitted values are not valid.");

        return ResponseEntity.badRequest()
                .body(Map.of("detail", detail, "status", 400));
    }

    /**
     * An address that does not exist.
     *
     * <p>SinglePageAppConfig deliberately refuses to answer anything under
     * {@code /api} with the HTML shell, which leaves the request unresolved and
     * raises this. Without handling it here it fell through to the catch-all below
     * and a mistyped endpoint answered 500 — reporting a fault in the server for
     * what is a fault in the request, and filling the log with stack traces that
     * looked like an outage.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        log.debug("No handler for {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "No such endpoint.", "status", 404));
    }

    /**
     * Anything unplanned. The real cause goes to the log with a stack trace; the
     * caller gets a sentence that reveals nothing about the internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Something went wrong. Please try again.", "status", 500));
    }
}
