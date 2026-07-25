package com.jrobertgardzinski.comments.application;

import java.util.Set;

/**
 * Port for a moderator's soft judgement over comments: today one axis, HIDDEN. A hidden comment
 * is kept, not gone — it shows as a tombstone in the thread (no text for readers), the gentler
 * counterpart to deletion. Kept separate from the comment row (like the meme service's NSFW flag)
 * so the judgement is the community's, not the author's, and a deleted comment sheds it by cascade.
 */
public interface CommentModeration {

    /**
     * Hiding a comment that no longer exists — the hide-vs-delete race, surfaced by the store
     * (the comment_flags foreign key) after the caller's existence check already passed. Callers
     * treat it exactly like "comment not found" on the check itself.
     */
    class UnknownComment extends RuntimeException {
        public UnknownComment(String commentId, Throwable cause) {
            super("no comment " + commentId + " to hide", cause);
        }
    }

    void setHidden(String commentId, boolean hidden);

    boolean isHidden(String commentId);

    /** The ids of the hidden comments among a meme's thread — read once per listing. */
    Set<String> hiddenIn(String memeId);
}
