-- The author's stable identity beside the address. Nullable for now: rows written before this
-- migration carry only the address and are filled in by the backfill; new comments write both.
-- The address column goes when every row and every reader has moved to the id.
ALTER TABLE comments ADD COLUMN author_id UUID;
CREATE INDEX idx_comments_author_id ON comments (author_id);

DROP VIEW active_comments;
CREATE VIEW active_comments AS
    SELECT id, meme_id, author, author_id, content, created_at
    FROM comments
    WHERE status = 'ACTIVE';
