package com.jrobertgardzinski.comments.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed per-key, per-minute window — server policy against comment spam. Keyed by whatever the
 * caller chooses (here: the author's e-mail), so one noisy account cannot flood a thread while
 * everyone else comments freely. Zero disables the guard. Pure logic; no framework.
 */
public final class RateLimit {

    private record Window(Instant start, int count) {}

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile Instant lastSweep = Instant.EPOCH;

    public RateLimit(int perMinute) {
        this(perMinute, Clock.systemUTC());
    }

    /** The clock is injectable so expiry (and the eviction it drives) is testable without waiting. */
    public RateLimit(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** Record one action for the key; false when it exceeds the ceiling this minute. */
    public boolean tryAcquire(String key) {
        if (perMinute <= 0) {
            return true;
        }
        Instant now = clock.instant();
        sweepExpired(now);
        Window updated = windows.compute(key, (k, current) ->
                current == null || Duration.between(current.start(), now).toSeconds() >= 60
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        return updated.count() <= perMinute;
    }

    /**
     * Expired windows would otherwise pile up forever — one entry per author ever seen. A sweep
     * roughly once a minute keeps the map bounded to recent commenters at O(n) once per window,
     * not per call. "Roughly": the lastSweep check-then-set is not a CAS, so two threads arriving
     * together may both sweep — a harmless duplicate pass, not a correctness problem. Races are
     * harmless: a stale window seen by compute() resets the same way.
     */
    private void sweepExpired(Instant now) {
        if (Duration.between(lastSweep, now).toSeconds() < 60) {
            return;
        }
        lastSweep = now;
        windows.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().start(), now).toSeconds() >= 60);
    }

    /** How many keys are currently tracked — exposed for the eviction pin, not for policy. */
    int trackedKeys() {
        return windows.size();
    }
}
