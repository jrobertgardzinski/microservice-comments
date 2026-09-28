package com.jrobertgardzinski.comments.closure;

import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.unitofwork.UnitOfWork;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.comments.application.CommentEvents;
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
 *
 * <p>ERASE also ANNOUNCES, on the deletion cascade's own topic and in the same unit of work as the
 * delete. A comment this saga destroys may be saved in a collection belonging to somebody who is
 * not leaving, and until this hop existed that pointer was never collected by anything — the
 * cascade cleaned up after a deleted meme and a closure cleaned up after nobody. The announcement
 * is the same COMMENTS_DELETED the cascade's own hop publishes, so collections needed no new
 * consumer and the wire no new message.
 *
 * <p>Like every hop after the pivot it is a choreography: nobody confirms it and nothing
 * compensates it. That is the same trade the memes side already makes by announcing MEME_DELETED
 * from inside its purge.
 */
public final class CommentsClosureParticipant {

    private static final Logger LOG = LoggerFactory.getLogger(CommentsClosureParticipant.class);

    public static final String MARK = ClosureMessages.PURGE_USER_CONTENT;
    public static final String ERASE = ClosureMessages.ERASE_USER_CONTENT;
    public static final String RESTORE = ClosureMessages.RESTORE_USER_CONTENT;

    private final MarkUserCommentsForErasure markForErasure;
    private final RestoreUserComments restoreUserComments;
    private final PurgeUserComments purgeUserComments;
    private final CommentEvents commentEvents;
    private final ClosureConfirmations confirmations;
    private final Observations<Observation> observations;
    private final UnitOfWork unitOfWork;

    public CommentsClosureParticipant(MarkUserCommentsForErasure markForErasure,
                                      RestoreUserComments restoreUserComments,
                                      PurgeUserComments purgeUserComments,
                                      CommentEvents commentEvents,
                                      ClosureConfirmations confirmations,
                                      Observations<Observation> observations,
                                      UnitOfWork unitOfWork) {
        this.markForErasure = markForErasure;
        this.restoreUserComments = restoreUserComments;
        this.purgeUserComments = purgeUserComments;
        this.commentEvents = commentEvents;
        this.confirmations = confirmations;
        this.observations = observations;
        this.unitOfWork = unitOfWork;
    }

    public ClosureOutcome handle(ClosureCommand command) {
        String type = command.type();
        if (!MARK.equals(type) && !ERASE.equals(type) && !RESTORE.equals(type)) {
            return new ClosureOutcome.NotOurs(type);
        }
        String sagaId = command.sagaId();
        if (!command.isAddressed()) {
            // confirming would advance the saga on a deletion that never happened
            LOG.warn("dropping {} without a user id (saga {})", type, sagaId);
            return new ClosureOutcome.Unaddressed(type);
        }
        UserId leaver = command.userId();
        return switch (type) {
            case MARK -> {
                int reserved = markAndConfirm(sagaId, leaver);
                LOG.info("marked {} of one leaver's comments for erasure (saga {})", reserved, sagaId);
                yield new ClosureOutcome.Reserved(reserved);
            }
            case ERASE -> {
                Optional<PurgeRule> rule = requestedRule(command);   // pure reading, kept outside the step
                yield eraseAndAnnounce(sagaId, leaver, rule);
            }
            case RESTORE -> {
                unitOfWork.run(() -> restoreUserComments.execute(leaver));
                LOG.info("restored one leaver's comments: the saga compensated (saga {})", sagaId);
                yield new ClosureOutcome.Restored();
            }
            default -> throw new IllegalStateException("unreachable: " + type);
        };
    }

    /**
     * The delete and the announcement are ONE unit of work, in both directions: a rollback must
     * take the announcement with it, and a commit must make it durable, because nothing in this
     * choreography ever re-derives a lost one. An empty announcement is never made — it would
     * state no fact and would repeat on every redelivery of the ERASE.
     */
    private ClosureOutcome eraseAndAnnounce(String sagaId, UserId leaver, Optional<PurgeRule> rule) {
        AtomicInteger announced = new AtomicInteger();
        unitOfWork.run(() -> {
            PurgeUserComments.Purged purged = purgeUserComments.execute(leaver, rule);
            purged.deletedByMeme().forEach(commentEvents::commentsDeleted);
            announced.set(purged.count());
        });
        LOG.info("erased one leaver's marked comments on the saga's closure and announced {} of "
                + "them to the cascade (saga {})", announced.get(), sagaId);
        return new ClosureOutcome.Erased();
    }

    /** The confirmation is made INSIDE the unit of work: hidden comments with no word owed is the failure mode. */
    private int markAndConfirm(String sagaId, UserId leaver) {
        AtomicInteger reserved = new AtomicInteger();
        unitOfWork.run(() -> {
            int marked = markForErasure.execute(leaver);
            confirmations.confirm(sagaId, leaver, marked);
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
