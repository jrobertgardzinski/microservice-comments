package com.jrobertgardzinski.comments.closure;

import com.jrobertgardzinski.closure.ClosureCommand;
import com.jrobertgardzinski.closure.ClosureInitiator;
import com.jrobertgardzinski.closure.ClosureMessages;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.comments.domain.Observation;
import com.jrobertgardzinski.observation.Observations;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What happens to a person's comments when their account closes — proven with no broker, no database,
 * no Spring and no container. That is the whole reason this module exists: the flow is readable
 * and testable before anybody decides whether the portal is six services or one.
 */
@Epic("Saga")
@Feature("Account closure — the comments axis")
class CommentsClosureParticipantTest {

    private static final String LEAVER = "leaver@example.com";
    private static final String SAGA = "7d9f9e2a-1f0a-4f6e-9a1b-2c3d4e5f6a7b";

    private final MarkUserCommentsForErasure markForErasure = mock(MarkUserCommentsForErasure.class);
    private final RestoreUserComments restoreUserComments = mock(RestoreUserComments.class);
    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final List<Observation> observed = new ArrayList<>();

    /**
     * A unit of work that says whether it is open. The participant's one real obligation about
     * ordering is that the confirmation is made INSIDE the step it confirms, and an assertion is
     * the only way to hold it: both orders pass every other test in this file.
     */
    private boolean inside = false;
    private final Atomically atomically = step -> {
        inside = true;
        try {
            step.run();
        } finally {
            inside = false;
        }
    };

    private final List<String> confirmedWhileOpen = new ArrayList<>();
    private int confirmedCount = -1;
    private final ClosureConfirmations confirmations = (sagaId, leaver, reserved) -> {
        if (inside) {
            confirmedWhileOpen.add(sagaId);
        }
        confirmedCount = reserved;
    };

    private final CommentsClosureParticipant participant = new CommentsClosureParticipant(
            markForErasure, restoreUserComments, purgeUserComments, confirmations,
            (Observations<Observation>) observed::add, atomically);

    private ClosureCommand command(String type, String email, String initiatedBy, String rule) {
        return new ClosureCommand(type, SAGA, email, initiatedBy, Optional.ofNullable(rule));
    }

    @Test
    @DisplayName("the reversible step marks the leaver's comments and confirms the count it reserved")
    void mark_reserves_and_confirms() {
        when(markForErasure.execute(LEAVER)).thenReturn(40);

        ClosureOutcome outcome = participant.handle(
                command(ClosureMessages.PURGE_USER_CONTENT, LEAVER, ClosureInitiator.SELF.wire(), null));

        assertEquals(new ClosureOutcome.Reserved(40), outcome);
        assertEquals(40, confirmedCount, "the orchestrator counts what was reserved, not that it happened");
        assertEquals(List.of(SAGA), confirmedWhileOpen,
                "the confirmation must be made inside the same unit of work as the mark: after it, "
                        + "the comments are hidden with nothing owing the orchestrator a word");
        verifyNoInteractions(purgeUserComments, restoreUserComments);
    }

    @Test
    @DisplayName("a mark that reserved nothing is still confirmed — and observed")
    void mark_that_found_nothing_is_observed() {
        when(markForErasure.execute(LEAVER)).thenReturn(0);

        assertEquals(new ClosureOutcome.Reserved(0), participant.handle(
                command(ClosureMessages.PURGE_USER_CONTENT, LEAVER, ClosureInitiator.SELF.wire(), null)));

        assertEquals(0, confirmedCount);
        assertTrue(observed.stream().anyMatch(o -> o instanceof Observation.PurgeReservedNothing),
                "\"I hold nothing of theirs\" and \"their rows are under an address they changed\" "
                        + "look identical from in here, so the silence has to be countable");
    }

    @Test
    @DisplayName("a closure the owner asked for destroys, whatever rule the command carried")
    void a_self_closure_ignores_conditions() {
        participant.handle(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER,
                ClosureInitiator.SELF.wire(), "KEEP_POPULAR_ANONYMIZED:10"));

        verify(purgeUserComments).execute(LEAVER, Optional.of(new PurgeRule.Delete()));
    }

    @Test
    @DisplayName("an administrator's closure may state conditions, and they are honoured")
    void an_admin_closure_honours_its_rule() {
        participant.handle(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER,
                ClosureInitiator.ADMIN.wire(), "ANONYMIZE_AUTHOR"));

        verify(purgeUserComments).execute(LEAVER, Optional.of(new PurgeRule.AnonymizeAuthor()));
    }

    @Test
    @DisplayName("an unreadable rule falls back to the deployment's default instead of wedging the saga")
    void an_unreadable_rule_falls_back() {
        participant.handle(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER,
                ClosureInitiator.ADMIN.wire(), "KEEP_THE_FUNNY_ONES"));

        verify(purgeUserComments).execute(LEAVER, Optional.empty());
    }

    @Test
    @DisplayName("the compensation puts the marked comments back")
    void restore_undoes_the_mark() {
        assertEquals(new ClosureOutcome.Restored(), participant.handle(
                command(ClosureMessages.RESTORE_USER_CONTENT, LEAVER, ClosureInitiator.ADMIN.wire(), null)));

        verify(restoreUserComments).execute(LEAVER);
        verifyNoInteractions(purgeUserComments, markForErasure);
    }

    @Test
    @DisplayName("a command that names nobody is dropped WITHOUT confirming")
    void a_command_without_an_address_is_not_confirmed() {
        ClosureOutcome outcome = participant.handle(
                command(ClosureMessages.PURGE_USER_CONTENT, "  ", ClosureInitiator.SELF.wire(), null));

        assertInstanceOf(ClosureOutcome.Unaddressed.class, outcome);
        assertEquals(-1, confirmedCount, "confirming here would advance the saga on a deletion that never happened");
        verify(markForErasure, never()).execute(any());
    }

    @Test
    @DisplayName("a command for somebody else's axis is ignored, not failed")
    void another_participants_command_is_ignored() {
        assertEquals(new ClosureOutcome.NotOurs("MEMES_ANONYMISED"), participant.handle(
                command("MEMES_ANONYMISED", LEAVER, ClosureInitiator.ADMIN.wire(), null)));

        verifyNoInteractions(markForErasure, purgeUserComments, restoreUserComments);
        assertEquals(-1, confirmedCount);
    }

    @Test
    @DisplayName("the closure and the compensation are not confirmed: the orchestrator is ending the case")
    void only_the_reversible_step_is_confirmed() {
        participant.handle(command(ClosureMessages.ERASE_USER_CONTENT, LEAVER,
                ClosureInitiator.ADMIN.wire(), null));
        participant.handle(command(ClosureMessages.RESTORE_USER_CONTENT, LEAVER,
                ClosureInitiator.ADMIN.wire(), null));

        assertEquals(-1, confirmedCount);
        verify(purgeUserComments).execute(eq(LEAVER), any());
    }
}
