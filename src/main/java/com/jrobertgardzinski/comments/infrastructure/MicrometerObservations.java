package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.comments.domain.Observation;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The one class in this service that knows what a metric is called.
 *
 * <p>Everything above it states facts ({@link Observation}); here they are given this month's
 * spelling — {@code comments_erasure_backlog}, {@code comments_kafka_records_dropped_total} — the
 * shape Prometheus wants and the labels an alert matches on. Swapping Micrometer for whatever comes
 * next is rewriting this file and nothing else, which is the entire reason the port exists.
 *
 * <p>Two translations neither of which is obvious from the fact alone: the backlog is a GAUGE
 * (the question is "how many right now", so it must fall back to zero by itself when a closure
 * finally lands), and a dropped command is a COUNTER labelled by topic (one increment is one saga
 * command never carried out, and an operator wants the running total).
 *
 * <p>A backlog that could NOT be read states nothing at all, so the gauge keeps its last value —
 * reporting zero would turn a failed database read into "the backlog is clear".
 */
@Component
@ConditionalOnProperty(name = "comments.observability-enabled", havingValue = "true", matchIfMissing = true)
class MicrometerObservations implements Observations<Observation> {

    private final AtomicLong erasureBacklog = new AtomicLong();
    private final MeterRegistry meters;

    MicrometerObservations(MeterRegistry meters) {
        this.meters = meters;
        meters.gauge("comments.erasure.backlog", erasureBacklog, AtomicLong::get);
    }

    @Override
    public void record(Observation observation) {
        switch (observation) {
            case Observation.ErasureBacklog backlog -> erasureBacklog.set(backlog.marked());
            case Observation.SagaCommandDropped dropped ->
                    meters.counter("comments.kafka.records.dropped", "topic", dropped.topic()).increment();
        }
    }
}
