package com.jrobertgardzinski.comments.domain.core;

import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link CommentVotes} promises, asked of EVERY implementation — the JDBC adapter the service
 * runs on and each stand-in used in its place. A stand-in that drifts does not fail; it makes a
 * green suite say something about a service that does not exist, which is worse than a mock,
 * because it is convincingly wrong.
 *
 * <p>These are not arbitrary promises: they are what {@code PurgeUserComments} leans on when it
 * retracts the leaver's ballots and only then reads each comment's score, and what a listing leans
 * on when it asks for a whole page of tallies at once. {@code KEEP_POPULAR_ANONYMIZED} — the one
 * {@code PurgeRule} that reads a score — had no scenario anywhere in the portal while the specs ran
 * on {@code mock(CommentVotes.class)}, whose {@code scoreOf} answers 0 for ever; the stand-in is
 * what made that rule statable, so the stand-in's arithmetic is now load-bearing.
 *
 * <p>Fresh ids AND fresh voter names per test method, because one implementation is a database
 * other suites share: {@code purgeVoter} is keyed by the voter alone, across every comment in the
 * store.
 *
 * <p><b>What this contract deliberately does NOT state.</b> Casting on a comment that is gone: the
 * V3 foreign key refuses the write and the adapter turns that into
 * {@link CommentVotes.UnknownComment}, while an in-memory stand-in has no comment store to ask and
 * records the ballot. That is the store's own guard on the vote-vs-delete race, pinned where it
 * lives — {@code JdbcPersistenceTest} on H2 and {@code PostgresDialectTest} on the production
 * dialect. The contract only requires that the implementation be TOLD which comments exist, through
 * {@link #givenVotableComment}.
 */
public abstract class CommentVotesContractTest {

    private final String run = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final String first = "c1-" + run;
    private final String second = "c2-" + run;
    private final String third = "c3-" + run;
    private final String alice = "alice-" + run;
    private final String bob = "bob-" + run;
    private final String carol = "carol-" + run;

    protected abstract CommentVotes votes();

    /** Put a comment there to vote on, however this implementation records one. */
    protected abstract void givenVotableComment(String commentId);

    @Test
    @DisplayName("the score is the ballots, and nothing else counts them")
    protected void the_score_is_the_ballots() {
        givenVotableComment(first);

        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(first, bob, VoteDirection.UP);
        votes().cast(first, carol, VoteDirection.DOWN);

        assertEquals(1, votes().scoreOf(first));
        assertEquals(0, votes().scoreOf(second), "a comment with no ballots scores 0, not nothing");
    }

    @Test
    @DisplayName("one voter has one ballot, and a second cast replaces the first")
    protected void a_second_cast_replaces_the_first() {
        givenVotableComment(first);
        votes().cast(first, alice, VoteDirection.UP);

        votes().cast(first, alice, VoteDirection.DOWN);

        // the arrows toggle, so this is the ordinary path and not a corner: an implementation that
        // appended instead of replacing would let one person carry a comment
        assertEquals(Optional.of(VoteDirection.DOWN), votes().voteOf(first, alice));
        assertEquals(-1, votes().scoreOf(first));
    }

    @Test
    @DisplayName("retracting takes that voter's ballot and nobody else's")
    protected void retracting_takes_only_that_voters_ballot() {
        givenVotableComment(first);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(first, bob, VoteDirection.UP);

        votes().retract(first, alice);

        assertEquals(Optional.empty(), votes().voteOf(first, alice));
        assertEquals(1, votes().scoreOf(first));

        votes().retract(first, alice);   // a retraction nobody cast is a no-op, not a failure
        assertEquals(1, votes().scoreOf(first));
    }

    @Test
    @DisplayName("a purged comment keeps none of its ballots")
    protected void purging_a_comment_forgets_its_ballots() {
        givenVotableComment(first);
        votes().cast(first, alice, VoteDirection.UP);

        votes().purgeComment(first);

        assertEquals(0, votes().scoreOf(first));
        assertTrue(votes().voteOf(first, alice).isEmpty());
    }

    @Test
    @DisplayName("a purged voter leaves every comment at once, and the scores say so")
    protected void purging_a_voter_moves_every_score() {
        givenVotableComment(first);
        givenVotableComment(second);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(second, alice, VoteDirection.UP);
        votes().cast(second, bob, VoteDirection.UP);

        votes().purgeVoter(alice);

        // the point of the ordering in PurgeUserComments: a leaver cannot buy his own comment's
        // survival with a ballot that is leaving with him
        assertEquals(0, votes().scoreOf(first));
        assertEquals(1, votes().scoreOf(second));
        assertTrue(votes().voteOf(second, alice).isEmpty());
    }

    @Test
    @DisplayName("a page of tallies agrees with the same tallies read one by one")
    protected void a_page_of_tallies_agrees_with_the_reads_one_by_one() {
        givenVotableComment(first);
        givenVotableComment(second);
        givenVotableComment(third);
        votes().cast(first, alice, VoteDirection.UP);
        votes().cast(first, bob, VoteDirection.DOWN);
        votes().cast(third, bob, VoteDirection.DOWN);

        Map<String, VoteTally> page = votes().tallyAll(List.of(first, second, third),
                Optional.of(alice));

        // the listing's whole page, through one viewer's eyes — and every id asked about is
        // answered, including the one nobody has voted on
        assertEquals(new VoteTally(0, Optional.of(VoteDirection.UP)), page.get(first));
        assertEquals(new VoteTally(0, Optional.empty()), page.get(second));
        assertEquals(new VoteTally(-1, Optional.empty()), page.get(third),
                "somebody else's ballot is in the score, and is not this viewer's choice");
    }

    @Test
    @DisplayName("a page read by nobody in particular carries scores and no choices")
    protected void a_page_has_no_choices_without_a_viewer() {
        givenVotableComment(first);
        votes().cast(first, alice, VoteDirection.UP);

        assertEquals(new VoteTally(1, Optional.empty()),
                votes().tallyAll(List.of(first), Optional.empty()).get(first));
        assertEquals(Map.of(), votes().tallyAll(List.of(), Optional.of(alice)),
                "an empty question needs no answer, and no round trip");
    }
}
