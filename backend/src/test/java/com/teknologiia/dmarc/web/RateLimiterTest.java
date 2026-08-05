package com.teknologiia.dmarc.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RateLimiter();
        // @Value fields are not populated outside a Spring context.
        ReflectionTestUtils.setField(limiter, "capacity", 3);
        ReflectionTestUtils.setField(limiter, "refillPerMinute", 60d);
    }

    @Test
    @DisplayName("allows a burst up to capacity, then refuses")
    void allowsBurstThenRefuses() {
        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();

        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isFalse();
    }

    @Test
    @DisplayName("reports how long to wait once limited")
    void reportsRetryAfter() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.2.3.4");
        }

        RateLimiter.Decision denied = limiter.tryAcquire("1.2.3.4");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
        assertThat(denied.remaining()).isZero();
    }

    @Test
    @DisplayName("counts each caller separately")
    void tracksCallersIndependently() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("1.2.3.4");
        }
        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isFalse();

        // A different address must be unaffected by the first one's spending.
        assertThat(limiter.tryAcquire("5.6.7.8").allowed()).isTrue();
    }

    @Test
    @DisplayName("reports the remaining allowance as it is spent")
    void reportsRemaining() {
        assertThat(limiter.tryAcquire("9.9.9.9").remaining()).isEqualTo(2);
        assertThat(limiter.tryAcquire("9.9.9.9").remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire("9.9.9.9").remaining()).isZero();
    }

    @Test
    @DisplayName("refills over time")
    void refillsOverTime() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("7.7.7.7");
        }
        assertThat(limiter.tryAcquire("7.7.7.7").allowed()).isFalse();

        // Configured at 60/minute, so one token is restored in about a second.
        Thread.sleep(1100);

        assertThat(limiter.tryAcquire("7.7.7.7").allowed()).isTrue();
    }
}
