package com.jrobertgardzinski.comments.domain;

import com.jrobertgardzinski.identity.UserId;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The twin of memes' ownership rule: ids when both sides have one, the address otherwise. */
@Epic("Domain")
@Feature("Authorship")
class CommentAuthorshipTest {

    private static final UserId ALICE = UserId.random();

    private static Comment withId() {
        return new Comment("c1", "m1", "alice@example.com", Optional.of(ALICE), "nice one",
                CommentStatus.ACTIVE, null);
    }

    @Test
    @DisplayName("the ids decide when both sides have one")
    void ids_decide_when_both_are_present() {
        assertTrue(withId().isAuthoredBy("alice.new@example.com", Optional.of(ALICE)),
                "the same id under a new address is still the author");
        assertFalse(withId().isAuthoredBy("alice@example.com", Optional.of(UserId.random())),
                "the same address under another id is somebody else");
    }

    @Test
    @DisplayName("the address decides while either side has no id")
    void address_decides_while_an_id_is_missing() {
        Comment withoutId = new Comment("c2", "m1", "alice@example.com", "nice one");

        assertTrue(withId().isAuthoredBy("alice@example.com", Optional.empty()), "a token before the cutover");
        assertTrue(withoutId.isAuthoredBy("alice@example.com", Optional.of(ALICE)), "a row before the backfill");
        assertFalse(withoutId.isAuthoredBy("stranger@example.com", Optional.of(ALICE)));
        assertFalse(withId().isAuthoredBy(null, Optional.empty()), "a signed-out viewer wrote nothing");
    }
}
