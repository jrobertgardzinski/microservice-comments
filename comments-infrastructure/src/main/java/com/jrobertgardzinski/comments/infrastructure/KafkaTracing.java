package com.jrobertgardzinski.comments.infrastructure;

/**
 * Carries the correlation id across the Kafka boundary, so the async saga and cascade hops keep the
 * same {@code cid} as the request that started them (matching {@link CorrelationIdFilter} on the HTTP
 * edge). Consumers restore the header into MDC; producers stamp it from the outbox ROW.
 *
 * <p>Round 11 left this class with the name alone. It used to own a {@code withCid(topic, key, value)}
 * helper that read the ambient MDC while building a record — right for a send that happens on the
 * thread that announced, and wrong for everything else. Both producers in this service now go through
 * the outbox, where the cid is captured at announce time, STORED in the row and stamped from there,
 * because a republication runs on a scheduler thread hours later whose MDC is empty or, worse,
 * somebody else's. So the helper had exactly one caller left, the saga's confirmation, and that
 * caller moved onto the outbox too; a "still used elsewhere" helper that is used nowhere is how
 * javadoc becomes fiction.
 *
 * <p>Deliberately byte-identical (modulo package) with its twin in microservice-memes — audited
 * 2026-07-07, and kept in step since. If you change one, change both.
 */
final class KafkaTracing {

    static final String HEADER = CorrelationIdFilter.HEADER;

    private KafkaTracing() {
    }
}
