package com.jrobertgardzinski.comments.infrastructure;

import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The saga contract's other direction, provider side: microservice-security's committed pact
 * states which USER_CONTENT_PURGED fields its orchestrator reads; this test proves the REAL
 * listener — driven by a purge command, its confirmation captured off the Kafka template — emits
 * that shape. Skipped, not failed, when the consumer repo is not checked out next to this one.
 */
@Provider("microservice-comments")
@PactFolder("../microservice-security/pacts")
@EnabledIf(value = "consumerPactsCheckedOut",
        disabledReason = "microservice-security is not checked out next to this repo")
class PurgeConfirmationPactProviderTest {

    static boolean consumerPactsCheckedOut() {
        return Files.isDirectory(Path.of("../microservice-security/pacts"));
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

    @PactVerifyProvider("a user content purged confirmation")
    @SuppressWarnings("unchecked")
    public String aUserContentPurgedConfirmation() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        PurgeCommandsListener listener =
                new PurgeCommandsListener(mock(PurgeUserComments.class), kafka, new ObjectMapper());
        listener.receive("{\"type\":\"PURGE_USER_CONTENT\","
                + "\"sagaId\":\"7d9f9e2a-1f0a-4f6e-9a1b-2c3d4e5f6a7b\","
                + "\"email\":\"leaver@example.com\"}", null);
        ArgumentCaptor<ProducerRecord<String, String>> confirmation =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(confirmation.capture());
        return confirmation.getValue().value();
    }
}
