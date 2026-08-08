package com.jrobertgardzinski.comments.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The twin of microservice-memes' {@code MemeMetadataTest}: the two transitions the compensatable
 * saga rests on, tested where they are DECIDED rather than where they are stored.
 *
 * <p>Worth having in both services rather than in one, because these are the rules the rest of the
 * feature merely carries: the use-case tests run on a fixed clock, so "a redelivery keeps the FIRST
 * instant" cannot be observed there at all, and the invariant is otherwise only stated by a CHECK
 * constraint that no unit test ever reaches.
 */
class CommentErasureStateTest {

    private static final Instant FIRST_DELIVERY = Instant.parse("2026-08-08T10:00:00Z");
    private static final Instant REDELIVERY = FIRST_DELIVERY.plus(Duration.ofHours(1));

    private static Comment inTheThread() {
        return new Comment("c1", "m1", "leaver@example.com", "nice one");
    }

    @Test
    @DisplayName("a fresh comment is ACTIVE and carries no mark")
    void the_shorthand_constructor_is_the_thread_state() {
        Comment comment = inTheThread();

        assertEquals(CommentStatus.ACTIVE, comment.status());
        assertEquals(null, comment.markedForErasureAt());
        assertFalse(comment.isPendingErasure());
    }

    @Test
    @DisplayName("marking reserves the comment, records when, and moves nothing else")
    void mark_sets_the_status_and_the_instant_together() {
        Comment marked = inTheThread().markForErasure(FIRST_DELIVERY);

        assertTrue(marked.isPendingErasure());
        assertEquals(FIRST_DELIVERY, marked.markedForErasureAt());
        assertEquals("nice one", marked.text(), "the words are still there — only hidden");
        assertEquals("leaver@example.com", marked.author(),
                "and so is the authorship: anonymising is the ERASURE's job, not the mark's");
    }

    @Test
    @DisplayName("a redelivered mark keeps the FIRST instant — the backlog measures an age")
    void marking_twice_does_not_rejuvenate_the_obligation() {
        Comment marked = inTheThread().markForErasure(FIRST_DELIVERY);

        Comment again = marked.markForErasure(REDELIVERY);

        assertEquals(FIRST_DELIVERY, again.markedForErasureAt(),
                "otherwise a command redelivered every few minutes would never look old enough to"
                        + " alarm on, and a lost closure would hide behind its own retries");
        assertSame(marked, again);
    }

    @Test
    @DisplayName("restoring puts it back exactly as it was, and twice is once")
    void restore_is_the_inverse_and_is_idempotent() {
        Comment comment = inTheThread();

        Comment restored = comment.markForErasure(FIRST_DELIVERY).restore();

        assertEquals(comment, restored, "the mark is fully reversible — that is the whole feature");
        assertSame(restored, restored.restore());
    }

    @Test
    @DisplayName("restoring a comment nobody marked is a no-op, not an error")
    void restore_of_an_unmarked_comment_does_not_throw() {
        Comment comment = inTheThread();

        assertSame(comment, comment.restore());
    }

    @Test
    @DisplayName("a mark without its instant — or an instant without its mark — cannot be built")
    void the_invariant_is_unrepresentable_not_merely_discouraged() {
        assertThrows(IllegalArgumentException.class,
                () -> new Comment("c1", "m1", "a@b.c", "hi", CommentStatus.PENDING_ERASURE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Comment("c1", "m1", "a@b.c", "hi", CommentStatus.ACTIVE, FIRST_DELIVERY));
    }
}
