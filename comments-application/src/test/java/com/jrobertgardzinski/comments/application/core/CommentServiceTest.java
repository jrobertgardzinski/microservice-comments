package com.jrobertgardzinski.comments.application.core;

import com.jrobertgardzinski.comments.application.votes.CommentVoteService;
import com.jrobertgardzinski.comments.config.core.RateLimit;
import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.identity.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions the bridge took over from the controller. None of these reaches a use case. */
class CommentServiceTest {

    private static final UserId SOMEBODY = UserId.random();

    @Test
    @DisplayName("a refused text costs the author nothing from their rate")
    void the_text_is_judged_before_the_rate() {
        RateLimit onePerMinute = new RateLimit(1);
        CommentService service = new CommentService(null, null, null, null, onePerMinute);

        assertInstanceOf(CommentService.Commenting.Invalid.class, service.add("m1", SOMEBODY, "  "));
        assertEquals(new CommentService.Commenting.TooLong(Comment.MAX_LENGTH),
                service.add("m1", SOMEBODY, "x".repeat(Comment.MAX_LENGTH + 1)));
        assertTrue(onePerMinute.tryAcquire(SOMEBODY.toString()), "the minute's one comment is still there");
    }

    @Test
    @DisplayName("an unstated hidden flag is refused, not read as a reveal")
    void an_unstated_hide_is_not_false() {
        assertInstanceOf(CommentService.Hiding.MissingHidden.class,
                new CommentService(null, null, null, null, new RateLimit(1)).hide("m1", "c1", null, Set.of("MODERATOR")));
    }

    @Test
    @DisplayName("a direction that is neither up nor down is refused")
    void an_unknown_direction_is_refused() {
        assertInstanceOf(CommentVoteService.Vote.InvalidDirection.class,
                new CommentVoteService(null).vote("m1", "c1", SOMEBODY, "sideways"));
    }
}
