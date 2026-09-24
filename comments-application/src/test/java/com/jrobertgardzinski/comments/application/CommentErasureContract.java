package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link CommentErasure} promises, stated once and asked of EVERY implementation — the JDBC
 * adapter the service runs on, and each stand-in a test suite uses in its place.
 *
 * <p>A stand-in that drifts from the adapter does not fail: it makes a green suite say something
 * about a service that does not exist. This estate met that twice in one afternoon. A hand-built
 * confirmation lost a field, and three of the stand-ins written that day answered
 * {@link CommentErasure#pendingSince} inclusively where all three real adapters ask
 * {@code marked_for_erasure_at < ?} — one row's difference, invisible, and nothing would ever have
 * reported it. A contract subclassed by both sides is the cheapest thing that does.
 *
 * <p>Two seams, both deliberately narrow: how this implementation is reached, and how a comment comes
 * to exist in it. Everything else is asked through the port, because a seam that reads the rows
 * some other way could lie in the same breath as the thing it is checking.
 */
public abstract class CommentErasureContract {

    private static final Instant NOON = Instant.parse("2026-09-24T12:00:00Z");

    /**
     * Fresh names per test method, because one of the implementations is a real database that
     * other suites share — and this estate has already been bitten by a shared H2 plus surefire's
     * default ordering. JUnit builds a new instance per method, so nothing here can meet a row
     * another test left behind.
     */
    private final String run = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final String alice = "alice+" + run + "@example.com";
    private final String bob = "bob+" + run + "@example.com";
    private final String first = "c1-" + run;
    private final String second = "c2-" + run;
    private final String third = "c3-" + run;

    protected abstract CommentErasure erasure();

    /** Put an ACTIVE comment in, however this implementation stores one. */
    protected abstract void givenActiveComment(String id, String author);

    private Comment theOnly(List<Comment> found) {
        assertEquals(1, found.size(), "expected exactly one comment, got " + found);
        return found.get(0);
    }

    @Test
    @DisplayName("active and pending are the two halves of one author's comments")
    protected void active_and_pending_split_by_status() {
        givenActiveComment(first, alice);
        givenActiveComment(second, alice);
        givenActiveComment(third, bob);

        assertEquals(2, erasure().activeOf(alice).size());
        assertEquals(List.of(), erasure().pendingOf(alice));

        erasure().store(theOnly(erasure().activeOf(bob)).markForErasure(NOON));

        assertEquals(List.of(), erasure().activeOf(bob));
        assertEquals(NOON, theOnly(erasure().pendingOf(bob)).markedForErasureAt());
    }

    @Test
    @DisplayName("a restored comment is active again and carries no mark")
    protected void restoring_puts_it_back() {
        givenActiveComment(first, alice);
        erasure().store(theOnly(erasure().activeOf(alice)).markForErasure(NOON));

        erasure().store(theOnly(erasure().pendingOf(alice)).restore());

        Comment back = theOnly(erasure().activeOf(alice));
        assertEquals(null, back.markedForErasureAt(), "a restored comment owes nobody an instant");
        assertEquals(List.of(), erasure().pendingOf(alice));
    }

    @Test
    @DisplayName("store writes the ERASURE state and nothing else — the author is not this port's business")
    protected void store_does_not_write_the_author() {
        givenActiveComment(first, alice);
        Comment held = theOnly(erasure().activeOf(alice));

        // a stale copy carrying somebody else's name — which is what the closure hands over a line
        // after it has anonymised the row. If this port wrote the author back, the anonymisation
        // would be undone by the very next call, and the leaver's name would return to the thread
        erasure().store(new Comment(held.id(), held.memeId(), "somebody.else+" + run + "@example.com",
                held.text(), held.markForErasure(NOON).status(), NOON));

        assertEquals(List.of(), erasure().pendingOf("somebody.else+" + run + "@example.com"),
                "the author moved: this port wrote a column that is not its own");
        assertEquals(first, theOnly(erasure().pendingOf(alice)).id());
    }

    @Test
    @DisplayName("pendingSince is STRICTLY before the cutoff — the adapters ask `marked_for_erasure_at < ?`")
    protected void pending_since_excludes_the_cutoff_itself() {
        givenActiveComment(first, alice);
        erasure().store(theOnly(erasure().activeOf(alice)).markForErasure(NOON));

        assertEquals(List.of(), erasure().pendingSince(NOON),
                "a comment marked AT the cutoff is not yet overdue");
        assertTrue(erasure().pendingSince(NOON.plusSeconds(1)).stream()
                        .anyMatch(comment -> comment.id().equals(first)),
                "a comment marked before the cutoff is overdue");
    }
}
