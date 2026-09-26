package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import com.jrobertgardzinski.identity.UserId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * <p>Three of its callers are the saga's steps: the mark, its compensation, and the erasure the
 * orchestrator's closure command triggers. The fourth is the MEME_DELETED cascade, which reads a
 * marked comment not to serve it but to destroy it with the rest of its thread — and to say so.
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

    /** The same two halves keyed by identity: rows whose author_id is this one. */
    List<Comment> activeOf(UserId author);

    List<Comment> pendingOf(UserId author);

    /**
     * The leaver's active rows during the dual period: by id when the closure carries one, plus
     * the rows under their address that have no id yet (the backfill has not reached them). A row
     * with another id under the same address is somebody else's.
     */
    default List<Comment> activeOf(String author, Optional<UserId> authorId) {
        return ofLeaver(author, authorId, activeOf(author), authorId.map(this::activeOf));
    }

    default List<Comment> pendingOf(String author, Optional<UserId> authorId) {
        return ofLeaver(author, authorId, pendingOf(author), authorId.map(this::pendingOf));
    }

    private static List<Comment> ofLeaver(String author, Optional<UserId> authorId,
                                          List<Comment> byAddress, Optional<List<Comment>> byId) {
        if (byId.isEmpty()) {
            return byAddress;
        }
        List<Comment> rows = new ArrayList<>(byId.get());
        for (Comment row : byAddress) {
            if (row.authorId().isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Persist the erasure state the aggregate computed — {@code status} and
     * {@code markedForErasureAt}, nothing else on the row. The decision was made by
     * {@link Comment#markForErasure(Instant)} / {@link Comment#restore()}; all that happens here is
     * storing it. A row that no longer exists is not an error: a moderator's delete races every
     * saga.
     */
    void store(Comment state);

    /**
     * Everything hanging under one meme, whatever its status — the thread a MEME_DELETED cascade
     * destroys. A marked comment goes with it (the mark reserved it for a saga, and the meme it
     * belonged to is gone, so there is nothing left to restore it to), which is precisely why the
     * cascade has to see it: what it destroys it must also be able to name to the services that
     * hold a reference to it.
     */
    List<Comment> allUnder(String memeId);

    /**
     * Every comment marked before {@code cutoff} and still not erased — the reaper's query, and the
     * whole structure this feature has: a status and an instant, both on the row. Used to WATCH the
     * backlog, never to erase: nothing here deletes content because time passed.
     */
    List<Comment> pendingSince(Instant cutoff);
}
