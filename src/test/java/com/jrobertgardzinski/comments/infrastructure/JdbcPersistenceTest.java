package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentModeration;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDBC adapters against the real schema (H2 in PostgreSQL mode, the same Flyway migrations as
 * Postgres): the MERGE upserts H2 must speak for the dialect bet to hold, the V3 foreign key's
 * cascade (and its translation to {@link CommentVotes.UnknownComment} on the vote-vs-delete
 * race), the one-shot retry when a concurrent MERGE loses the WHEN NOT MATCHED race, and the
 * batch tally. Every test works its own comment ids — the H2 instance is shared with the MockMvc
 * and Cucumber suites.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
class JdbcPersistenceTest {

    @Autowired
    CommentRepository comments;
    @Autowired
    CommentVotes votes;
    @Autowired
    CommentModeration moderation;
    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("cast is an upsert: two casts by one voter are one row, the last direction wins")
    void cast_twice_is_one_row() {
        String commentId = savedComment();

        votes.cast(commentId, "voter@example.com", VoteDirection.UP);
        votes.cast(commentId, "voter@example.com", VoteDirection.UP);      // same again — no PK clash
        assertEquals(1, voteRows(commentId));
        assertEquals(Optional.of(VoteDirection.UP), votes.voteOf(commentId, "voter@example.com"));

        votes.cast(commentId, "voter@example.com", VoteDirection.DOWN);    // switch — still one row
        assertEquals(1, voteRows(commentId));
        assertEquals(Optional.of(VoteDirection.DOWN), votes.voteOf(commentId, "voter@example.com"));
        assertEquals(-1, votes.scoreOf(commentId));
    }

    @Test
    @DisplayName("hiding twice is an upsert, not a PK clash; revealing removes the row")
    void hide_twice_is_one_row() {
        String commentId = savedComment();

        moderation.setHidden(commentId, true);
        moderation.setHidden(commentId, true);
        assertTrue(moderation.isHidden(commentId));
        assertEquals(1, flagRows(commentId));

        moderation.setHidden(commentId, false);
        assertEquals(0, flagRows(commentId));
    }

    @Test
    @DisplayName("V3: deleting a comment cascades to its votes at the database level")
    void deleting_a_comment_takes_its_votes_by_cascade() {
        String commentId = savedComment();
        votes.cast(commentId, "fan@example.com", VoteDirection.UP);
        votes.cast(commentId, "hater@example.com", VoteDirection.DOWN);
        assertEquals(2, voteRows(commentId));

        // straight to the repository — no application-level purge; the foreign key must do it
        comments.delete(commentId);
        assertEquals(0, voteRows(commentId));
    }

    @Test
    @DisplayName("tallyAll answers a whole page in one batch, through the viewer's eyes")
    void tally_all_matches_the_per_comment_reads() {
        String first = savedComment();
        String second = savedComment();
        String unvoted = savedComment();
        votes.cast(first, "viewer@example.com", VoteDirection.UP);
        votes.cast(first, "fan@example.com", VoteDirection.UP);
        votes.cast(second, "viewer@example.com", VoteDirection.DOWN);

        Map<String, VoteTally> tallies =
                votes.tallyAll(List.of(first, second, unvoted), Optional.of("viewer@example.com"));

        assertEquals(new VoteTally(2, Optional.of(VoteDirection.UP)), tallies.get(first));
        assertEquals(new VoteTally(-1, Optional.of(VoteDirection.DOWN)), tallies.get(second));
        assertEquals(new VoteTally(0, Optional.empty()), tallies.get(unvoted));
    }

    @Test
    @DisplayName("a cast losing the MERGE insert race retries once and lands as WHEN MATCHED")
    void cast_retries_once_after_a_concurrent_merge_insert() {
        String commentId = savedComment();
        // PG15+ MERGE may throw a unique violation when two first-time casts race WHEN NOT
        // MATCHED; H2 cannot be made to lose that race on cue, so the first pass is forced to
        // fail the way the loser would — the retry then runs the real MERGE
        var firstPassLosesTheRace = new JdbcCommentVotes(jdbc) {
            boolean firstPass = true;

            @Override
            void mergeVote(String id, String voter, VoteDirection direction) {
                if (firstPass) {
                    firstPass = false;
                    throw new DuplicateKeyException("simulated concurrent WHEN NOT MATCHED insert");
                }
                super.mergeVote(id, voter, direction);
            }
        };

        firstPassLosesTheRace.cast(commentId, "racer@example.com", VoteDirection.UP);

        assertEquals(1, voteRows(commentId));
        assertEquals(Optional.of(VoteDirection.UP), votes.voteOf(commentId, "racer@example.com"));
    }

    @Test
    @DisplayName("hiding that loses the MERGE insert race retries once and still hides")
    void hide_retries_once_after_a_concurrent_merge_insert() {
        String commentId = savedComment();
        var firstPassLosesTheRace = new JdbcCommentModeration(jdbc) {
            boolean firstPass = true;

            @Override
            void mergeFlag(String id) {
                if (firstPass) {
                    firstPass = false;
                    throw new DuplicateKeyException("simulated concurrent WHEN NOT MATCHED insert");
                }
                super.mergeFlag(id);
            }
        };

        firstPassLosesTheRace.setHidden(commentId, true);

        assertTrue(moderation.isHidden(commentId));
        assertEquals(1, flagRows(commentId));
    }

    @Test
    @DisplayName("casting on a comment deleted mid-vote surfaces as UnknownComment, not a raw FK error")
    void cast_on_a_vanished_comment_reports_unknown_comment() {
        // no such row in comments — the V3 foreign key must refuse, and the adapter must
        // translate that refusal instead of leaking DataIntegrityViolationException (a 500)
        assertThrows(CommentVotes.UnknownComment.class,
                () -> votes.cast(UUID.randomUUID().toString(), "voter@example.com", VoteDirection.UP));
    }

    @Test
    @DisplayName("hiding a comment deleted mid-hide surfaces as UnknownComment, not a raw FK error")
    void hide_on_a_vanished_comment_reports_unknown_comment() {
        // the hide-vs-delete race: no such row in comments, the comment_flags foreign key must
        // refuse, and the adapter must translate instead of leaking a 500
        assertThrows(CommentModeration.UnknownComment.class,
                () -> moderation.setHidden(UUID.randomUUID().toString(), true));
    }

    @Test
    @DisplayName("a duplicate key on the cast RETRY is a bug and stays a raw error, not a fake 404")
    void cast_with_a_permanent_duplicate_key_is_not_dressed_up_as_unknown_comment() {
        String commentId = savedComment();
        // a second DuplicateKeyException in a row cannot be the MERGE race (the winner's row
        // makes the retry pass WHEN MATCHED) — it is a key bug and must surface, not read as
        // "no such comment"
        var alwaysDuplicate = new JdbcCommentVotes(jdbc) {
            @Override
            void mergeVote(String id, String voter, VoteDirection direction) {
                throw new DuplicateKeyException("simulated permanently broken key");
            }
        };

        assertThrows(DuplicateKeyException.class,
                () -> alwaysDuplicate.cast(commentId, "voter@example.com", VoteDirection.UP));
    }

    @Test
    @DisplayName("a duplicate key on the hide RETRY is a bug and stays a raw error, not a fake 404")
    void hide_with_a_permanent_duplicate_key_is_not_dressed_up_as_unknown_comment() {
        String commentId = savedComment();
        // the votes twin above already guards cast(); this is the same asymmetry for moderation —
        // a second DuplicateKeyException in a row cannot be the MERGE race (the winner's row makes
        // the retry pass WHEN MATCHED), so it must surface instead of reading as "no such comment"
        var alwaysDuplicate = new JdbcCommentModeration(jdbc) {
            @Override
            void mergeFlag(String id) {
                throw new DuplicateKeyException("simulated permanently broken key");
            }
        };

        assertThrows(DuplicateKeyException.class,
                () -> alwaysDuplicate.setHidden(commentId, true));
    }

    private String savedComment() {
        String id = UUID.randomUUID().toString();
        // a fresh meme id per comment keeps these rows out of the other suites' threads
        comments.save(new Comment(id, UUID.randomUUID().toString(), "author@example.com", "under test"));
        return id;
    }

    private long voteRows(String commentId) {
        return jdbc.sql("SELECT COUNT(*) FROM comment_votes WHERE comment_id = ?")
                .param(commentId).query(Long.class).single();
    }

    private long flagRows(String commentId) {
        return jdbc.sql("SELECT COUNT(*) FROM comment_flags WHERE comment_id = ?")
                .param(commentId).query(Long.class).single();
    }
}
