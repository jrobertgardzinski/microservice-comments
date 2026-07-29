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
import com.jrobertgardzinski.comments.application.DeleteThread;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumer contract for the first hop of the deletion cascade: {@code MEME_DELETED}, published
 * by microservice-memes and consumed here to drop the meme's whole comment thread.
 *
 * <p>It was the only inter-service contract in the estate with no pact. Everything else — the purge
 * commands, the purge confirmations, the offboarding outcomes, the introspection gate — has one,
 * and this hop is not less important for being simple: rename or retype {@code memeId} on the
 * producer's side and comment threads stop being deleted, silently, with both services' own suites
 * green. A pact is what makes the producer's build fail instead.
 *
 * <p>Only the fields this listener reads are in it ({@code type} and {@code memeId}); the producer
 * already sends more (an {@code eventId}, a {@code version}) and may add further fields — tolerant
 * reader, ADR 0004.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "microservice-memes", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class MemeDeletedContractTest {

    private final DeleteThread deleteThread = mock(DeleteThread.class);
    private final CommentEvents commentEvents = mock(CommentEvents.class);
    private final MemesEventsListener listener = new MemesEventsListener(
            deleteThread, commentEvents, new ObjectMapper(), NoTransactions.template());

    @Pact(consumer = "microservice-comments")
    MessagePact memeDeleted(MessagePactBuilder builder) {
        return builder.expectsToReceive("a meme deleted announcement for the comment thread")
                .withContent(new PactDslJsonBody()
                        .stringValue("type", "MEME_DELETED")
                        .stringType("memeId", "known-meme"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "memeDeleted")
    @DisplayName("the announcement drops that meme's whole thread")
    void theAnnouncementDropsTheThread(List<Message> messages) {
        when(deleteThread.execute(any())).thenReturn(List.of());

        listener.receive(messages.get(0).contentsAsString(), null);

        verify(deleteThread).execute("known-meme");
    }
}
