package com.jrobertgardzinski.comments.closure;

import com.jrobertgardzinski.closure.AtomicClosureParticipant;
import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureConfirmations;
import com.jrobertgardzinski.closure.ClosureOutcome;
import com.jrobertgardzinski.comments.application.CommentEvents;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.comments.domain.Observation;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.purge.RequestedRule;
import com.jrobertgardzinski.unitofwork.UnitOfWork;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The comment service's side of the account-closure saga. MARK hides and confirms; ERASE applies
 * the rule; RESTORE compensates. Only MARK is confirmed, and all three are idempotent.
 *
 * <p>The guard in front of the three commands, the switch between them and the mark's confirmation
 * are {@link AtomicClosureParticipant}'s, shared with the meme service's participant — what is left
 * here is this axis. Knows nothing about Kafka, so the same class runs in one process.
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
public final class CommentsClosureParticipant extends AtomicClosureParticipant {

    /** This axis's own word for itself, and only for the log line that names it. */
    private static final String AXIS = "comments";

    private final MarkUserCommentsForErasure markForErasure;
    private final RestoreUserComments restoreUserComments;
    private final PurgeUserComments purgeUserComments;
    private final CommentEvents commentEvents;
    private final Observations<Observation> observations;

    public CommentsClosureParticipant(MarkUserCommentsForErasure markForErasure,
                                      RestoreUserComments restoreUserComments,
                                      PurgeUserComments purgeUserComments,
                                      CommentEvents commentEvents,
                                      ClosureConfirmations confirmations,
                                      Observations<Observation> observations,
                                      UnitOfWork unitOfWork) {
        super(confirmations, unitOfWork);
        this.markForErasure = markForErasure;
        this.restoreUserComments = restoreUserComments;
        this.purgeUserComments = purgeUserComments;
        this.commentEvents = commentEvents;
        this.observations = observations;
    }

    @Override
    protected int mark(String sagaId, UserId leaver) {
        return markForErasure.execute(leaver);
    }

    @Override
    protected void marked(String sagaId, int rows) {
        log.info("marked {} of one leaver's comments for erasure (saga {})", rows, sagaId);
    }

    /**
     * The delete and the announcement are ONE unit of work, in both directions: a rollback must
     * take the announcement with it, and a commit must make it durable, because nothing in this
     * choreography ever re-derives a lost one. An empty announcement is never made — it would
     * state no fact and would repeat on every redelivery of the ERASE.
     */
    @Override
    protected ClosureOutcome erase(ClosureCommand command, UserId leaver) {
        RequestedRule requested =
                RequestedRule.of(command.allowsConditions(), command.rule(), AXIS);   // pure reading, kept outside the step
        requested.complaint().ifPresent(log::warn);
        AtomicInteger announced = new AtomicInteger();
        inUnitOfWork(() -> {
            PurgeUserComments.Purged purged = purgeUserComments.execute(leaver, requested.rule());
            purged.deletedByMeme().forEach(commentEvents::commentsDeleted);
            announced.set(purged.count());
        });
        log.info("erased one leaver's marked comments on the saga's closure and announced {} of "
                + "them to the cascade (saga {})", announced.get(), command.sagaId());
        // the destroyed ones, which are exactly the announced ones; what the rule KEPT came back
        // into its thread anonymised and is nobody's residue
        return new ClosureOutcome.Erased(announced.get(), ClosureOutcome.UNCOUNTED);
    }

    @Override
    protected ClosureOutcome restore(String sagaId, UserId leaver) {
        inUnitOfWork(() -> restoreUserComments.execute(leaver));
        log.info("restored one leaver's comments: the saga compensated (saga {})", sagaId);
        return ClosureOutcome.Restored.uncounted();
    }

    @Override
    protected void reservedNothing() {
        observations.record(new Observation.PurgeReservedNothing());
    }
}
