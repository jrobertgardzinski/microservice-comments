package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.time.Instant;
import java.util.List;

/**
 * The erasure-aware side of comment storage: the only port in this service that can see a comment
 * which is not {@link com.jrobertgardzinski.comments.domain.CommentStatus#ACTIVE}.
 *
 * <p><strong>Why a second port rather than four more methods on {@link CommentRepository}.</strong>
 * {@link CommentRepository} is the thread's world, and its promise has to be absolute: nothing it
 * returns is pending erasure, because its adapter reads the {@code active_comments} view and never
 * the table. A port that could answer both questions would turn that promise into a matter of which
 * method a caller happened to pick — and would leave {@code CommentReadFilterTest} nothing to
 * enforce.
 *
 * <p>Its three callers are the saga's steps: the mark, its compensation, and the erasure the
 * orchestrator's closure command triggers.
 */
public interface CommentErasure {

    /** The leaver's comments still in their threads — what a mark has left to do. */
    List<Comment> activeOf(String author);

    /**
     * The leaver's comments a running saga has already reserved — what a compensation restores and
     * what the erasure destroys. Both act on THIS set, never on "everything by that author", so a
     * comment written after the mark belongs to no saga and is nobody's to erase.
     */
    List<Comment> pendingOf(String author);

    /**
     * Persist the erasure state the aggregate computed — {@code status} and
     * {@code markedForErasureAt}, nothing else on the row. The decision was made by
     * {@link Comment#markForErasure(Instant)} / {@link Comment#restore()}; all that happens here is
     * storing it. A row that no longer exists is not an error: a moderator's delete races every
     * saga.
     */
    void store(Comment state);

    /**
     * Every comment marked before {@code cutoff} and still not erased — the reaper's query, and the
     * whole structure this feature has: a status and an instant, both on the row. Used to WATCH the
     * backlog, never to erase: nothing here deletes content because time passed.
     */
    List<Comment> pendingSince(Instant cutoff);
}
