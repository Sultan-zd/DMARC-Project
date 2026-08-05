package com.teknologiia.dmarc.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter keyed by caller.
 *
 * <p>The public scan endpoint spends real resources per call — outbound DNS
 * queries against a domain the caller chooses — so it cannot be left open. A
 * bucket refills continuously, which lets a caller make a short burst and then
 * settle to the sustained rate rather than being cut off at a window boundary.
 *
 * <p><strong>Scope:</strong> state is held in this process. Behind more than one
 * instance each replica enforces the limit separately, so the effective limit
 * multiplies by the replica count. Moving to a shared store (Redis) is the fix
 * when this is deployed with more than one instance.
 */
@Component
@Slf4j
public class RateLimiter {

    /** Entries idle beyond this are dropped so the map cannot grow without bound. */
    private static final Duration IDLE_EVICTION = Duration.ofHours(1);

    /** Sweep for idle entries once the map passes this size. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Value("${app.ratelimit.scan.capacity:10}")
    private int capacity;

    @Value("${app.ratelimit.scan.refill-per-minute:5}")
    private double refillPerMinute;

    /**
     * Attempts to consume one token for {@code key}, using the scan allowance.
     *
     * @return the outcome, including how long to wait when the caller is limited
     */
    public Decision tryAcquire(String key) {
        return tryAcquire(key, capacity, refillPerMinute);
    }

    /**
     * Attempts to consume one token against a caller-supplied allowance.
     *
     * <p>Used by sign-in, which needs a much tighter budget than the public
     * scanner: unlimited attempts against a known username is an open invitation
     * to guess passwords.
     */
    public Decision tryAcquire(String key, int bucketCapacity, double bucketRefillPerMinute) {
        if (buckets.size() > SWEEP_THRESHOLD) {
            evictIdle();
        }

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(bucketCapacity));
        return bucket.tryConsume(bucketCapacity, bucketRefillPerMinute);
    }

    private void evictIdle() {
        long cutoff = System.nanoTime() - IDLE_EVICTION.toNanos();
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastSeenNanos() < cutoff);
        log.debug("Rate limiter swept {} idle entries", before - buckets.size());
    }

    /** Result of a rate-limit check. */
    public record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        long lastSeenNanos() {
            return lastRefillNanos;
        }

        synchronized Decision tryConsume(int capacity, double refillPerMinute) {
            long now = System.nanoTime();
            double elapsedMinutes = (now - lastRefillNanos) / 60_000_000_000d;
            tokens = Math.min(capacity, tokens + elapsedMinutes * refillPerMinute);
            lastRefillNanos = now;

            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, (int) Math.floor(tokens), 0);
            }

            // Seconds until the bucket holds a whole token again.
            double needed = 1 - tokens;
            long retryAfter = (long) Math.ceil(needed / refillPerMinute * 60);
            return new Decision(false, 0, Math.max(1, retryAfter));
        }
    }
}
