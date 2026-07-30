package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentModeration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * Postgres-backed {@link CommentModeration} (H2 in tests): one row per hidden comment. Hiding is
 * a standard-SQL {@code MERGE} upsert — one statement instead of delete+insert; revealing stays a
 * delete, keeping the "a row means hidden" invariant. MERGE, not Postgres' {@code ON CONFLICT}:
 * H2 2.3's PostgreSQL mode only accepts a targetless {@code ON CONFLICT DO NOTHING}, while
 * standard MERGE is spoken by both H2 and Postgres 15+ (the stack runs 16 — and the MERGE
 * dialect is under test on the real PostgreSQL 18 in PostgresDialectTest, not only on H2).
 * Unlike ON CONFLICT, MERGE gives no atomicity against a concurrent insert: two moderators hiding
 * the same comment can both take WHEN NOT MATCHED and the loser hits the primary key — the race
 * is not gone, it is reduced to one rare retry (the second pass lands in WHEN MATCHED). The
 * foreign key cascades, so a deleted comment loses its flag without anyone here remembering to.
 */
@Repository
class JdbcCommentModeration implements CommentModeration {

    private final JdbcClient jdbc;

    JdbcCommentModeration(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void setHidden(String commentId, boolean hidden) {
        if (hidden) {
            try {
                mergeFlag(commentId);
            } catch (DuplicateKeyException concurrentHide) {
                // PG15+ MERGE: a concurrent WHEN NOT MATCHED won the insert — one retry hits
                // WHEN MATCHED, and both moderators wanted the same end state anyway
                try {
                    mergeFlag(commentId);
                } catch (DuplicateKeyException stillDuplicate) {
                    // asymmetry on purpose: a duplicate on the RETRY cannot be the race again
                    // (the winner's row makes this pass WHEN MATCHED) — it is a key bug, and
                    // dressing it up as "no such comment" would hide it; let it be a 500
                    throw stillDuplicate;
                } catch (DataIntegrityViolationException commentGone) {
                    throw new UnknownComment(commentId, commentGone);
                }
            } catch (DataIntegrityViolationException commentGone) {
                // not a duplicate key, so the foreign key: the comment was deleted between the
                // caller's existence check and this hide — report "no such comment", not a 500
                throw new UnknownComment(commentId, commentGone);
            }
        } else {
            jdbc.sql("DELETE FROM comment_flags WHERE comment_id = ?").params(commentId).update();
        }
    }

    /** The MERGE itself — a seam the retry test overrides to force the first pass to fail. */
    void mergeFlag(String commentId) {
        jdbc.sql("MERGE INTO comment_flags USING (VALUES (?)) AS src(comment_id) "
                        + "ON comment_flags.comment_id = src.comment_id "
                        + "WHEN MATCHED THEN UPDATE SET hidden = TRUE "
                        + "WHEN NOT MATCHED THEN INSERT (comment_id, hidden) "
                        + "VALUES (src.comment_id, TRUE)")
                .params(commentId).update();
    }

    @Override
    public boolean isHidden(String commentId) {
        return jdbc.sql("SELECT COUNT(*) FROM comment_flags WHERE comment_id = ? AND hidden")
                .params(commentId).query(Long.class).single() > 0;
    }

    @Override
    public Set<String> hiddenIn(String memeId) {
        // .list() materialises and closes the connection; .stream() would keep the cursor open
        return Set.copyOf(jdbc.sql("SELECT f.comment_id FROM comment_flags f "
                        + "JOIN comments c ON c.id = f.comment_id WHERE c.meme_id = ? AND f.hidden")
                .params(memeId).query(String.class).list());
    }
}
