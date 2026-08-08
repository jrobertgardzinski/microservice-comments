package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.time.Clock;
import java.time.Instant;

/**
 * The comments service's REVERSIBLE step of an account deletion: every comment the leaver still has
 * in a thread is marked {@link com.jrobertgardzinski.comments.domain.CommentStatus#PENDING_ERASURE}.
 * Nothing is deleted, nothing is anonymised, no vote is retracted — which is precisely what makes
 * this the step the orchestrator can take back ({@link RestoreUserComments}).
 *
 * <p>What a reader sees is nevertheless the finished article: the comments are gone from every
 * thread, from its paging, from its count and from the moderation view, because all of those read
 * through {@link CommentRepository}, which cannot see a marked comment.
 *
 * <p><strong>Idempotent</strong> (workspace ADR 0006): the command arrives at least once, and a
 * second delivery finds the comments already marked — keeping their ORIGINAL instant, so a
 * redelivery never makes an old obligation look fresh to the backlog alarm.
 *
 * <p>The purge rule is deliberately not consulted here: it reads vote scores, and the leaver's own
 * votes are only retracted by the erasure itself. Deciding at mark time would measure the
 * community's judgement against a tally that still contains the departing voter.
 */
public class MarkUserCommentsForErasure {

    private final CommentErasure erasure;
    private final Clock clock;

    public MarkUserCommentsForErasure(CommentErasure erasure, Clock clock) {
        this.erasure = erasure;
        this.clock = clock;
    }

    public void execute(String author) {
        Instant at = Instant.now(clock);
        for (Comment comment : erasure.activeOf(author)) {
            // the aggregate decides what "marked" means (including keeping the first instant on a
            // redelivery); the port only stores the answer
            erasure.store(comment.markForErasure(at));
        }
    }
}
