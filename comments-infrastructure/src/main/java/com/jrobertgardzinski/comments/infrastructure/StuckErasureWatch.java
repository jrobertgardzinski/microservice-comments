package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.WatchErasureBacklog;
import com.jrobertgardzinski.comments.domain.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock behind {@link WatchErasureBacklog}, and nothing else.
 *
 * <p>What it used to be: the query, the threshold, the decision and the gauge in one class in
 * infrastructure. What is left is what genuinely belongs to a framework — a schedule, and the
 * handling of a database failure in the vocabulary of the database library.
 *
 * <p>The schedule hangs off {@code @EnableScheduling}, which this service switches on together with
 * the broker (the outbox config). That is the right coupling rather than an accident: without a
 * broker there is no saga, so there are no marks and nothing to watch.
 */
@Component
class StuckErasureWatch {

    private static final Logger LOG = LoggerFactory.getLogger(StuckErasureWatch.class);

    private final WatchErasureBacklog watchBacklog;

    StuckErasureWatch(WatchErasureBacklog watchBacklog) {
        this.watchBacklog = watchBacklog;
    }

    @Scheduled(fixedDelayString = "${comments.erasure.watch-interval-ms:60000}")
    void watch() {
        Observation.ErasureBacklog backlog;
        try {
            backlog = watchBacklog.execute();
        } catch (DataAccessException unreadable) {
            // loud, never fatal — and nothing is stated, so the gauge KEEPS its last value:
            // reporting zero would turn a failed read into "the backlog is clear"
            LOG.error("could not read the erasure backlog — the gauge keeps its last value", unreadable);
            return;
        }
        if (backlog.marked() == 0) {
            return;   // the normal case
        }
        LOG.warn("{} comment(s) have been marked for erasure for longer than the tolerance — the"
                        + " oldest for {}. Their account-deletion saga never sent its closure"
                        + " command, so this content is hidden but NOT erased. Nothing here will"
                        + " delete it on a timer: re-drive the saga from microservice-offboarding,"
                        + " or compensate it",
                backlog.marked(), backlog.oldest());
    }
}
