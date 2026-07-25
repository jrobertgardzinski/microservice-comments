package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.util.Optional;

/**
 * Hide or reveal a comment — a moderator-only soft touch. Unlike deletion (which the author may do
 * to their own), hiding is a moderator's judgement over anyone's comment: it stays in the thread
 * as a tombstone rather than vanishing. The boundary decides who is a moderator (from the roles
 * microservice-security reports); this use case enforces that only they may flip the flag.
 */
public class HideComment {

    public enum Status { UPDATED, FORBIDDEN, NO_SUCH_COMMENT }

    private final CommentRepository comments;
    private final CommentModeration moderation;

    public HideComment(CommentRepository comments, CommentModeration moderation) {
        this.comments = comments;
        this.moderation = moderation;
    }

    public Status execute(String commentId, boolean hidden, boolean callerIsModerator) {
        if (!callerIsModerator) {
            return Status.FORBIDDEN;
        }
        Optional<Comment> comment = comments.find(commentId);
        if (comment.isEmpty()) {
            return Status.NO_SUCH_COMMENT;
        }
        try {
            moderation.setHidden(commentId, hidden);
        } catch (CommentModeration.UnknownComment deletedMidHide) {
            // the comment passed the check above but was deleted before the flag landed (the
            // store's foreign key caught it) — same outcome as failing the check: no such comment
            return Status.NO_SUCH_COMMENT;
        }
        return Status.UPDATED;
    }
}
