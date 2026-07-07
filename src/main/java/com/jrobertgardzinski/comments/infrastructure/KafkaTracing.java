package com.jrobertgardzinski.comments.infrastructure;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

/**
 * Carries the correlation id across the Kafka boundary, so the async saga and cascade hops keep
 * the same {@code cid} as the request that started them (matching {@link CorrelationIdFilter} on
 * the HTTP edge). Producers stamp the header from MDC; consumers restore it into MDC.
 *
 * <p>Deliberately byte-identical (modulo package) with its twin in microservice-memes — audited
 * 2026-07-07. If you change one, change both.
 */
final class KafkaTracing {

    static final String HEADER = CorrelationIdFilter.HEADER;

    private KafkaTracing() {
    }

    /** A record carrying the current request's cid (if any) as the correlation header. */
    static ProducerRecord<String, String> withCid(String topic, String key, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (cid != null) {
            record.headers().add(HEADER, cid.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
