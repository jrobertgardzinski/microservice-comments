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
import com.jrobertgardzinski.comments.application.RekeyUserComments;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The consumer's half of the contract for the one fact this service reads off
 * {@code security-events}: an address change. The pact states the shape, the test proves it by
 * driving the REAL listener with the pact's own payload, and the generated file (../pacts,
 * committed) is what microservice-security verifies against its REAL producer —
 * {@code EmailChangedAnnouncer} — so a renamed or dropped field goes red in the producer's build
 * rather than in a live stack (ADR 0003).
 *
 * <p>Three fields are pinned and no more (tolerant reader): the {@code type} that selects the fact,
 * {@code oldEmail} — the address this service's rows are keyed by today — and {@code email}, which
 * is the NEW one. That last pair is the whole reason this pact is worth having: the producer calls
 * the subject's current address {@code email} on every fact on that topic, so the intuitive reading
 * of "email" here is precisely the wrong one, and a consumer that swapped the two would re-key
 * everyone onto the address they just left. The {@code id} and {@code version} the producer also
 * sends are deliberately absent — nothing here reads them, and a pact that pins what it does not use
 * is a pact that fails for reasons nobody has to care about.
 */
@Epic("Contract")
@Feature("Address changes")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "microservice-security", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class EmailChangedContractTest {

    private final RekeyUserComments rekeyUserComments = mock(RekeyUserComments.class);
    private final SecurityEventsListener listener =
            new SecurityEventsListener(rekeyUserComments, new ObjectMapper(), NoTransactions.template());

    @Pact(consumer = "microservice-comments")
    MessagePact emailChanged(MessagePactBuilder builder) {
        return builder.expectsToReceive("an email changed fact")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "EMAIL_CHANGED")
                        .stringType("oldEmail", "alice@old.example.com")
                        .stringType("email", "alice@new.example.com"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "emailChanged")
    @DisplayName("the rows move FROM oldEmail TO email — the fact's 'email' is the new address")
    void rekeysFromTheOldAddressToTheNewOne(List<Message> messages) throws Exception {
        listener.receive(messages.get(0).contentsAsString(), null);

        verify(rekeyUserComments).execute("alice@old.example.com", "alice@new.example.com");
    }
}
