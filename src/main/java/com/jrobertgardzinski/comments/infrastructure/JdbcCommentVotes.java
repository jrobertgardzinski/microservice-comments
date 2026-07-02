package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.voting.VoteDirection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Postgres-backed {@link CommentVotes} (H2 in tests): one row per (comment, voter). Cast is
 * delete-then-insert — portable upsert across Postgres and H2.
 */
@Repository
class JdbcCommentVotes implements CommentVotes {

    private final JdbcClient jdbc;

    JdbcCommentVotes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void cast(String commentId, String voter, VoteDirection direction) {
        retract(commentId, voter);
        jdbc.sql("INSERT INTO comment_votes (comment_id, voter, direction) VALUES (?, ?, ?)")
                .params(commentId, voter, direction.name()).update();
    }

    @Override
    public void retract(String commentId, String voter) {
        jdbc.sql("DELETE FROM comment_votes WHERE comment_id = ? AND voter = ?")
                .params(commentId, voter).update();
    }

    @Override
    public Optional<VoteDirection> voteOf(String commentId, String voter) {
        return jdbc.sql("SELECT direction FROM comment_votes WHERE comment_id = ? AND voter = ?")
                .params(commentId, voter)
                .query((rs, n) -> VoteDirection.valueOf(rs.getString("direction")))
                .optional();
    }

    @Override
    public int scoreOf(String commentId) {
        return jdbc.sql("SELECT COALESCE(SUM(CASE direction WHEN 'UP' THEN 1 ELSE -1 END), 0) "
                        + "FROM comment_votes WHERE comment_id = ?")
                .param(commentId).query(Integer.class).single();
    }

    @Override
    public void purgeComment(String commentId) {
        jdbc.sql("DELETE FROM comment_votes WHERE comment_id = ?").param(commentId).update();
    }

    @Override
    public void purgeVoter(String voter) {
        jdbc.sql("DELETE FROM comment_votes WHERE voter = ?").param(voter).update();
    }
}
