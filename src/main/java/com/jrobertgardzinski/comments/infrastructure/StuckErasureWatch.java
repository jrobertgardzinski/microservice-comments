package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.comments.domain.Comment;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches the erasure backlog — and deliberately does nothing about it. The twin of the meme
 * service's watch of the same name, for the same reason and with the same refusal to act.
 *
 * <p>A comment is marked by the saga's reversible step, and only the orchestrator's closure command
 * turns that mark into a delete. A mark older than any saga can legitimately last therefore means
 * the closure never arrived — the participant's retry budget was spent, or the record was dropped.
 * The comment is invisible, which is what the leaver asked for, but still stored, which is not what
 * the GDPR asked for, and nothing about that state throws or breaks: it is silent by construction.
 *
 * <p>Erasing on a timer would mean guessing the orchestrator's decision, and the guess is wrong
 * exactly where it is expensive — a saga stuck because a sibling participant is down may still
 * COMPENSATE, and a comment erased on a clock cannot come back. So the backlog becomes an alarm
 * ({@code comments_erasure_backlog}) that an operator acts on, never an expiry.
 *
 * <p>The schedule hangs off {@code @EnableScheduling}, which this service switches on together with
 * the broker (the outbox config). That is the right coupling rather than an accident: without a
 * broker there is no saga, so there are no marks and nothing to watch.
 */
@Component
class StuckErasureWatch {

    private static final Logger LOG = LoggerFactory.getLogger(StuckErasureWatch.class);

    /**
     * How old a mark must be to be evidence of a lost command rather than of a running saga. The
     * orchestrator decides a case within {@code OFFBOARDING_PURGE_TIMEOUT_SEC} (120s) times the
     * retries plus one (3 + 1) — about eight minutes; thirty leaves room for a slow deployment and
     * still raises a genuinely lost closure inside the same shift.
     */
    static final Duration DEFAULT_STUCK_AFTER = Duration.ofMinutes(30);

    private final CommentErasure erasure;
    private final Clock clock;
    private final Duration stuckAfter;
    private final AtomicLong backlog = new AtomicLong();

    @Autowired
    StuckErasureWatch(CommentErasure erasure, Clock clock, MeterRegistry meters,
                      @Value("${comments.erasure.stuck-after-seconds:1800}") long stuckAfterSeconds) {
        this(erasure, clock, meters, Duration.ofSeconds(stuckAfterSeconds));
    }

    StuckErasureWatch(CommentErasure erasure, Clock clock, MeterRegistry meters, Duration stuckAfter) {
        this.erasure = erasure;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        meters.gauge("comments.erasure.backlog", backlog, AtomicLong::get);
    }

    @Scheduled(fixedDelayString = "${comments.erasure.watch-interval-ms:60000}")
    void watch() {
        List<Comment> stuck;
        try {
            stuck = erasure.pendingSince(clock.instant().minus(stuckAfter));
        } catch (DataAccessException unreadable) {
            // loud, never fatal — and the gauge KEEPS its last value: reporting zero would turn a
            // failed read into "the backlog is clear"
            LOG.error("could not read the erasure backlog — the gauge keeps its last value", unreadable);
            return;
        }
        backlog.set(stuck.size());
        if (stuck.isEmpty()) {
            return;   // the normal case
        }
        Instant oldest = stuck.getFirst().markedForErasureAt();
        LOG.warn("{} comment(s) have been marked for erasure for longer than {} — the oldest for {}."
                        + " Their account-deletion saga never sent its closure command, so this"
                        + " content is hidden but NOT erased. Nothing here will delete it on a"
                        + " timer: re-drive the saga from microservice-offboarding, or compensate it",
                stuck.size(), stuckAfter, Duration.between(oldest, clock.instant()));
    }
}
