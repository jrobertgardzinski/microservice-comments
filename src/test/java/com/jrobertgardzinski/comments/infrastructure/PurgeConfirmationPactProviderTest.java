package com.jrobertgardzinski.comments.infrastructure;

import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * The saga contract's other direction, provider side: microservice-offboarding's committed pact
 * states which USER_CONTENT_PURGED fields its orchestrator reads; this test proves the REAL
 * listener — driven by a purge command, its confirmation captured on the way to the outbox — emits
 * that shape. Skipped, not failed, when the consumer repo is not checked out next to this one.
 */
@Provider("microservice-comments")
@PactFolder("../microservice-offboarding/pacts")
@EnabledIf(value = "consumerPactsCheckedOut",
        disabledReason = "microservice-offboarding is not checked out next to this repo")
class PurgeConfirmationPactProviderTest {

    static boolean consumerPactsCheckedOut() {
        return Files.isDirectory(Path.of("../microservice-offboarding/pacts"));
    }

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget(List.of("com.jrobertgardzinski")));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void theConfirmationShapeTheOrchestratorReliesOn(PactVerificationContext context) {
        context.verifyInteraction();
    }

    /**
     * Round 11 moved the confirmation onto the outbox round 10 built, so what is captured here is the
     * outbox EVENT rather than a producer record — the payload, which is what the contract is about,
     * comes from the same builder either way. The topic it rides is pinned separately by
     * {@link PurgeConfirmationTopicTest}, because a payload with the right shape on the wrong topic is
     * exactly the gap this pact cannot see.
     */
    @PactVerifyProvider("a user content purged confirmation")
    public String aUserContentPurgedConfirmation() throws Exception {
        CapturedConfirmations confirmations = new CapturedConfirmations();
        PurgeCommandsListener listener = new PurgeCommandsListener(mock(PurgeUserComments.class),
                confirmations, new ObjectMapper(), NoTransactions.template());
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\","
                + "\"sagaId\":\"7d9f9e2a-1f0a-4f6e-9a1b-2c3d4e5f6a7b\","
                + "\"email\":\"leaver@example.com\"}", null);
        return confirmations.captured().payload();
    }
}
