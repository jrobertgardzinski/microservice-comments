package com.jrobertgardzinski.comments.infrastructure;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.comments.config.PurgeRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The consumer's half of the account-deletion saga contract: the pact states the exact shape of
 * every {@code content-commands} event this service acts on, and proves each one by driving the
 * real listener with the pact's payload. The generated pact (pacts/, committed) is verified against
 * the REAL orchestrator by microservice-offboarding's provider tests. Only the fields this consumer
 * reads are in the contract; the producer may add more (tolerant reader).
 *
 * <p>There are THREE commands now, because the saga has two phases. The mark
 * ({@code PURGE_USER_CONTENT}) is unchanged on the wire — deliberately, so this pact keeps its
 * meaning across the change — and the closure ({@code ERASE_USER_CONTENT}) and the compensation
 * ({@code RESTORE_USER_CONTENT}) join it in the same envelope. Note where the POLICY sits: on the
 * mark it is ferried and ignored (the mark decides nothing), on the closure it is read, because
 * the rule is applied at erasure time.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "microservice-offboarding", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class PurgeCommandsContractTest {

    private final PurgeUserComments purgeUserComments = mock(PurgeUserComments.class);
    private final MarkUserCommentsForErasure markForErasure = mock(MarkUserCommentsForErasure.class);
    private final PurgeCommandsListener listener = new PurgeCommandsListener(markForErasure,
            mock(RestoreUserComments.class), purgeUserComments, new CapturedConfirmations(),
            new ObjectMapper(), NoTransactions.template());

    @Pact(consumer = "microservice-comments")
    MessagePact purgeCommand(MessagePactBuilder builder) {
        return builder.expectsToReceive("a purge user content command")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "PURGE_USER_CONTENT")
                        .uuid("sagaId")
                        .stringType("email", "leaver@example.com"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "purgeCommand")
    void purgesWithTheDeploymentDefault(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);
        // the mark, not the erasure: this command is the reversible half of the saga
        verify(markForErasure).execute("leaver@example.com");
    }

    @Pact(consumer = "microservice-comments")
    MessagePact purgeCommandWithPolicy(MessagePactBuilder builder) {
        return builder.expectsToReceive("a purge user content command with an explicit policy")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "PURGE_USER_CONTENT")
                        .uuid("sagaId")
                        .stringType("email", "leaver@example.com")
                        .object("policy")
                        .stringType("comments", "ANONYMIZE_AUTHOR")
                        .closeObject())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "purgeCommandWithPolicy")
    void purgesWithTheLeaversChoice(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);
        // the policy rides the mark and is not acted on here — the CLOSURE is what applies it
        verify(markForErasure).execute("leaver@example.com");
    }

    @Pact(consumer = "microservice-comments")
    MessagePact eraseCommand(MessagePactBuilder builder) {
        return builder.expectsToReceive("an erase user content command closing the saga")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "ERASE_USER_CONTENT")
                        .uuid("sagaId")
                        .stringType("email", "leaver@example.com")
                        .object("policy")
                        .stringType("comments", "ANONYMIZE_AUTHOR")
                        .closeObject())
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "eraseCommand")
    void erasesOnTheClosureAndAppliesTheRule(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);
        verify(purgeUserComments).execute("leaver@example.com",
                Optional.of(new PurgeRule.AnonymizeAuthor()));
    }

    @Pact(consumer = "microservice-comments")
    MessagePact restoreCommand(MessagePactBuilder builder) {
        return builder.expectsToReceive("a restore user content command compensating the saga")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "RESTORE_USER_CONTENT")
                        .uuid("sagaId")
                        .stringType("email", "leaver@example.com"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "restoreCommand")
    void restoresOnTheCompensation(List<Message> messages) throws Exception {
        RestoreUserComments restore = mock(RestoreUserComments.class);
        new PurgeCommandsListener(markForErasure, restore, purgeUserComments,
                new CapturedConfirmations(), new ObjectMapper(), NoTransactions.template())
                .receive(messages.get(0).contentsAsString(), null);
        verify(restore).execute("leaver@example.com");
    }
}
