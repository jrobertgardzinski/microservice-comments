-- Votes belong to their comment the way flags already do (V2): a foreign key with ON DELETE
-- CASCADE, so a deleted comment takes its ballots along at the database level — the application's
-- purge-then-delete stays, but the invariant no longer depends on it.
-- Dev databases predate the constraint and may hold votes whose comment is already gone; those
-- rows would make ADD CONSTRAINT fail, so they are swept first (they were unreachable anyway).
DELETE FROM comment_votes WHERE comment_id NOT IN (SELECT id FROM comments);

ALTER TABLE comment_votes
    ADD CONSTRAINT fk_comment_votes_comment
    FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE;
