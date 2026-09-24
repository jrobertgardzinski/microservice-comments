package com.jrobertgardzinski.comments.domain;

import java.time.Instant;

/**
 * A comment posted on a meme: its id, the meme it belongs to, the author, the text — and whether it
 * is part of the thread at all. Author and text must not be blank, and the text has an upper bound
 * — a comment is a remark, not an essay; null-freedom is the boundary's responsibility (ADR 0001 —
 * no null guards in domain types).
 *
 * <p><strong>The status is the saga's business, and only the saga's.</strong> An account deletion
 * marks a leaver's comments {@link CommentStatus#PENDING_ERASURE}, which takes them out of every
 * public read while leaving the rows intact, so the orchestrator can still change its mind. The two
 * transitions below are the only way that status ever moves — there is no setter, and the
 * repository persists what these methods computed rather than deciding anything itself.
 *
 * <p>Both are IDEMPOTENT, because the commands that trigger them arrive over Kafka and Kafka
 * promises at-least-once. Marking an already-marked comment keeps the FIRST instant: the age of the
 * mark is what the erasure backlog is watched by, and a redelivered command must not make an old
 * obligation look new.
 */
public record Comment(String id, String memeId, String author, String text, CommentStatus status,
                      Instant markedForErasureAt) {

    /** The longest a comment may be. A hard domain rule, not server policy. */
    public static final int MAX_LENGTH = 2000;

    public Comment {
        if (author.isBlank()) {
            throw new IllegalArgumentException("author must not be blank");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("a comment is at most " + MAX_LENGTH + " characters");
        }
        // the mark and its instant exist together or not at all; the schema repeats this as a
        // CHECK constraint, because rows are also written by migrations and by humans at a prompt
        if ((status == CommentStatus.PENDING_ERASURE) != (markedForErasureAt != null)) {
            throw new IllegalArgumentException(
                    "a comment is PENDING_ERASURE exactly when it carries the instant it was marked;"
                            + " got status=" + status + ", markedForErasureAt=" + markedForErasureAt);
        }
    }

    /** A comment in its thread — the shorthand for everything that is not an erasure. */
    public Comment(String id, String memeId, String author, String text) {
        this(id, memeId, author, text, CommentStatus.ACTIVE, null);
    }

    /**
     * The reversible half of an account deletion: out of the thread, still in the table. Idempotent
     * — a redelivered command finds the comment already marked and keeps the original instant.
     */
    public Comment markForErasure(Instant at) {
        return status == CommentStatus.PENDING_ERASURE
                ? this
                : new Comment(id, memeId, author, text, CommentStatus.PENDING_ERASURE, at);
    }

    /**
     * The compensation: back into the thread exactly as before. A no-op on an ACTIVE comment rather
     * than an error — the orchestrator compensating a saga cannot know how far each participant got.
     */
    public Comment restore() {
        return status == CommentStatus.ACTIVE
                ? this
                : new Comment(id, memeId, author, text, CommentStatus.ACTIVE, null);
    }

    /** Whether a running saga has this comment reserved for erasure. */
    public boolean isPendingErasure() {
        return status == CommentStatus.PENDING_ERASURE;
    }
}
