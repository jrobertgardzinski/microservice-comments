package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.outbox.Dispatch;
import com.jrobertgardzinski.outbox.DispatchOutcome;
import com.jrobertgardzinski.outbox.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

/**
 * The outbox's send, over this service's {@code KafkaTemplate} — the one thing the shared outbox
 * library cannot do for itself, since it deliberately knows nothing about brokers.
 *
 * <p>Both halves of the {@link Dispatch} contract are load-bearing here:
 *
 * <ol>
 *   <li><strong>The calling thread does not wait for the acknowledgement.</strong> The record is
 *       handed over and the outcome comes from the completion callback, on the producer's I/O
 *       thread. It matters more here than the caller's identity suggests: the first attempt is
 *       parked on the commit of a transaction opened by {@link MemesEventsListener}, i.e. it runs on
 *       the Kafka LISTENER thread of the {@code comments} consumer group. A blocking send there
 *       would hold that thread for the broker's patience on every cascade hop, and a long enough
 *       hold overruns {@code max.poll.interval.ms} and turns a broker outage into a consumer-group
 *       rebalance — the outage would then also stop this service dropping threads at all.
 *       <p>"Does not wait" is only true if {@code max.block.ms} is pinned: {@code send} is
 *       asynchronous just once the record reaches the accumulator, and Kafka's default 60s wait for
 *       metadata and buffer space would make the paragraph above false. That dial is set explicitly
 *       in {@code application.properties} and asserted by {@link KafkaProducerClocksTest}. Round 10
 *       added both — this service had NEITHER before, which was recorded as a risk when the
 *       fire-and-forget variant shipped.</li>
 *   <li><strong>{@code confirmed()} means the BROKER acknowledged</strong> — the send future
 *       completing without failure, nothing earlier. On that word the library marks the row
 *       published and stops guaranteeing the event, so an optimistic confirmation would be a lost
 *       event with a green log. {@link CommentsOutboxTest} keeps it honest from the outside: an
 *       unconfirmed send must leave the row unmarked.</li>
 * </ol>
 */
final class KafkaCommentDispatch implements Dispatch {

    private final KafkaTemplate<String, String> kafka;

    KafkaCommentDispatch(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void send(OutboxEvent event, DispatchOutcome outcome) {
        kafka.send(toRecord(event)).whenComplete((ack, notConfirmed) -> {
            if (notConfirmed != null) {
                outcome.failed(notConfirmed);
                return;
            }
            outcome.confirmed();
        });
        // A synchronous refusal (producer closed, buffer full, serialisation) throws out of send()
        // rather than completing the future; the library treats a throw as a failure, which is the
        // same conclusion — the row stays, the republisher retries — so it is not caught here.
    }

    /**
     * The row rebuilt into the record the broker gets. The correlation id comes from the STORED
     * event, not from the MDC: a republication happens on a scheduler thread hours later, where the
     * ambient trace is empty or, worse, somebody else's. Since round 11 both producers in this service
     * are here — the saga's confirmations used to be stamped from the MDC instead, which was only ever
     * right because they were never republished.
     */
    static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.topic(), event.key(), event.payload());
        if (event.cid() != null) {
            record.headers().add(KafkaTracing.HEADER, event.cid().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
