package com.jrobertgardzinski.comments.infrastructure;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * This service sits in the MIDDLE of the deletion cascade, so it pins a topic name at each end.
 *
 * <p>The audit of 26.07 named the unasserted topic name the system's most dangerous structural
 * finding: rename a topic on one side only — a typo, a copied constant, a "-v2" suffix — and the
 * producer publishes into the void while the consumer waits forever. No data is deleted, no error is
 * raised, and every signal the portal has stays green, because every process involved is perfectly
 * healthy. So both names are pinned to LITERALS here, and to the same literals in the repositories
 * at the other end.
 *
 * <p>The repositories cannot share a constant — they are separate git repositories, released and
 * versioned apart, and a shared library for two strings would couple their release cycles for
 * nothing. The literals are therefore duplicated deliberately, and each says where its twin lives:
 *
 * <ul>
 *   <li><b>{@code memes-events}</b>, what this service CONSUMES: produced by
 *       {@code KafkaMemeEvents#TOPIC} in <b>microservice-memes</b> and pinned there in
 *       {@code memes-infrastructure/src/test/java/com/jrobertgardzinski/memes/infrastructure/MemeDeletedTopicTest.java}.</li>
 *   <li><b>{@code comments-events}</b>, what this service PRODUCES: consumed by
 *       {@code CascadeConsumer#COMMENTS_TOPIC} in <b>microservice-user-collections</b> and pinned
 *       there in
 *       {@code src/test/java/com/jrobertgardzinski/collections/infrastructure/CascadeTopicNamesTest.java}
 *       (it also carries the saga's confirmations, which microservice-offboarding reads).</li>
 * </ul>
 *
 * <p><b>Changing either string here REQUIRES the matching change in the other repository, in the
 * file named above.</b> That is the price of separate repositories, and a test failing on both sides
 * is what makes the price visible instead of silent.
 *
 * <p>The name is also checked ACROSS the repositories, not merely inside each: the COMMENTS_DELETED
 * pact carries the topic as message metadata and
 * {@link CommentsDeletedPactProviderTest} answers it with the real record's {@code topic()}.
 *
 * <p>Which is why the record here comes from that class's fixture: the pact and this test must assert
 * the SAME real object, or one of them could go on passing while the other describes a producer that
 * no longer exists. Unlike the pact test this one has no {@code @EnabledIf} — it needs no
 * neighbouring checkout, so it runs in every layout, including a solo one.
 */
class CascadeTopicNamesTest {

    /** See the class comment: the twin literal lives in microservice-memes. */
    private static final String MEMES_EVENTS = "memes-events";

    /** See the class comment: the twin literal lives in microservice-user-collections. */
    private static final String COMMENTS_EVENTS = "comments-events";

    @Test
    @DisplayName("the cascade's inbound listener is configured for the topic memes publishes on")
    void the_listener_is_configured_for_the_agreed_inbound_topic() throws Exception {
        // the listener's CONFIGURATION, not a constant next to it: this is the string Spring hands
        // the consumer group, so it is the string that decides whether anything arrives at all
        KafkaListener configured = MemesEventsListener.class
                .getDeclaredMethod("receive", String.class, String.class)
                .getAnnotation(KafkaListener.class);

        assertNotNull(configured, "the cascade's entry point must still BE a listener");
        assertArrayEquals(new String[]{MEMES_EVENTS}, configured.topics(),
                "microservice-memes announces MEME_DELETED here — see the class comment before"
                        + " changing this");
    }

    @Test
    @DisplayName("the cascade's outbound announcement goes to the topic collections subscribes to")
    void the_announcement_goes_to_the_agreed_outbound_topic() {
        // the REAL record, not the constant: what matters is where the producer actually sends
        ProducerRecord<String, String> announced = CommentsDeletedPactProviderTest.realAnnouncement(
                UUID.randomUUID().toString(), List.of(UUID.randomUUID().toString()));

        assertEquals(COMMENTS_EVENTS, announced.topic(),
                "microservice-user-collections' cascade consumer subscribes here — see the class"
                        + " comment before changing this");
        assertEquals(COMMENTS_EVENTS, KafkaCommentEvents.TOPIC,
                "and the constant both producers in this service share must say the same");
    }
}
