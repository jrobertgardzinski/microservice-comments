package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentModeration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * Postgres-backed {@link CommentModeration} (H2 in tests): one row per hidden comment. Setting
 * hidden is delete-then-insert — a portable upsert; the foreign key cascades, so a deleted comment
 * loses its flag without anyone here remembering to.
 */
@Repository
class JdbcCommentModeration implements CommentModeration {

    private final JdbcClient jdbc;

    JdbcCommentModeration(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void setHidden(String commentId, boolean hidden) {
        jdbc.sql("DELETE FROM comment_flags WHERE comment_id = ?").params(commentId).update();
        if (hidden) {
            jdbc.sql("INSERT INTO comment_flags (comment_id, hidden) VALUES (?, TRUE)")
                    .params(commentId).update();
        }
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
