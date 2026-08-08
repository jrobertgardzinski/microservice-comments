package com.jrobertgardzinski.comments.domain;

/**
 * Whether a comment is part of its thread or is waiting to be erased — the comments service's half
 * of the same idea the meme service spells out in its own {@code MemeStatus}, and deliberately its
 * own type rather than a shared one: the two services share a saga, not a database, and a status
 * that travelled between them would be a schema they would have to release together.
 *
 * <p>The second value is what makes the account-deletion saga compensatable. Destroying a comment
 * the moment the purge command arrives leaves the orchestrator with nothing to undo when a LATER
 * participant fails, so a comment leaves the thread before it leaves the table, and the step that
 * removes it from the thread can be taken back.
 *
 * <p><strong>PENDING_ERASURE, not TO_DELETE</strong> — the GDPR's own word (article 17, the right
 * to erasure), so the enum, the saga's commands and the ADR all say the same thing as the
 * regulation they exist for.
 *
 * @see Comment#markForErasure(java.time.Instant)
 * @see Comment#restore()
 */
public enum CommentStatus {

    /** In the thread: the only status any public read may ever return. */
    ACTIVE,

    /**
     * Marked by a running account-deletion saga: invisible to every public read, still stored,
     * still restorable by the saga's compensation. Only the orchestrator's closure command turns
     * this into a real delete — never the passage of time.
     */
    PENDING_ERASURE
}
