package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.config.ErasureTolerance;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.Observation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Counts the obligations this service is sitting on — and does nothing about them on purpose.
 *
 * <p>A comment is marked {@code PENDING_ERASURE} by the saga's first, reversible step, and only the
 * orchestrator's closure turns that mark into a delete. A mark standing longer than any saga can
 * legitimately last therefore means the closure never arrived. The comment is invisible, which is
 * what the leaver asked for, but still stored, which is not what the law asked for — and nobody
 * would find out, because the failure is silent by construction.
 *
 * <p>Erasing on a timer would mean guessing the orchestrator's decision, and the guess is wrong
 * exactly where it is expensive: a saga stuck because a sibling is down may still COMPENSATE, and a
 * comment erased on a clock cannot come back. So the backlog is stated, and an operator acts on it.
 *
 * <p>It states the fact on EVERY pass, zero included — the question is "how many right now", so
 * saying nothing would leave yesterday's answer standing as if it were today's.
 */
public class WatchErasureBacklog {

    private final CommentErasure erasure;
    private final ErasureTolerance tolerance;
    private final Observations observations;
    private final Clock clock;

    public WatchErasureBacklog(CommentErasure erasure, ErasureTolerance tolerance,
                               Observations observations, Clock clock) {
        this.erasure = erasure;
        this.tolerance = tolerance;
        this.observations = observations;
        this.clock = clock;
    }

    /** Answers what it stated, so a caller can log the detail without asking the database twice. */
    public Observation.ErasureBacklog execute() {
        Instant now = clock.instant();
        List<Comment> overdue = erasure.pendingSince(tolerance.overdueBefore(now));
        if (overdue.isEmpty()) {
            observations.record(Observation.ErasureBacklog.NONE);
            return Observation.ErasureBacklog.NONE;
        }
        // the OLDEST mark decides how bad this is; an age is not personal data, which matters here
        // because the people behind these comments are the ones this service is trying to forget
        Duration oldest = Duration.between(overdue.getFirst().markedForErasureAt(), now);
        Observation.ErasureBacklog backlog = new Observation.ErasureBacklog(overdue.size(), oldest);
        observations.record(backlog);
        return backlog;
    }
}
