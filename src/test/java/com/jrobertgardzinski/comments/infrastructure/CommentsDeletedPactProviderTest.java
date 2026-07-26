package com.jrobertgardzinski.comments.infrastructure;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deletion CASCADE's second hop, provider side: microservice-user-collections' committed pact
 * states which COMMENTS_DELETED fields — and which TOPIC — its cascade consumer depends on, and this
 * test proves the REAL producer emits them. The verification drives the whole production path a
 * MEME_DELETED takes through this service ({@link MemesEventsListener} → {@link DeleteThread} →
 * {@link KafkaCommentEvents}) and captures the record that comes out of the Kafka template; nothing
 * here is hand-written JSON.
 *
 * <p><b>Both halves of the record are verified.</b> The payload is checked against the pact's body,
 * and {@code record.topic()} is reported as the message's {@code topic} metadata, which the pact also
 * pins. The audit of 26.07 called an unasserted topic name the system's most dangerous structural
 * gap: rename the topic on one side and the announcements fall into the void — no references are
 * cleaned up, nothing fails, CI stays green. Reporting the real {@code topic()} into a pact that
 * names the consumer's subscription closes that gap ACROSS the two repositories, which no test
 * inside either one can do alone.
 *
 * <p>Note which topic that is: {@code comments-events}, shared with the account-deletion saga's
 * USER_CONTENT_PURGED confirmations. That sharing is pinned separately by
 * {@link CommentsEventsTopicTest} here and by SharedTopicCascadeTrafficTest in
 * microservice-offboarding; this file pins the cascade's own half of it.
 *
 * <p>Skipped, not failed, when the consumer repo is not checked out next to this one — the workspace
 * convention. The predicate looks for the pact FILE, not merely a directory: a directory check is
 * what let {@code SecurityOutcomePactProviderTest} in microservice-offboarding skip silently for
 * weeks after the workspace split, and a contract test that never runs is indistinguishable from one
 * that passes.
 */
@Provider("microservice-comments")
@PactFolder(CommentsDeletedPactProviderTest.PACT_FOLDER)
@EnabledIf(value = "consumerPactCheckedOut",
        disabledReason = "microservice-user-collections' comments pact is not checked out next to this repo")
class CommentsDeletedPactProviderTest {

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    static final String PACT_FOLDER = "../microservice-user-collections/pacts";

    static final String PACT_FILE = "microservice-user-collections-microservice-comments.json";

    static boolean consumerPactCheckedOut() {
        return Files.isRegularFile(Path.of(PACT_FOLDER, PACT_FILE));
    }

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget(List.of("com.jrobertgardzinski")));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void theAnnouncementShapeAndTopicTheCascadeReliesOn(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @PactVerifyProvider("a comments deleted announcement")
    public MessageAndMetadata aCommentsDeletedAnnouncement() {
        ProducerRecord<String, String> record = realAnnouncement(UUID.randomUUID().toString(),
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        return new MessageAndMetadata(
                record.value().getBytes(StandardCharsets.UTF_8),
                // the topic is transport, so it belongs in the message's metadata rather than its
                // body — and it is the REAL record's, never the constant: a producer that starts
                // publishing elsewhere must fail here
                Map.of("contentType", "application/json", "topic", record.topic()));
    }

    /**
     * The record the real cascade would hand the broker after dropping a meme's thread.
     *
     * <p>Two collaborators stand in, for the same reason as the {@code KafkaTemplate} mock in
     * {@link PurgeConfirmationPactProviderTest}: they are adapters, not the code under test. The
     * repository behind {@link DeleteThread} is a database, so the use case is stubbed to report the
     * ids it dropped — everything that turns those ids into an announcement (the hop's transaction,
     * the outbox row, the envelope, the key, the topic) is the real thing, down to a real H2 holding
     * the row the record is built from.
     */
    @SuppressWarnings("unchecked")
    static ProducerRecord<String, String> realAnnouncement(String memeId, List<String> commentIds) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        DeleteThread deleteThread = mock(DeleteThread.class);
        when(deleteThread.execute(memeId)).thenReturn(commentIds);
        ObjectMapper mapper = new ObjectMapper();
        OutboxTestDatabase db = OutboxTestDatabase.with(kafka);

        new MemesEventsListener(deleteThread, new KafkaCommentEvents(db.outbox(), mapper), mapper,
                db.tx())
                .receive("{\"type\":\"MEME_DELETED\",\"memeId\":\"" + memeId
                        + "\",\"eventId\":\"" + UUID.randomUUID() + "\"}", null);

        ArgumentCaptor<ProducerRecord<String, String>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        return sent.getValue();
    }
}
