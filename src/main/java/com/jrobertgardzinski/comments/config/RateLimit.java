package com.jrobertgardzinski.comments.config;

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
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimit(int perMinute) {
        this.perMinute = perMinute;
    }

    /** Record one action for the key; false when it exceeds the ceiling this minute. */
    public boolean tryAcquire(String key) {
        if (perMinute <= 0) {
            return true;
        }
        Instant now = Instant.now();
        Window updated = windows.compute(key, (k, current) ->
                current == null || Duration.between(current.start(), now).toSeconds() >= 60
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        return updated.count() <= perMinute;
    }
}
