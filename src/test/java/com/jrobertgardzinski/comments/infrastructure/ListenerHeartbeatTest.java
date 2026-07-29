package com.jrobertgardzinski.comments.infrastructure;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one thing the lamp's unit test cannot check: that the running loop's heartbeat actually REACHES
 * it. This test boots the real application with listeners enabled and waits for a real
 * {@code ListenerContainerIdleEvent} to move the real marker.
 *
 * <p>It exists because that wiring was wrong and everything else stayed green. A
 * {@code @KafkaListener} is registered as a {@code ConcurrentMessageListenerContainer} under the id
 * given in the annotation, but the thread that polls belongs to its CHILD container, whose bean name
 * carries a {@code -0} suffix — and the child is what publishes the idle events. Looking the marker up
 * by the registered id alone found nothing, ever, so the lamp fell back to its boot grace and turned
 * every healthy instance red exactly {@code stall-tolerance} seconds after start. Unit tests passed
 * (they publish the event with the id they then query), the suite passed, the stack came up green —
 * and the readiness probe of a perfectly healthy service went 503 two and a half minutes later, on a
 * live stack. That is the audit's own theme, scored against this very package: a signal that proves
 * a process exists proves nothing about the function.
 *
 * <p>No broker is needed and none is started: a consumer polling a bootstrap address with nothing
 * behind it gets empty results, which is exactly the situation the heartbeat is FOR — an idle topic
 * must read as alive. The idle interval is shortened to a second so the wait is a second, not ten.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class}, properties = {
        "comments.kafka-enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.idle-event-interval=1s"})
class ListenerHeartbeatTest {

    /** The id {@link PurgeCommandsListener} gives its container (the cascade's is watched too). */
    private static final String LISTENER = "comments-purge-commands";

    @Autowired
    SagaListenersHealth lamp;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> heartbeat;

    @Test
    @DisplayName("the polling loop's heartbeat reaches the lamp — the registered id and the reporting one are not the same string")
    void the_loop_reports_its_polls_to_the_lamp() throws Exception {
        Instant giveUpAt = Instant.now().plus(Duration.ofSeconds(30));
        while (!lamp.hasHeartbeatFrom(LISTENER) && Instant.now().isBefore(giveUpAt)) {
            Thread.sleep(200);
        }

        assertTrue(lamp.hasHeartbeatFrom(LISTENER),
                "no idle event ever reached the indicator: the lamp is running on its boot grace and"
                        + " will turn red on a healthy service once that grace expires");
        assertEquals(Status.UP, lamp.health().getStatus());
        assertTrue(String.valueOf(lamp.health().getDetails().get(LISTENER)).contains("polling"),
                "and the detail reports a real poll, not the grace: " + lamp.health().getDetails());
    }

    /**
     * The same question for the OTHER half of the heartbeat, and it has to be asked separately: idle
     * events say the loop polled and got nothing, records say it polled and got something, and a
     * loop that is busy publishes only the second kind. If the interceptor is not on the container,
     * {@code recordDelivered} is a method nobody calls — which is how the lamp came to report a
     * draining backlog as a dead listener.
     */
    @Test
    @DisplayName("every registered container carries the record heartbeat")
    @SuppressWarnings("unchecked")
    void the_containers_carry_the_record_heartbeat() {
        assertFalse(registry.getListenerContainers().isEmpty(), "no containers to check");

        // Not merely "an interceptor is installed" — THE heartbeat one. Boot's factory sets a
        // recordInterceptor of its own from any RecordInterceptor bean, and a non-null check would
        // pass just as happily with someone else's, leaving recordDelivered a method nobody calls.
        // The customizer's interceptor is an anonymous class, so its Class identifies it exactly.
        ConcurrentMessageListenerContainer<Object, Object> probe =
                mock(ConcurrentMessageListenerContainer.class);
        when(probe.getListenerId()).thenReturn("probe");
        ArgumentCaptor<RecordInterceptor<Object, Object>> expected =
                ArgumentCaptor.forClass(RecordInterceptor.class);
        heartbeat.configure(probe);
        verify(probe).setRecordInterceptor(expected.capture());

        registry.getListenerContainers().forEach(container -> {
            RecordInterceptor<?, ?> installed =
                    ((ConcurrentMessageListenerContainer<?, ?>) container).getRecordInterceptor();
            assertNotNull(installed,
                    container.getListenerId() + " has no record interceptor, so a loop that is busy"
                            + " reports nothing at all and the lamp runs on empty polls alone");
            assertSame(expected.getValue().getClass(), installed.getClass(),
                    container.getListenerId() + " carries some OTHER interceptor: whatever it does,"
                            + " it is not stamping this lamp");
        });
    }

    /**
     * And that the interceptor stamps the marker under the CONTAINER'S OWN id. The lamp looks a
     * marker up by the registered id (or a {@code -0} child of it); an interceptor that stamped some
     * other string would be installed, invoked, and still leave the lamp on its boot grace — the
     * exact shape of the child-id bug this file was written for, one layer down.
     */
    @Test
    @DisplayName("and that interceptor stamps the marker under the container's own id")
    @SuppressWarnings("unchecked")
    void the_record_heartbeat_names_its_container() {
        String unknownToAnyRealContainer = "a-container-of-its-own";
        assertFalse(lamp.hasHeartbeatFrom(unknownToAnyRealContainer), "precondition");

        ConcurrentMessageListenerContainer<Object, Object> container =
                mock(ConcurrentMessageListenerContainer.class);
        when(container.getListenerId()).thenReturn(unknownToAnyRealContainer);
        ArgumentCaptor<RecordInterceptor<Object, Object>> installed =
                ArgumentCaptor.forClass(RecordInterceptor.class);

        // the customizer is a bean, so this drives the very object Spring hands the factory, and it
        // stamps the very lamp the application serves health from
        heartbeat.configure(container);
        verify(container).setRecordInterceptor(installed.capture());
        installed.getValue().intercept(new ConsumerRecord<>("content-commands", 0, 0L, "k", "v"),
                mock(Consumer.class));

        assertTrue(lamp.hasHeartbeatFrom(unknownToAnyRealContainer),
                "a delivered record must advance the marker of the container that delivered it");
    }
}
