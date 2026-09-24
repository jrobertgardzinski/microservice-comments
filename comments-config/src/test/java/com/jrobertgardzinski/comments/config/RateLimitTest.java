package com.jrobertgardzinski.comments.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Config")
@Feature("Rate limit")
class RateLimitTest {

    @Test
    @DisplayName("the ceiling is per key: one noisy account is capped, another is free")
    void per_key_ceiling() {
        RateLimit limit = new RateLimit(3);
        assertTrue(limit.tryAcquire("alice"));
        assertTrue(limit.tryAcquire("alice"));
        assertTrue(limit.tryAcquire("alice"));
        assertFalse(limit.tryAcquire("alice"), "the fourth in a minute is refused");
        assertTrue(limit.tryAcquire("bob"), "a different account is unaffected");
    }

    @Test
    @DisplayName("zero disables the guard")
    void zero_disables() {
        RateLimit limit = new RateLimit(0);
        for (int i = 0; i < 100; i++) {
            assertTrue(limit.tryAcquire("anyone"));
        }
    }

    @Test
    @DisplayName("a new minute lifts the ceiling: the window expires instead of banning forever")
    void the_window_expires() {
        SteppingClock clock = new SteppingClock();
        RateLimit limit = new RateLimit(1, clock);
        assertTrue(limit.tryAcquire("alice"));
        assertFalse(limit.tryAcquire("alice"), "the ceiling holds within the minute");

        clock.advance(Duration.ofSeconds(61));
        assertTrue(limit.tryAcquire("alice"), "a new minute starts a new window");
    }

    @Test
    @DisplayName("the sweep evicts expired windows — the map holds recent commenters, not everyone ever seen")
    void the_sweep_evicts_expired_windows() {
        SteppingClock clock = new SteppingClock();
        RateLimit limit = new RateLimit(5, clock);
        limit.tryAcquire("alice");
        limit.tryAcquire("bob");
        assertEquals(2, limit.trackedKeys(), "active windows are tracked");

        clock.advance(Duration.ofSeconds(61));
        limit.tryAcquire("carol");   // the first call of the new minute triggers the sweep

        assertEquals(1, limit.trackedKeys(),
                "expired windows are gone; only the fresh caller remains");
    }

    /** A clock the test moves by hand, so expiry needs no real waiting. */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-07-25T12:00:00Z");

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
