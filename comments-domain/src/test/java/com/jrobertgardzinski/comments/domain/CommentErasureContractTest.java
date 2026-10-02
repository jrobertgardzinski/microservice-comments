package com.jrobertgardzinski.comments.domain;

import com.jrobertgardzinski.identity.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link CommentErasure} promises, asked of EVERY implementation — the JDBC adapter the
 * service runs on and each stand-in used in its place. A stand-in that drifts does not fail; it
 * makes a green suite say something about a service that does not exist.
 *
 * <p>Fresh ids per test method, because one implementation is a database other suites share.
 */
public abstract class CommentErasureContractTest {

    private static final Instant NOON = Instant.parse("2026-09-24T12:00:00Z");

    private final String run = java.util.UUID.randomUUID().toString().substring(0, 8);
    private final UserId alice = UserId.random();
    private final UserId bob = UserId.random();
    private final String first = "c1-" + run;
    private final String second = "c2-" + run;
    private final String third = "c3-" + run;

    protected abstract CommentErasure erasure();

    /** Put an ACTIVE comment in, however this implementation stores one. */
    protected abstract void givenActiveComment(String id, Optional<UserId> authorId);

    private void givenActiveComment(String id, UserId author) {
        givenActiveComment(id, Optional.of(author));
    }

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
    @DisplayName("the id is the key, and a row without one belongs to nobody")
    protected void rows_are_keyed_by_the_authors_id() {
        givenActiveComment(first, Optional.of(alice));
        givenActiveComment(second, Optional.of(alice));
        givenActiveComment(third, Optional.empty());

        assertEquals(2, erasure().activeOf(alice).size(), "two rows, one person");
        assertEquals(List.of(), erasure().activeOf(bob), "and the anonymised row is nobody's");
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
    protected void store_does_not_write_the_author_id() {
        givenActiveComment(first, alice);
        Comment held = theOnly(erasure().activeOf(alice));

        // a stale copy carrying somebody else's identity — which is what the closure hands over a
        // line after it has anonymised the row. If this port wrote the author id back, the
        // anonymisation would be undone by the very next call
        erasure().store(new Comment(held.id(), held.memeId(),
                Optional.of(bob), held.text(), held.markForErasure(NOON).status(), NOON));

        assertEquals(List.of(), erasure().pendingOf(bob),
                "the author id moved: this port wrote a column that is not its own");
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
