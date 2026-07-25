package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Postgres-backed {@link CommentVotes} (H2 in tests): one row per (comment, voter). Cast is a
 * standard-SQL {@code MERGE} upsert — one statement instead of delete+insert. MERGE, not
 * Postgres' {@code ON CONFLICT}: H2 2.3's PostgreSQL mode only accepts a targetless
 * {@code ON CONFLICT DO NOTHING} (verified 2026-07-25), while standard MERGE is spoken by both H2
 * and Postgres 15+ (the stack runs 16). Unlike ON CONFLICT, MERGE offers no atomicity guarantee
 * against a concurrent insert: two first-time casts by the same voter can both take WHEN NOT
 * MATCHED and the loser hits the primary key — the race is not gone, it is reduced to one rare
 * retry (the second pass lands in WHEN MATCHED). Proven against the real schema by
 * JdbcPersistenceTest.
 */
@Repository
class JdbcCommentVotes implements CommentVotes {

    private final JdbcClient jdbc;

    JdbcCommentVotes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void cast(String commentId, String voter, VoteDirection direction) {
        try {
            mergeVote(commentId, voter, direction);
        } catch (DuplicateKeyException concurrentFirstCast) {
            // PG15+ MERGE: a concurrent WHEN NOT MATCHED won the insert — one retry hits
            // WHEN MATCHED and records this direction as the later of the two casts
            try {
                mergeVote(commentId, voter, direction);
            } catch (DataIntegrityViolationException commentGone) {
                throw new UnknownComment(commentId, commentGone);
            }
        } catch (DataIntegrityViolationException commentGone) {
            // not a duplicate key, so the V3 foreign key: the comment was deleted between the
            // caller's existence check and this vote — report "no such comment", not a 500
            throw new UnknownComment(commentId, commentGone);
        }
    }

    /** The MERGE itself — a seam the retry test overrides to force the first pass to fail. */
    void mergeVote(String commentId, String voter, VoteDirection direction) {
        jdbc.sql("MERGE INTO comment_votes USING (VALUES (?, ?, ?)) "
                        + "AS src(comment_id, voter, direction) "
                        + "ON comment_votes.comment_id = src.comment_id "
                        + "AND comment_votes.voter = src.voter "
                        + "WHEN MATCHED THEN UPDATE SET direction = src.direction "
                        + "WHEN NOT MATCHED THEN INSERT (comment_id, voter, direction) "
                        + "VALUES (src.comment_id, src.voter, src.direction)")
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
    public Map<String, VoteTally> tallyAll(List<String> commentIds, Optional<String> viewer) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        // two queries for the whole page (scores, then the viewer's own choices) instead of two
        // per comment — the listing's N+1 collapsed into a constant number of round-trips
        Map<String, Integer> scores = new HashMap<>();
        jdbc.sql("SELECT comment_id, SUM(CASE direction WHEN 'UP' THEN 1 ELSE -1 END) AS score "
                        + "FROM comment_votes WHERE comment_id IN (:ids) GROUP BY comment_id")
                .param("ids", commentIds)
                .query((rs, n) -> scores.put(rs.getString("comment_id"), rs.getInt("score")))
                .list();
        Map<String, VoteDirection> choices = new HashMap<>();
        viewer.ifPresent(voter -> jdbc
                .sql("SELECT comment_id, direction FROM comment_votes "
                        + "WHERE voter = :voter AND comment_id IN (:ids)")
                .param("voter", voter).param("ids", commentIds)
                .query((rs, n) -> choices.put(rs.getString("comment_id"),
                        VoteDirection.valueOf(rs.getString("direction"))))
                .list());
        Map<String, VoteTally> tallies = new HashMap<>();
        for (String id : commentIds) {
            tallies.put(id, new VoteTally(scores.getOrDefault(id, 0),
                    Optional.ofNullable(choices.get(id))));
        }
        return tallies;
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
