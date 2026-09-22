package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.util.Optional;

/**
 * Remove one comment and its votes, subject to authority: the author may remove their own, a
 * moderator may remove anyone's. The boundary decides who is a moderator (from the roles
 * microservice-security reports); this use case enforces the rule and does the teardown.
 */
public class DeleteComment {

    public enum Status { DELETED, FORBIDDEN, NO_SUCH_COMMENT }

    /** {@code byModerator} is true only when a moderator removed someone else's comment. */
    public record Result(Status status, boolean byModerator) {}

    private final CommentRepository comments;
    private final CommentVotes votes;

    public DeleteComment(CommentRepository comments, CommentVotes votes) {
        this.comments = comments;
        this.votes = votes;
    }

    public Result execute(String memeId, String commentId, String caller, boolean callerIsModerator) {
        // the address is a comment IN a thread, so the thread is part of it: a comment hanging
        // under another meme is not at this address, and confirming a deletion against it would
        // have every cache, log and audit record the wrong conversation
        Optional<Comment> comment = comments.find(commentId)
                .filter(found -> found.memeId().equals(memeId));
        if (comment.isEmpty()) {
            return new Result(Status.NO_SUCH_COMMENT, false);
        }
        boolean isAuthor = comment.get().author().equals(caller);
        if (!isAuthor && !callerIsModerator) {
            return new Result(Status.FORBIDDEN, false);
        }
        votes.purgeComment(commentId);
        comments.delete(commentId);
        return new Result(Status.DELETED, !isAuthor);
    }
}
