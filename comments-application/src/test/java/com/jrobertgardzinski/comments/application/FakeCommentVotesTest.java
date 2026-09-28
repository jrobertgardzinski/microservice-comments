package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.VoteDirection;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the stand-in promises the account closure — the same three {@code PurgeUserComments} leans
 * on when it retracts the leaver's ballots and only then reads each comment's score.
 */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
class FakeCommentVotesTest {

    private final FakeCommentVotes votes = new FakeCommentVotes();

    @Test
    @DisplayName("the score is the ballots, and nothing else counts them")
    void score_is_the_ballots() {
        votes.cast("c1", "alice", VoteDirection.UP);
        votes.cast("c1", "bob", VoteDirection.UP);
        votes.cast("c1", "carol", VoteDirection.DOWN);

        assertEquals(1, votes.scoreOf("c1"));
        assertEquals(0, votes.scoreOf("never-voted-on"));
    }

    @Test
    @DisplayName("a purged comment keeps none of its ballots")
    void purging_a_comment_forgets_its_ballots() {
        votes.cast("c1", "alice", VoteDirection.UP);

        votes.purgeComment("c1");

        assertEquals(0, votes.scoreOf("c1"));
        assertTrue(votes.voteOf("c1", "alice").isEmpty());
    }

    @Test
    @DisplayName("a purged voter leaves every comment at once, and the scores say so")
    void purging_a_voter_moves_every_score() {
        votes.cast("c1", "leaver", VoteDirection.UP);
        votes.cast("c2", "leaver", VoteDirection.UP);
        votes.cast("c2", "somebody", VoteDirection.UP);

        votes.purgeVoter("leaver");

        assertEquals(0, votes.scoreOf("c1"));
        assertEquals(1, votes.scoreOf("c2"));
    }
}
