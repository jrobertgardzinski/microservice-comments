package com.jrobertgardzinski.comments.config;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a comment may stay marked for erasure before the mark stops meaning "a saga is working
 * on it" and starts meaning "the closure never came".
 *
 * <p>In config rather than in the watcher because it is the kind of number people argue about: it
 * trades a false alarm during a slow deployment against the hours a genuinely lost erasure stays
 * unnoticed, and that trade belongs to whoever answers for the obligation.
 *
 * <p>The default is derived, not felt: the orchestrator decides a case within
 * {@code OFFBOARDING_PURGE_TIMEOUT_SEC} (120s) times retries-plus-one (4) — about eight minutes.
 * Thirty leaves room for a slow deployment and still raises a lost closure inside the same shift.
 */
public record ErasureTolerance(Duration markStandsFor) {

    public static final ErasureTolerance DEFAULT = new ErasureTolerance(Duration.ofMinutes(30));

    public ErasureTolerance {
        if (markStandsFor.isNegative() || markStandsFor.isZero()) {
            throw new IllegalArgumentException("a mark must be allowed to stand for some time, was "
                    + markStandsFor);
        }
    }

    /** Marks older than this instant are evidence of a lost closure, not of a running saga. */
    public Instant overdueBefore(Instant now) {
        return now.minus(markStandsFor);
    }
}
