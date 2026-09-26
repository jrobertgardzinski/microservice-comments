package com.jrobertgardzinski.comments.closure;

import com.jrobertgardzinski.closure.Atomically;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.comments.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The comment service's side of the account-closure saga. MARK hides and confirms; ERASE applies
 * the rule; RESTORE compensates. Only MARK is confirmed, and all three are idempotent. Knows
 * nothing about Kafka, so the same class runs in one process.
 */
public final class CommentsClosureParticipant {

    private static final Logger LOG = LoggerFactory.getLogger(CommentsClosureParticipant.class);

    public static final String MARK = ClosureMessages.PURGE_USER_CONTENT;
    public static final String ERASE = ClosureMessages.ERASE_USER_CONTENT;
    public static final String RESTORE = ClosureMessages.RESTORE_USER_CONTENT;

    private final MarkUserCommentsForErasure markForErasure;
    private final RestoreUserComments restoreUserComments;
    private final PurgeUserComments purgeUserComments;
    private final ClosureConfirmations confirmations;
    private final Observations<Observation> observations;
    private final Atomically atomically;

    public CommentsClosureParticipant(MarkUserCommentsForErasure markForErasure,
                                      RestoreUserComments restoreUserComments,
                                      PurgeUserComments purgeUserComments,
                                      ClosureConfirmations confirmations,
                                      Observations<Observation> observations,
                                      Atomically atomically) {
        this.markForErasure = markForErasure;
        this.restoreUserComments = restoreUserComments;
        this.purgeUserComments = purgeUserComments;
        this.confirmations = confirmations;
        this.observations = observations;
        this.atomically = atomically;
    }

    public ClosureOutcome handle(ClosureCommand command) {
        String type = command.type();
        if (!MARK.equals(type) && !ERASE.equals(type) && !RESTORE.equals(type)) {
            return new ClosureOutcome.NotOurs(type);
        }
        String sagaId = command.sagaId();
        if (!command.isAddressed()) {
            // confirming would advance the saga on a deletion that never happened
            LOG.warn("dropping {} without an email (saga {})", type, sagaId);
            return new ClosureOutcome.Unaddressed(type);
        }
        String email = command.email();   // PII: never logged
        return switch (type) {
            case MARK -> {
                int reserved = markAndConfirm(sagaId, email);
                LOG.info("marked {} of one leaver's comments for erasure (saga {})", reserved, sagaId);
                yield new ClosureOutcome.Reserved(reserved);
            }
            case ERASE -> {
                Optional<PurgeRule> rule = requestedRule(command);   // pure reading, kept outside the step
                atomically.run(() -> purgeUserComments.execute(email, rule));
                LOG.info("erased one leaver's marked comments on the saga's closure (saga {})", sagaId);
                yield new ClosureOutcome.Erased();
            }
            case RESTORE -> {
                atomically.run(() -> restoreUserComments.execute(email));
                LOG.info("restored one leaver's comments: the saga compensated (saga {})", sagaId);
                yield new ClosureOutcome.Restored();
            }
            default -> throw new IllegalStateException("unreachable: " + type);
        };
    }

    /** The confirmation is made INSIDE the unit of work: hidden comments with no word owed is the failure mode. */
    private int markAndConfirm(String sagaId, String email) {
        AtomicInteger reserved = new AtomicInteger();
        atomically.run(() -> {
            int marked = markForErasure.execute(email);
            confirmations.confirm(sagaId, email, marked);
            reserved.set(marked);
        });
        if (reserved.get() == 0) {
            // "nothing of theirs" and "rows still under their old address" look the same from here
            observations.record(new Observation.PurgeReservedNothing());
            LOG.warn("confirmed a purge that reserved NOTHING (saga {})", sagaId);
        }
        return reserved.get();
    }

    /** A self-closure always deletes; an administrator's rule is honoured; an unreadable one falls back. */
    private Optional<PurgeRule> requestedRule(ClosureCommand command) {
        if (!command.allowsConditions()) {
            if (command.rule().isPresent()) {
                LOG.warn("a self-requested closure arrived carrying a comments purge rule; ignoring it and deleting");
            }
            return Optional.of(new PurgeRule.Delete());
        }
        if (command.rule().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PurgeRule.parse(command.rule().get()));
        } catch (IllegalArgumentException invalid) {
            LOG.warn("ignoring an unparseable comments purge rule, using the default: {}", invalid.getMessage());
            return Optional.empty();
        }
    }
}
