package com.teknologiia.dmarc.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who the caller is, for rate limiting.
 *
 * <p>Behind a tunnel or a load balancer every request arrives from the proxy, so
 * {@code getRemoteAddr()} returns the same address for everyone and one person
 * failing to sign in repeatedly locks out the rest. {@code X-Forwarded-For} carries
 * the real one — but only when something trustworthy put it there.
 *
 * <p>The header is a comma-separated chain, appended to as it passes each hop.
 * Anything a client sends arrives at the left; the trusted proxy's own observation
 * lands at the <em>right</em>. Reading the left-most entry — which is what the
 * public scanner did — lets a caller vary a header of their own choosing and get a
 * fresh rate-limit bucket each time. The right-most is the only entry the proxy
 * itself vouches for.
 */
@Component
public class ClientAddress {

    /**
     * Whether {@code X-Forwarded-For} may be believed at all. Off by default: when
     * the application is reachable directly, nothing has written that header except
     * the caller.
     */
    @Value("${app.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    public String of(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                String nearest = hops[hops.length - 1].trim();
                if (!nearest.isEmpty()) {
                    return nearest;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
