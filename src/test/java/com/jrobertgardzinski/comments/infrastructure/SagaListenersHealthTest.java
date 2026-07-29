package com.jrobertgardzinski.comments.infrastructure;

import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The lamp the audit found missing: a listener loop that has stopped must show up somewhere, and
 * "somewhere" is readiness. Every case below is a real failure this service could not see before —
 * the container Spring Kafka stopped on an unrecoverable error, the loop wedged inside one record, and
 * (the one that costs the most) a service that never got a listener at all.
 */
class SagaListenersHealthTest {

    private static final Duration TOLERANCE = Duration.ofSeconds(150);
    private static final String LISTENER = "comments-purge-commands";

    private long nowNanos;
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);

    private SagaListenersHealth health() {
        return new SagaListenersHealth(registry, TOLERANCE, true, () -> nowNanos);
    }

    private MessageListenerContainer container(boolean running, boolean expectedState) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getListenerId()).thenReturn(LISTENER);
        when(container.isRunning()).thenReturn(running);
        if (!running) {
            when(container.isInExpectedState()).thenReturn(expectedState);
        }
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        return container;
    }

    private void elapse(Duration time) {
        nowNanos += time.toNanos();
    }

    /** What the container publishes once per idle interval when a poll comes back empty. */
    private void aCompletedPoll(SagaListenersHealth health) {
        health.onIdlePoll(new ListenerContainerIdleEvent(this, this, 10_000L, LISTENER, List.of(),
                mock(Consumer.class), false));
    }

    @Test
    @DisplayName("a running loop that keeps polling is UP — an idle topic is not a dead one")
    void a_polling_loop_is_up() {
        container(true, true);
        SagaListenersHealth health = health();

        // content-commands is silent for days between account deletions: the proof of life is the
        // completed poll, never a consumed record
        elapse(Duration.ofMinutes(20));
        aCompletedPoll(health);
        elapse(Duration.ofSeconds(10));

        Health reported = health.health();
        assertEquals(Status.UP, reported.getStatus());
        assertTrue(String.valueOf(reported.getDetails().get(LISTENER)).contains("polling"),
                "the detail names the container and its state: " + reported.getDetails());
    }

    @Test
    @DisplayName("a loop that stopped completing polls is DOWN — the stall the audit called invisible")
    void a_stalled_loop_is_down() {
        container(true, true);
        SagaListenersHealth health = health();
        aCompletedPoll(health);

        elapse(TOLERANCE.plusSeconds(1));

        Health reported = health.health();
        assertEquals(Status.DOWN, reported.getStatus(),
                "every account deletion in the portal stops closing while this container sleeps —"
                        + " readiness is where that has to show");
        assertTrue(String.valueOf(reported.getDetails().get(LISTENER)).contains("no completed poll"),
                reported.getDetails().toString());
    }

    @Test
    @DisplayName("a loop consuming without pause is UP — records are proof of life, not just empty polls")
    void a_draining_loop_is_up() {
        container(true, true);
        SagaListenersHealth health = health();
        aCompletedPoll(health);   // the last empty poll before the backlog arrives

        // Now the loop never polls empty again: it is draining the duplicate storm a broker outage
        // left behind, which is the scenario this service's own manifests plan for. No idle event is
        // published while records keep coming, and none was published for the busy TRANSITION either
        // (that one fires once, here it already has). The lamp used to hear nothing at all and call
        // the working loop dead after the tolerance — at replicas: 1 that takes the only pod out of
        // the Service exactly while the system is catching up.
        for (int minute = 0; minute < 10; minute++) {
            elapse(Duration.ofMinutes(1));
            health.recordDelivered(LISTENER);
        }
        elapse(Duration.ofSeconds(10));

        Health reported = health.health();
        assertEquals(Status.UP, reported.getStatus(),
                "a loop handing records to listeners is the least dead a loop can be: "
                        + reported.getDetails());
        assertTrue(String.valueOf(reported.getDetails().get(LISTENER)).contains("polling"),
                reported.getDetails().toString());
    }

    @Test
    @DisplayName("but a loop wedged inside one record is still DOWN — delivery, not arrival, is the beat")
    void a_loop_wedged_in_one_record_is_down() {
        container(true, true);
        SagaListenersHealth health = health();
        health.recordDelivered(LISTENER);

        // the record went in and never came out: no further delivery, so the marker stops
        elapse(TOLERANCE.plusSeconds(1));

        assertEquals(Status.DOWN, health.health().getStatus(),
                "stamping on records must not become a licence to hang on one of them");
    }

    @Test
    @DisplayName("a container Spring Kafka stopped is DOWN, and the details say it died abnormally")
    void a_stopped_container_is_down() {
        container(false, false);   // the consumer thread died: TopicAuthorizationException, an Error
        SagaListenersHealth health = health();

        Health reported = health.health();

        assertEquals(Status.DOWN, reported.getStatus());
        assertTrue(String.valueOf(reported.getDetails().get(LISTENER)).contains("abnormally"),
                "'stopped abnormally' and 'stopped' are different incidents: " + reported.getDetails());
    }

    @Test
    @DisplayName("no listener containers where listeners are expected is DOWN: commands reach nobody")
    void no_containers_is_down() {
        when(registry.getListenerContainers()).thenReturn(List.of());

        assertEquals(Status.DOWN, health().health().getStatus(),
                "a broker is configured, so an empty registry means the listeners never came up —"
                        + " the quietest way to break every account deletion");
    }

    @Test
    @DisplayName("a deployment without a broker is UP and says so — it takes no part in the saga")
    void listeners_disabled_is_up() {
        when(registry.getListenerContainers()).thenReturn(List.of());

        Health reported =
                new SagaListenersHealth(registry, TOLERANCE, false, () -> nowNanos).health();

        // the contributor exists even here, because Spring validates group membership at startup and
        // a readiness group naming an absent bean refuses to boot — so the lamp has to say "disabled"
        // rather than not exist
        assertEquals(Status.UP, reported.getStatus());
        assertTrue(String.valueOf(reported.getDetails().get("listeners")).contains("disabled"),
                reported.getDetails().toString());
    }

    @Test
    @DisplayName("a service still booting is not born unhealthy")
    void a_warming_up_service_is_up() {
        container(true, true);
        SagaListenersHealth health = health();

        // not one idle event yet — the container has been alive for two seconds
        elapse(Duration.ofSeconds(2));

        assertEquals(Status.UP, health.health().getStatus(),
                "the grace runs from this bean's creation, and with a 10s idle interval a live"
                        + " container stamps itself long before the tolerance runs out");
    }

    @Test
    @DisplayName("but a container that never reports at all goes DOWN once the tolerance is spent")
    void a_container_that_never_polls_goes_down() {
        container(true, true);
        SagaListenersHealth health = health();

        elapse(TOLERANCE.plusSeconds(1));

        assertEquals(Status.DOWN, health.health().getStatus(),
                "a listener that came up and never polled — no assignment, wedged from birth — is"
                        + " exactly as broken as one that stopped later");
    }
}
