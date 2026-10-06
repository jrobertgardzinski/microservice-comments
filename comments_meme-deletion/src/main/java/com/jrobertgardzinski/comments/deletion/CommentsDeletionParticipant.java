package com.jrobertgardzinski.comments.deletion;

import com.jrobertgardzinski.comments.domain.core.CommentEvents;
import com.jrobertgardzinski.comments.system.erasure.DeleteThread;
import com.jrobertgardzinski.deletion.CommentsDeleted;
import com.jrobertgardzinski.deletion.DeletionOutcome;
import com.jrobertgardzinski.deletion.MemeDeleted;
import com.jrobertgardzinski.unitofwork.UnitOfWork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * This service's hop of the deletion cascade: a meme went, so its conversation goes, and whoever
 * saved one of those comments is told which ones they were.
 *
 * <p>Both halves are one unit of work. The announcement is not a consequence of dropping a
 * thread but of the CASCADE — which is why it is made here and not inside {@link DeleteThread} —
 * and it has to share the drop's fate in both directions: a rollback must take it with it, and a
 * commit must make it durable, because nothing in this choreography ever re-derives a lost
 * announcement. A redelivered MEME_DELETED finds an empty thread and deliberately says nothing.
 */
public class CommentsDeletionParticipant {

    private static final Logger LOG = LoggerFactory.getLogger(CommentsDeletionParticipant.class);

    private final DeleteThread deleteThread;
    private final CommentEvents commentEvents;
    private final UnitOfWork unitOfWork;

    public CommentsDeletionParticipant(DeleteThread deleteThread, CommentEvents commentEvents,
                                       UnitOfWork unitOfWork) {
        this.deleteThread = deleteThread;
        this.commentEvents = commentEvents;
        this.unitOfWork = unitOfWork;
    }

    /** Drop the meme's thread and, in the same unit of work, name what went. */
    public DeletionOutcome handle(MemeDeleted memeDeleted) {
        List<String> dropped = dropTheThreadAndAnnounceIt(memeDeleted.memeId());
        return dropped.isEmpty()
                ? new DeletionOutcome.Nothing()
                : new DeletionOutcome.Dropped(dropped.size());
    }

    /**
     * Not this hop's business: this service is where COMMENTS_DELETED is PRODUCED. It is on the
     * contract because a hop must be able to say so without failing — a shared topic carries
     * other conversations, and a hop that threw on one of them would wedge the partition.
     */
    public DeletionOutcome handle(CommentsDeleted commentsDeleted) {
        return new DeletionOutcome.Nothing();
    }

    private List<String> dropTheThreadAndAnnounceIt(String memeId) {
        List<String> dropped = new ArrayList<>();
        unitOfWork.run(() -> {
            dropped.addAll(deleteThread.execute(memeId));
            if (dropped.isEmpty()) {
                // a meme nobody commented on, or a redelivered MEME_DELETED whose cascade already
                // ran: an empty COMMENTS_DELETED states no fact a consumer could act on, and the
                // idempotent cascade would re-emit it on every redelivery — noise on a shared topic
                return;
            }
            commentEvents.commentsDeleted(memeId, List.copyOf(dropped));
        });
        LOG.info("dropped the comment thread of deleted meme {} ({} comment(s))",
                memeId, dropped.size());
        return List.copyOf(dropped);
    }
}
