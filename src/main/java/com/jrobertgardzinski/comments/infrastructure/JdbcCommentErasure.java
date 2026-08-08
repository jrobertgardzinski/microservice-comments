package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.CommentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The one adapter in this service allowed to SELECT from {@code comments} itself rather than from
 * the {@code active_comments} view — because it is the one that has to see what the view hides.
 * Everything else reads through {@link JdbcCommentRepository}, and {@code CommentReadFilterTest}
 * fails the build if that stops being true.
 *
 * <p>No policy of its own: what a mark means and what a restore leaves behind is decided by
 * {@link Comment}, and merely stored here.
 */
@Repository
class JdbcCommentErasure implements CommentErasure {

    private final JdbcClient jdbc;

    JdbcCommentErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Comment> activeOf(String author) {
        return byAuthor(author, CommentStatus.ACTIVE);
    }

    @Override
    public List<Comment> pendingOf(String author) {
        return byAuthor(author, CommentStatus.PENDING_ERASURE);
    }

    private List<Comment> byAuthor(String author, CommentStatus status) {
        return jdbc.sql("SELECT id, meme_id, author, content, status, marked_for_erasure_at "
                        + "FROM comments WHERE author = ? AND status = ?")
                .params(author, status.name())
                .query(JdbcCommentErasure::toComment).list();
    }

    @Override
    public void store(Comment state) {
        // the two erasure columns and nothing else: the text and the author are not this port's
        // business, and writing them back would let a stale copy overwrite the anonymisation that
        // this very saga performs a line later
        jdbc.sql("UPDATE comments SET status = ?, marked_for_erasure_at = ? WHERE id = ?")
                .params(state.status().name(),
                        state.markedForErasureAt() == null
                                ? null : Timestamp.from(state.markedForErasureAt()),
                        state.id())
                .update();
        // no check on the update count: a comment a moderator deleted between the read and the
        // write is not an error — there is simply nothing left to mark or restore
    }

    @Override
    public List<Comment> pendingSince(Instant cutoff) {
        // the reaper's query in full — a status and an instant, served by
        // idx_comments_pending_erasure. Oldest first, because that is what an operator reading the
        // alarm needs to judge it.
        return jdbc.sql("SELECT id, meme_id, author, content, status, marked_for_erasure_at "
                        + "FROM comments WHERE status = ? AND marked_for_erasure_at < ? "
                        + "ORDER BY marked_for_erasure_at")
                .params(CommentStatus.PENDING_ERASURE.name(), Timestamp.from(cutoff))
                .query(JdbcCommentErasure::toComment).list();
    }

    private static Comment toComment(ResultSet rs, int rowNum) throws SQLException {
        Timestamp marked = rs.getTimestamp("marked_for_erasure_at");
        return new Comment(rs.getString("id"), rs.getString("meme_id"), rs.getString("author"),
                rs.getString("content"), CommentStatus.valueOf(rs.getString("status")),
                marked == null ? null : marked.toInstant());
    }
}
