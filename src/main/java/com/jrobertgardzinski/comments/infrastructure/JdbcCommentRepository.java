package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.domain.Comment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed {@link CommentRepository} (H2 in tests); schema in V1.
 *
 * <p><strong>Every SELECT below names {@code active_comments}, never {@code comments}</strong> —
 * the view V6 created, whose whole body is {@code WHERE status = 'ACTIVE'}. That is the single
 * place this service's account-deletion filter is written down: a comment a running saga has marked
 * is absent from the thread, from its paging, from its count and from the single-comment lookups
 * that the vote and moderation paths gate on, without any of those queries knowing that erasure
 * exists. Writes still name the table — and {@code CommentReadFilterTest} enforces exactly that
 * split, so the next SELECT written here cannot quietly become the leak.
 */
@Repository
class JdbcCommentRepository implements CommentRepository {

    private final JdbcClient jdbc;

    JdbcCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Comment comment) {
        jdbc.sql("INSERT INTO comments (id, meme_id, author, content, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(comment.id(), comment.memeId(), comment.author(), comment.text(),
                        java.sql.Timestamp.from(Instant.now()))
                .update();
    }

    @Override
    public List<Comment> findByMeme(String memeId) {
        return jdbc.sql("SELECT id, meme_id, author, content FROM active_comments WHERE meme_id = ? ORDER BY created_at")
                .param(memeId).query(this::toComment).list();
    }

    @Override
    public List<Comment> findByMeme(String memeId, int offset, int limit) {
        return jdbc.sql("SELECT id, meme_id, author, content FROM active_comments WHERE meme_id = ? "
                        + "ORDER BY created_at LIMIT ? OFFSET ?")
                .params(memeId, limit, offset).query(this::toComment).list();
    }

    @Override
    public int countByMeme(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM active_comments WHERE meme_id = ?")
                .param(memeId).query((rs, n) -> rs.getInt(1)).single();
    }

    @Override
    public Optional<Comment> find(String commentId) {
        return jdbc.sql("SELECT id, meme_id, author, content FROM active_comments WHERE id = ?")
                .param(commentId).query(this::toComment).optional();
    }

    @Override
    public List<Comment> findByAuthor(String author) {
        return jdbc.sql("SELECT id, meme_id, author, content FROM active_comments WHERE author = ?")
                .param(author).query(this::toComment).list();
    }

    @Override
    public void delete(String commentId) {
        jdbc.sql("DELETE FROM comments WHERE id = ?").param(commentId).update();
    }

    @Override
    public void deleteByMeme(String memeId) {
        jdbc.sql("DELETE FROM comments WHERE meme_id = ?").param(memeId).update();
    }

    @Override
    public void reassignAuthor(String commentId, String newAuthor) {
        jdbc.sql("UPDATE comments SET author = ? WHERE id = ?").params(newAuthor, commentId).update();
    }

    private Comment toComment(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Comment(rs.getString("id"), rs.getString("meme_id"),
                rs.getString("author"), rs.getString("content"));
    }
}
