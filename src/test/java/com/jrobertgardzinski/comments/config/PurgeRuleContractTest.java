package com.jrobertgardzinski.comments.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The purge-rule dictionary is DELIBERATELY duplicated with microservice-memes (a shared textual
 * contract spoken by the saga command, env vars and the deletion wizard; a shared library was
 * weighed and deferred until a third consumer appears). This test pins the literals and their
 * semantics IDENTICALLY to {@code memes-config}'s {@code PurgeRuleTest} — if either side's
 * vocabulary drifts, one of the mirrored tests goes red before production notices.
 */
@Epic("Config")
@Feature("Purge rules")
class PurgeRuleContractTest {

    @Test
    @DisplayName("speaks the shared vocabulary: DELETE, ANONYMIZE_AUTHOR, KEEP_POPULAR_ANONYMIZED:n")
    void parses_the_vocabulary() {
        assertEquals(new PurgeRule.Delete(), PurgeRule.parse("DELETE"));
        assertEquals(new PurgeRule.AnonymizeAuthor(), PurgeRule.parse("ANONYMIZE_AUTHOR"));
        assertEquals(new PurgeRule.KeepPopularAnonymized(100), PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:100"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse("EXPLODE"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:abc"));
        assertThrows(IllegalArgumentException.class, () -> PurgeRule.parse("KEEP_POPULAR_ANONYMIZED:0"));
    }

    @Test
    @DisplayName("popularity decides what a rule keeps")
    void keeps_by_score() {
        assertFalse(new PurgeRule.Delete().keeps(1000));
        assertTrue(new PurgeRule.AnonymizeAuthor().keeps(-5));
        assertTrue(new PurgeRule.KeepPopularAnonymized(100).keeps(100));
        assertFalse(new PurgeRule.KeepPopularAnonymized(100).keeps(99));
    }
}
