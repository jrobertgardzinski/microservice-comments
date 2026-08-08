package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

/**
 * The compensation: every comment this service reserved for a leaver goes back into its thread,
 * with its author, its text and its votes untouched. This is what the mark bought — a conversation
 * other people took part in is not something a failed saga may quietly shorten.
 *
 * <p><strong>The orchestrator decides, not this service.</strong> No local timeout, no "the closure
 * is late, let us assume it failed": restoring on our own clock would put a leaver's comments back
 * while the saga is still legitimately waiting for a slow sibling — possibly under an account that
 * is by then gone.
 *
 * <p><strong>Idempotent</strong> (workspace ADR 0006), and silent about how much it found: a
 * redelivered command restores nothing because the first one already did, and a command for a saga
 * this service never marked finds nothing at all. The orchestrator compensates every participant it
 * commanded, because it cannot know which of them heard the first command.
 */
public class RestoreUserComments {

    private final CommentErasure erasure;

    public RestoreUserComments(CommentErasure erasure) {
        this.erasure = erasure;
    }

    public void execute(String author) {
        for (Comment comment : erasure.pendingOf(author)) {
            erasure.store(comment.restore());
        }
    }
}
