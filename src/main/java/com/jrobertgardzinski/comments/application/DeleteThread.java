package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops a meme's whole comment thread — the cascade behind a MEME_DELETED event from
 * microservice-memes: when the meme goes, its conversation goes. Idempotent.
 *
 * <p>It RETURNS the ids it dropped, because this service is the only one in the portal that knows
 * which comments hung under that meme. Nobody downstream (microservice-user-collections may hold a
 * saved comment among them) can derive that from the meme id alone — so the next hop of the
 * choreography exists only if this step reports its own work.
 *
 * <p>Reporting, not announcing: publishing belongs to the caller. The transaction wraps THIS method
 * (the decorator in CommentsConfig), so anything published from inside it would escape before the
 * commit — and a rollback would have announced comments that are still there. Returning the ids
 * hands the announcement to a caller that only ever sees them after a successful commit.
 */
public class DeleteThread {

    private final CommentRepository commentRepository;
    private final CommentVotes commentVotes;

    public DeleteThread(CommentRepository commentRepository, CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.commentVotes = commentVotes;
    }

    /** @return the ids of the comments this run dropped; empty when the thread was already gone. */
    public List<String> execute(String memeId) {
        List<String> dropped = new ArrayList<>();
        for (Comment comment : commentRepository.findByMeme(memeId)) {
            commentVotes.purgeComment(comment.id());
            dropped.add(comment.id());
        }
        commentRepository.deleteByMeme(memeId);
        // idempotent (ADR 0006): a redelivered MEME_DELETED finds nothing and reports nothing,
        // which is also what keeps the cascade's announcement from repeating on every retry
        return List.copyOf(dropped);
    }
}
