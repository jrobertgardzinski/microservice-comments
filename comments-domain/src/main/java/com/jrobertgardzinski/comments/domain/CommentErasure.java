package com.jrobertgardzinski.comments.domain;

import com.jrobertgardzinski.identity.UserId;

import java.time.Instant;
import java.util.List;

/**
 * The saga's own view of the comments table, keyed by the author's identity: the two halves of one
 * author's comments, the one write the saga makes, a thread with its marked rows included (the
 * cascade's read), and the backlog the reaper watches. Everything else reads the view.
 */
public interface CommentErasure {

    List<Comment> activeOf(UserId author);

    List<Comment> pendingOf(UserId author);

    /** Writes the erasure state of one comment — status and instant — and nothing else. */
    void store(Comment state);

    /** Every comment under a meme, marked ones included: the thread cascade must reach them all. */
    List<Comment> allUnder(String memeId);

    /** Marked strictly before the cutoff, oldest first: the reaper's backlog. */
    List<Comment> pendingSince(Instant cutoff);
}
