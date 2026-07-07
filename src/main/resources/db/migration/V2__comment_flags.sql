-- A moderator's soft touch: a hidden comment stays in the thread as a tombstone (no text for
-- readers), the gentler counterpart to deletion. One row per hidden comment; the foreign key
-- cascades, so deleting a comment (moderation or the account purge) takes its flag along.
CREATE TABLE comment_flags (
    comment_id VARCHAR(36) PRIMARY KEY REFERENCES comments(id) ON DELETE CASCADE,
    hidden     BOOLEAN NOT NULL
);
