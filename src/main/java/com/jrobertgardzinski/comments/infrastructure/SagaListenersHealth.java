package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.event.ListenerContainerNoLongerIdleEvent;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The lamp for this service's Kafka listener loop — the one signal a Spring saga participant was
 * missing while both Helidon participants had it ({@code PurgeCommandsConsumer.healthy()} in
 * microservice-user-collections, {@code KafkaLoop.healthy()} in microservice-offboarding).
 *
 * <p>Both of this service's listeners are watched, and they fail differently: the saga's
 * {@code content-commands} loop stops account deletions from closing, the cascade's
 * {@code memes-events} loop leaves deleted memes with their comment threads intact. The details name
 * whichever container stopped.
 *
 * <p><strong>The failure it exists to reveal.</strong> Spring Kafka stops a listener container on
 * unrecoverable trouble (a {@code TopicAuthorizationException}, an {@code Error} on the consumer
 * thread) and a container can also wedge inside one record. Neither shows anywhere else: the JVM
 * keeps serving comment threads, {@code docker compose ps} stays green, {@code up == 1} in Prometheus,
 * and every account deletion in the portal quietly stops closing — the orchestrator waits 120s,
 * re-commands three times, capitulates, and each leaver gets an apology mail instead of an erasure.
 * A green suite, a green stack and a green dashboard all pass through that failure without a trace,
 * which is exactly the blind spot the 26.07 audit named.
 *
 * <p><strong>Readiness, deliberately, not liveness.</strong> A stopped listener is not a dead
 * process: HTTP works, and a restart cannot fix the broker or the database that stopped it. This is
 * the same split the two Helidons arrived at ({@code /alive} versus {@code /health}) — restarting on
 * liveness heals a wedged process, restarting on a broken dependency only crash-loops. So this
 * contributor belongs to the {@code readiness} group (wired in {@code application.properties}) and
 * never to {@code liveness}.
 *
 * <p><strong>What it does NOT do: it is not a broker probe.</strong> With the broker unreachable the
 * container keeps polling and keeps publishing idle events, so this lamp stays green. That is a
 * choice, not an oversight. A broker outage IS reported — by collections' and offboarding's own
 * health, whose loops probe it — and readiness in k8s gates HTTP traffic: red-lighting every replica
 * because Kafka blinked would take every comment thread in the portal out of the Service for a fault
 * that has nothing to do with reading or writing comments. This lamp answers one question only: "is MY
 * listener loop still turning?"
 *
 * <p><strong>How the loop reports that it turned.</strong> From BOTH ends of the poll, because
 * either one alone lies about half the time:
 * <ul>
 *   <li>an empty poll publishes a {@link ListenerContainerIdleEvent} once per
 *       {@code spring.kafka.listener.idle-event-interval}, and records starting again publish a
 *       {@link ListenerContainerNoLongerIdleEvent}. This is what keeps a silent topic — and
 *       {@code content-commands} is silent for days at a time — from reading as a dead one;</li>
 *   <li>every record handed to a listener stamps the marker too, via the interceptor
 *       {@code SagaParticipantConfig} installs on the containers ({@link #recordDelivered}).</li>
 * </ul>
 *
 * <p>The second half was missing until 2026-07-29, and its absence inverted the lamp exactly when
 * the answer mattered. Idle events fire only on EMPTY polls; the no-longer-idle event fires only on
 * the idle→busy TRANSITION. A loop consuming without pause therefore publishes nothing at all, so
 * the marker stood still and the lamp went DOWN after the tolerance — while the loop was working.
 * The tolerance is 150s, derived from the retry budget of ONE record; draining a backlog after a
 * broker outage (the duplicate storm this service's own manifests plan for with
 * {@code strategy: Recreate}) exceeds it easily. At {@code replicas: 1} readiness then takes the
 * only pod out of the Service precisely while the system is healing, and it teaches the operator
 * that a red lamp means nothing. A lamp that is wrong when the system is under stress is worse than
 * no lamp.
 */
class SagaListenersHealth implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;
    private final Duration stallTolerance;

    /**
     * Whether this deployment has listeners at all ({@code comments.kafka-enabled}). The contributor is
     * registered either way, deliberately: Spring Boot validates group membership at startup, so a
     * {@code readiness} group naming a bean that only exists with a broker would refuse to start every
     * broker-less run — the whole test suite included. So the lamp exists always and says which of the
     * two things it is looking at.
     */
    private final boolean listenersExpected;

    /**
     * When each REPORTING container last completed a poll, keyed by the id the event carries. Written
     * by container threads, read by whoever probes health.
     *
     * <p>The reporting id is not the registered one, and finding that out cost a live stack: a
     * {@code @KafkaListener} is registered as a {@code ConcurrentMessageListenerContainer} (id
     * {@code comments-purge-commands}), but the thread that polls belongs to its CHILD, whose bean name is
     * the parent's plus {@code -<index>}. The child is what publishes the idle events, so a lookup by
     * the registered id alone matched nothing, the marker never moved, and the lamp went red 150
     * seconds after every boot with a perfectly healthy loop — the exact failure mode of a health
     * signal nobody tests: a green promise and a red light, both wrong. Hence {@link #markerFor},
     * which accepts the registered id or any child of it.
     */
    private final Map<String, Long> lastPollNanos = new ConcurrentHashMap<>();

    /**
     * Elapsed time only, from a monotonic source — the wall clock can step (NTP), and a step
     * backwards would fake a stall while a step forwards would hide one. Injected for the tests,
     * which age a marker instead of waiting out a real stall.
     */
    private final LongSupplier nanoTime;

    /**
     * When this indicator came into existence — the grace baseline for a container that has not
     * reported yet. A service still warming up must not be born unhealthy (the rule collections
     * wrote down for the same marker); with the idle interval at 10s a live container stamps itself
     * within seconds of starting, so the grace only ever covers the boot.
     */
    private final long startedNanos;

    SagaListenersHealth(KafkaListenerEndpointRegistry registry, Duration stallTolerance,
                        boolean listenersExpected) {
        this(registry, stallTolerance, listenersExpected, System::nanoTime);
    }

    SagaListenersHealth(KafkaListenerEndpointRegistry registry, Duration stallTolerance,
                        boolean listenersExpected, LongSupplier nanoTime) {
        this.registry = registry;
        this.stallTolerance = stallTolerance;
        this.listenersExpected = listenersExpected;
        this.nanoTime = nanoTime;
        this.startedNanos = nanoTime.getAsLong();
    }

    @EventListener
    void onIdlePoll(ListenerContainerIdleEvent idle) {
        lastPollNanos.put(idle.getListenerId(), nanoTime.getAsLong());
    }

    @EventListener
    void onRecordsAgain(ListenerContainerNoLongerIdleEvent busyAgain) {
        lastPollNanos.put(busyAgain.getListenerId(), nanoTime.getAsLong());
    }

    /**
     * The other half of the heartbeat: a record was delivered to a listener, which is proof the loop
     * polled and got something. Called on the container thread by the interceptor
     * {@code SagaParticipantConfig} installs, once per record and again once it has been handled —
     * so a busy loop stamps at least as often as an idle one does, and a loop wedged INSIDE one
     * record stops stamping and is still reported.
     *
     * <p>The id is the container's registered one, which {@link #markerFor} already accepts
     * alongside the {@code -0} child ids the idle events carry.
     */
    void recordDelivered(String containerId) {
        lastPollNanos.put(containerId, nanoTime.getAsLong());
    }

    /**
     * DOWN when any registered container is not running, or has not completed a poll within the
     * tolerance — and DOWN when there are no containers at all, because this bean only exists where
     * a broker does, so an empty registry means the listeners never came up.
     *
     * <p>The details name container ids, states and ages. Nothing from a record ever goes in here: a
     * purge command carries the leaver's address, and {@code /actuator/health} is the least private
     * page in the estate.
     */
    @Override
    public Health health() {
        if (!listenersExpected) {
            // no broker, no listeners, nothing to be unhealthy about: this deployment does not take
            // part in the saga at all (the whole listener is @ConditionalOnProperty on the same dial)
            return Health.up().withDetail("listeners", "disabled (comments.kafka-enabled=false)").build();
        }
        Collection<MessageListenerContainer> containers =
                registry == null ? List.of() : registry.getListenerContainers();
        if (containers.isEmpty()) {
            return Health.down()
                    .withDetail("listeners", "none registered — the saga's commands reach nobody")
                    .build();
        }
        Map<String, Object> states = new java.util.LinkedHashMap<>();
        boolean stalled = false;
        for (MessageListenerContainer container : containers) {
            String id = idOf(container);
            if (!container.isRunning()) {
                stalled = true;
                states.put(id, container.isInExpectedState()
                        ? "stopped" : "stopped abnormally — the consumer thread died");
                continue;
            }
            Duration since = Duration.ofNanos(Math.max(0,
                    nanoTime.getAsLong() - markerFor(id).orElse(startedNanos)));
            if (since.compareTo(stallTolerance) > 0) {
                stalled = true;
                states.put(id, "no completed poll for " + since.toSeconds()
                        + "s (tolerance " + stallTolerance.toSeconds() + "s)");
            } else if (markerFor(id).isEmpty()) {
                states.put(id, "starting, no completed poll reported yet (" + since.toSeconds()
                        + "s of the " + stallTolerance.toSeconds() + "s grace used)");
            } else {
                states.put(id, "polling, last completed poll " + since.toSeconds() + "s ago");
            }
        }
        return (stalled ? Health.down() : Health.up()).withDetails(states).build();
    }

    private String idOf(MessageListenerContainer container) {
        String id = container.getListenerId();
        return id != null ? id : container.toString();
    }

    /**
     * The freshest heartbeat belonging to a registered container: its own id, or any child of it
     * ({@code <id>-0}, {@code -1}, … — see {@link #lastPollNanos}).
     *
     * <p>FRESHEST rather than oldest, for concurrency above 1 (this service runs the default, one
     * child): a key left behind by a previous incarnation of the container must not raise a permanent
     * false alarm, and one lagging consumer inside a healthy group is a lag question, which Prometheus
     * answers better than a boolean lamp.
     */
    private java.util.Optional<Long> markerFor(String containerId) {
        return lastPollNanos.entrySet().stream()
                .filter(marker -> marker.getKey().equals(containerId)
                        || marker.getKey().startsWith(containerId + "-"))
                .map(Map.Entry::getValue)
                .max(Long::compare);
    }

    /**
     * Whether a real heartbeat has arrived for this container, as opposed to the boot grace standing
     * in for one. Package-private because it is what {@code ListenerHeartbeatTest} asserts: the lamp is
     * UP for the first {@code stallTolerance} either way, so "UP" alone cannot tell a loop that
     * reports from one that never will — which is precisely how the child-id mismatch above survived a
     * green suite.
     */
    boolean hasHeartbeatFrom(String containerId) {
        return markerFor(containerId).isPresent();
    }
}
