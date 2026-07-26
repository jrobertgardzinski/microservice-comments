package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.outbox.OutboxEvent;
import com.jrobertgardzinski.outbox.OutboxRepublisher;
import com.jrobertgardzinski.outbox.OutboxTable;
import com.jrobertgardzinski.outbox.RepublisherSettings;
import com.jrobertgardzinski.outbox.TransactionalOutbox;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The promotion round 10 bought this service, tested where it has to be true: against the real
 * {@code comment_events_outbox} table V4 creates and a real Spring transaction manager.
 *
 * <p>Until now COMMENTS_DELETED was announced after the thread delete committed, with a callback that
 * logged a failure. A rollback let nothing out — that much was already right — but a crash or a broker
 * outage between the commit and the send lost the announcement <em>permanently</em>, and that was the
 * part with no way back: a redelivered MEME_DELETED finds an empty thread and deliberately announces
 * nothing, so nothing in the portal ever re-derives it. The only repair was the UI noticing a
 * dangling reference.
 *
 * <p>The first test below is therefore the whole point of the round: the event survives the gap. The
 * rest pin the properties that make the survival worth anything — the row does not escape a rollback,
 * a delivered event is not delivered twice, an unconfirmed send is not mistaken for a delivery, and
 * the retention that keeps the table from growing forever does not eat an obligation.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
class CommentsOutboxTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private SpringOutbox springOutbox;
    private TransactionalOutbox outbox;
    private KafkaCommentEvents events;
    private OutboxRepublisher republisher;

    private static final long RETENTION_HOURS = 24;

    /** The dials this service runs on, so the assertions quote the configuration, not magic numbers. */
    private static final RepublisherSettings SETTINGS =
            RepublisherSettings.defaults(Duration.ofHours(RETENTION_HOURS));

    @BeforeEach
    void freshOutbox() {
        // exactly CommentsOutboxConfig's wiring, with a mock broker — a broker outage is what half
        // of these tests are about, and it cannot be staged with a real one
        springOutbox = new SpringOutbox(dataSource, OutboxTable.named(CommentsOutboxConfig.TABLE),
                Clock.systemUTC(), new KafkaCommentDispatch(kafka));
        outbox = springOutbox.outbox();
        events = new KafkaCommentEvents(springOutbox, mapper);
        republisher = CommentsOutboxConfig.republisher(springOutbox, RETENTION_HOURS);
        jdbc.sql("DELETE FROM " + CommentsOutboxConfig.TABLE).update();
    }

    @Test
    @DisplayName("THE PROMOTION: a committed hop whose send never got through is delivered later, unchanged")
    void an_event_survives_the_gap_between_the_commit_and_the_send() throws Exception {
        // the broker is down at exactly the wrong moment — or, equivalently for this table, the
        // process dies right after the commit and never gets to send at all
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        String memeId = UUID.randomUUID().toString();
        List<String> dropped = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        tx.executeWithoutResult(status -> events.commentsDeleted(memeId, dropped));

        ArgumentCaptor<ProducerRecord<String, String>> firstTry =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(firstTry.capture());
        assertEquals(1, rowsFor(memeId), "the announcement must survive the failed send — before"
                + " round 10 this is where it was lost for good");
        assertFalse(published(memeId), "an unconfirmed send must NOT mark the row");

        // the broker comes back and the row comes of age; the republisher is the guarantee
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        backdateBeyondMinAge(memeId);
        republisher.runOnce();

        ArgumentCaptor<ProducerRecord<String, String>> redelivered =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(redelivered.capture());
        assertEquals(firstTry.getValue().value(), redelivered.getValue().value(),
                "the redelivery must be the SAME event, byte for byte — the envelope id included, or"
                        + " collections could not tell a duplicate from a second deletion");
        assertEquals(firstTry.getValue().key(), redelivered.getValue().key());
        assertEquals(firstTry.getValue().topic(), redelivered.getValue().topic());
        assertTrue(published(memeId), "a CONFIRMED redelivery marks the row");

        JsonNode delivered = mapper.readTree(redelivered.getValue().value());
        assertEquals("COMMENTS_DELETED", delivered.path("type").asText());
        assertEquals(memeId, delivered.path("memeId").asText());
        assertEquals(dropped, ids(delivered), "the complete set of ids, still");
        assertEquals(1, delivered.path("version").asInt(), "envelope v1 (workspace ADR 0004)");

        republisher.runOnce();
        verifyNoMoreInteractions(kafka);   // delivered means done — no third send
    }

    @Test
    @DisplayName("the envelope id IS the row id — which is what makes a redelivery a recognizable duplicate")
    void the_envelope_id_is_the_row_id() throws Exception {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        String memeId = UUID.randomUUID().toString();

        tx.executeWithoutResult(status ->
                events.commentsDeleted(memeId, List.of(UUID.randomUUID().toString())));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        String envelopeId = mapper.readTree(sent.getValue().value()).path("id").asText();

        // round 9 minted this id with UUID.randomUUID() while serialising, and said so: "random, not
        // derived: there is no republication path to deduplicate against". There is one now, so the
        // id has to come from the row — otherwise every re-send would look like a new deletion
        assertEquals(envelopeId, jdbc.sql("SELECT id FROM " + CommentsOutboxConfig.TABLE
                        + " WHERE event_key = ?").params(memeId).query(String.class).single(),
                "the payload's id and the row's primary key must be the same string");
    }

    @Test
    @DisplayName("a rolled-back hop leaves NO row and sends NOTHING — the round-9 guarantee, now the table's")
    void a_rollback_takes_the_announcement_with_it() {
        String memeId = UUID.randomUUID().toString();

        tx.executeWithoutResult(status -> {
            events.commentsDeleted(memeId, List.of(UUID.randomUUID().toString()));
            status.setRollbackOnly();
        });

        verifyNoInteractions(kafka);
        assertEquals(0, rowsFor(memeId),
                "the announcement must share the fate of the transaction that wrote it");
    }

    @Test
    @DisplayName("the happy path publishes exactly once: the republisher does not double a marked row")
    void a_confirmed_first_attempt_is_published_exactly_once() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        String memeId = UUID.randomUUID().toString();

        tx.executeWithoutResult(status ->
                events.commentsDeleted(memeId, List.of(UUID.randomUUID().toString())));

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertTrue(published(memeId), "a confirmed delivery marks the row");

        backdateBeyondMinAge(memeId);   // even old enough to qualify, a published row is not re-sent
        republisher.runOnce();
        verifyNoMoreInteractions(kafka);
    }

    @Test
    @DisplayName("a fresh unpublished row is left alone — its first attempt may still be in flight")
    void the_republisher_leaves_fresh_rows_to_the_first_attempt() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        String memeId = UUID.randomUUID().toString();
        tx.executeWithoutResult(status ->
                events.commentsDeleted(memeId, List.of(UUID.randomUUID().toString())));
        verify(kafka, times(1)).send(any(ProducerRecord.class));

        republisher.runOnce();   // no backdating: the row is seconds old

        verifyNoMoreInteractions(kafka);
        assertFalse(published(memeId), "still owed — until it is old enough for the republisher");
    }

    @Test
    @DisplayName("retention reaps delivered rows past the threshold and never an undelivered one")
    void retention_reaps_only_delivered_rows_past_the_threshold() {
        String deliveredLongAgo = UUID.randomUUID().toString();
        String deliveredJustNow = UUID.randomUUID().toString();
        String stillOwed = UUID.randomUUID().toString();
        append("e-old-done", deliveredLongAgo, true);
        append("e-new-done", deliveredJustNow, true);
        append("e-old-owed", stillOwed, false);
        backdateBeyondRetention(deliveredLongAgo);
        backdateBeyondRetention(stillOwed);
        // the pass also re-tries the aged unpublished row — keep the broker down so it stays owed
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        republisher.runOnce();

        assertEquals(0, rowsFor(deliveredLongAgo),
                "a delivered event past retention has no value left — the row must go");
        assertEquals(1, rowsFor(deliveredJustNow),
                "retention must not touch a delivered row younger than the threshold");
        assertEquals(1, rowsFor(stillOwed),
                "an UNDELIVERED row is never reaped, however old — it still carries an obligation");
        assertFalse(published(stillOwed));
    }

    @Test
    @DisplayName("a retention of zero or less refuses the boot, naming the property and echoing the value")
    void a_non_positive_retention_refuses_to_start() {
        // the library owns no configuration namespace, so the name travels from this service into
        // its message — the operator who set the env var is the one who reads it
        assertEquals("comments.outbox.retention-hours", CommentsOutboxConfig.RETENTION_PROPERTY,
                "the constant must spell the dial exactly as application.properties does");

        for (long broken : new long[]{0, -1, -24}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> CommentsOutboxConfig.republisher(springOutbox, broken));
            assertTrue(refused.getMessage().contains(CommentsOutboxConfig.RETENTION_PROPERTY),
                    "the operator must read the dial's NAME: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(String.valueOf(broken)),
                    "…and the value they set: " + refused.getMessage());
        }
    }

    @Test
    @DisplayName("a thread of thousands is stored and redelivered intact — the payload column is TEXT")
    void a_big_thread_survives_the_round_trip() throws Exception {
        // COMMENTS_DELETED is the one event in this estate whose payload grows LINEARLY with the
        // aggregate: one uuid per comment. A popular meme's thread crosses the library's canary
        // (1024 chars, ~26 ids) routinely and logs a warning, which is exactly what the canary is
        // for. This pins what the canary does NOT do: nothing is truncated on the way through the
        // table, so a redelivery is still the same event.
        //
        // WHAT IS STILL OPEN, and deliberately not solved here: at ~26k comments the payload passes
        // Kafka's default max.request.size (1 MiB) and the send is refused — the row then stays
        // unpublished forever and every republisher pass retries it. Splitting the announcement into
        // chunks changes the CONSUMER contract (collections would have to tolerate partial hops), so
        // it belongs in a round of its own; the canary is what will report it first.
        List<String> hugeThread = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            hugeThread.add(UUID.randomUUID().toString());
        }
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        String memeId = UUID.randomUUID().toString();

        tx.executeWithoutResult(status -> events.commentsDeleted(memeId, hugeThread));

        ArgumentCaptor<ProducerRecord<String, String>> firstTry =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(firstTry.capture());
        assertTrue(firstTry.getValue().value().length() > TransactionalOutbox.EXPECTED_MAX_PAYLOAD_CHARS,
                "this payload is meant to be past the canary — otherwise the test proves nothing");

        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        backdateBeyondMinAge(memeId);
        republisher.runOnce();

        ArgumentCaptor<ProducerRecord<String, String>> redelivered =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(redelivered.capture());
        assertEquals(firstTry.getValue().value(), redelivered.getValue().value(),
                "a 2000-comment announcement must come back byte-for-byte — the column is TEXT and"
                        + " the library never re-serialises what it stored");
        assertEquals(hugeThread, ids(mapper.readTree(redelivered.getValue().value())));
    }

    private List<String> ids(JsonNode event) {
        List<String> ids = new ArrayList<>();
        event.path("commentIds").forEach(id -> ids.add(id.asText()));
        return ids;
    }

    /** A stored row for the retention test — no send, no transaction, just the row. */
    private void append(String eventId, String memeId, boolean delivered) {
        springOutbox.append(new OutboxEvent(eventId, KafkaCommentEvents.TOPIC,
                KafkaCommentEvents.COMMENTS_DELETED, memeId, null, "{}"));
        if (delivered) {
            outbox.markPublished(eventId);
        }
    }

    private int rowsFor(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + CommentsOutboxConfig.TABLE + " WHERE event_key = ?")
                .params(memeId).query(Integer.class).single();
    }

    private boolean published(String memeId) {
        return jdbc.sql("SELECT published FROM " + CommentsOutboxConfig.TABLE + " WHERE event_key = ?")
                .params(memeId).query(Boolean.class).single();
    }

    private void backdateBeyondMinAge(String memeId) {
        // the row is seconds old and the republisher only touches rows past its minimum age
        // (SETTINGS.minAge() = 30s); in production those 30s pass by themselves
        jdbc.sql("UPDATE " + CommentsOutboxConfig.TABLE + " SET created_at = DATEADD('SECOND', ?,"
                        + " created_at) WHERE event_key = ?")
                .params(-(SETTINGS.minAge().toSeconds() * 2), memeId).update();
    }

    private void backdateBeyondRetention(String memeId) {
        jdbc.sql("UPDATE " + CommentsOutboxConfig.TABLE + " SET created_at = DATEADD('HOUR', ?,"
                        + " created_at) WHERE event_key = ?")
                .params(-(RETENTION_HOURS + 1), memeId).update();
    }
}
