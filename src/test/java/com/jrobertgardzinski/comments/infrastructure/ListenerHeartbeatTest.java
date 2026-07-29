package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
