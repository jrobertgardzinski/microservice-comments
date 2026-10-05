package com.jrobertgardzinski.comments.domain.core;

import com.jrobertgardzinski.identity.UserId;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The twin of memes' ownership rule: authorship is the id's, and nothing else's. */
@Epic("Domain")
@Feature("Authorship")
class CommentAuthorshipTest {

    private static final UserId ALICE = UserId.random();

    @Test
    @DisplayName("authorship is the id's, and an anonymised row is nobody's")
    void authorship_is_the_ids() {
        Comment withId = new Comment("c1", "m1", Optional.of(ALICE), "nice one",
                CommentStatus.ACTIVE, null);
        Comment anonymised = new Comment("c2", "m1", "nice one", CommentStatus.ACTIVE, null);

        assertTrue(withId.isAuthoredBy(ALICE));
        assertFalse(withId.isAuthoredBy(UserId.random()), "another id is somebody else");
        assertFalse(anonymised.isAuthoredBy(ALICE), "a row without an id has been anonymised: nobody's");
    }
}
