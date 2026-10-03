package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.CommentRepository;

import java.util.Optional;

/**
 * Hide or reveal a comment — a moderator-only soft touch. Unlike deletion (which the author may do
 * to their own), hiding is a moderator's judgement over anyone's comment: it stays in the thread
 * as a tombstone rather than vanishing. The boundary decides who is a moderator (from the roles
 * microservice-security reports); this use case enforces that only they may flip the flag.
 */
public class HideComment {

    public enum Status { UPDATED, FORBIDDEN, NO_SUCH_COMMENT }

    /**
     * The outcome, in the shape its twin {@link DeleteComment} already uses: a record over the
     * status enum, so this use case can report one more fact about a hide without every caller
     * having to change. Two use cases that moderate the same comment answered in two different
     * shapes until this existed.
     */
    public record Result(Status status) {}

    private final CommentRepository comments;
    private final CommentModeration moderation;

    public HideComment(CommentRepository comments, CommentModeration moderation) {
        this.comments = comments;
        this.moderation = moderation;
    }

    public Result execute(String memeId, String commentId, boolean hidden, boolean callerIsModerator) {
        if (!callerIsModerator) {
            return new Result(Status.FORBIDDEN);
        }
        // the address is a comment IN a thread, so the thread is part of it: a comment hanging
        // under another meme is not at this address, and confirming a hide against it would have
        // every cache, log and audit record the wrong conversation
        Optional<Comment> comment = comments.find(commentId)
                .filter(found -> found.memeId().equals(memeId));
        if (comment.isEmpty()) {
            return new Result(Status.NO_SUCH_COMMENT);
        }
        try {
            moderation.setHidden(commentId, hidden);
        } catch (CommentModeration.UnknownComment deletedMidHide) {
            // the comment passed the check above but was deleted before the flag landed (the
            // store's foreign key caught it) — same outcome as failing the check: no such comment
            return new Result(Status.NO_SUCH_COMMENT);
        }
        return new Result(Status.UPDATED);
    }
}
