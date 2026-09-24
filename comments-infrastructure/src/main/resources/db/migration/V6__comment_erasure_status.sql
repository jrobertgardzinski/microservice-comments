-- The comments half of a compensatable offboarding saga — the same two columns and the same single
-- filtering view as the meme service's V10, for the same reason.
--
-- WHAT WAS WRONG. A purge command deleted the leaver's comments (or anonymised them, by the rule)
-- the moment it arrived. If a LATER participant of the same saga then failed, the orchestrator
-- announced the failure, security unlocked the account — and the conversation the leaver had taken
-- part in was already gone. An irreversible step in the middle of a saga makes the saga
-- uncompensatable no matter what the orchestrator does.
--
-- HOW THIS FIXES IT. The command MARKS the comments (status = PENDING_ERASURE), which takes them
-- out of every thread because every read goes through active_comments; the compensation flips them
-- back; the orchestrator's closure command is the only thing that deletes anything.
--
-- Deliberately NOT a second table listing comments to delete: the fact belongs to the comment, and
-- a queue in another table is one more thing that can disagree with the row it points at. ADR 0007.

ALTER TABLE comments ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
-- when a running saga reserved this comment — the age the erasure backlog is watched by, never a
-- deadline after which anything is deleted
ALTER TABLE comments ADD COLUMN marked_for_erasure_at TIMESTAMP;

ALTER TABLE comments ADD CONSTRAINT ck_comments_status
    CHECK (status IN ('ACTIVE', 'PENDING_ERASURE'));
ALTER TABLE comments ADD CONSTRAINT ck_comments_erasure_mark
    CHECK ((status = 'PENDING_ERASURE') = (marked_for_erasure_at IS NOT NULL));

-- THE one place the ACTIVE filter is written down: the thread listing, its page, its count, the
-- single-comment lookup the vote and the moderation paths gate on, and the moderation join all
-- select from here. A query that forgets the filter has to name the base table to do it, and
-- CommentReadFilterTest fails the build over exactly that.
CREATE VIEW active_comments AS
    SELECT id, meme_id, author, content, created_at
    FROM comments
    WHERE status = 'ACTIVE';

-- the reaper's data structure, in one index instead of in a queue table
CREATE INDEX idx_comments_pending_erasure ON comments (status, marked_for_erasure_at);
