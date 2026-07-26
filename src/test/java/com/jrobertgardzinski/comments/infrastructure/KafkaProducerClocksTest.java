package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A configuration pin: it asserts that properties the CODE'S PROMISES depend on actually reach the
 * component that honours them — here, the producer's blocking clocks. The twin of
 * microservice-memes' {@code KafkaProducerClocksTest}, added in round 10 because this service had
 * NEITHER the dials nor the pin, which was written down as a risk when the fire-and-forget
 * announcement shipped in round 9.
 *
 * <p>What depends on them: {@link KafkaCommentDispatch} promises the shared outbox library that the
 * calling thread does not wait for the broker — one of the two halves of the library's
 * {@code Dispatch} contract, and the half no library can verify for itself, because it holds no
 * broker client and can therefore neither see nor set a producer property. That is why property (h)
 * of the outbox design is unavoidably the service's.
 *
 * <p>Why it is not merely tidiness: {@code KafkaTemplate.send()} is asynchronous only once the record
 * reaches the accumulator, and reaching it blocks the CALLING thread on the metadata fetch (a
 * leaderless topic has none) and on buffer space for up to {@code max.block.ms} — Kafka's own default
 * being 60s. The calling thread here is the Kafka LISTENER thread of the {@code comments} group,
 * since the announcement is parked on the commit of the transaction {@link MemesEventsListener}
 * opens. A minute per deletion on that thread walks straight into the 300s
 * {@code max.poll.interval.ms} that turns a broker outage into a consumer-group rebalance — which
 * would stop this service dropping threads at all: an availability problem grown out of a delivery
 * problem.
 *
 * <p><strong>Why this is two assertions where memes needs one.</strong> A test in this repository
 * cannot read the deployed {@code application.properties} through Spring at all:
 * {@code src/test/resources/application.properties} sits at the same classpath location, and Spring
 * Boot loads the FIRST {@code classpath:/application.properties} it finds — so in a test the main
 * file is not merely overridden key by key, it is never read. (microservice-memes has no test
 * properties file, which is the only reason its single-assertion pin works there.) Asserting the
 * running context's values would therefore have pinned the TEST file — a green test about a file no
 * container ever sees, which is worse than no test. So the two halves are checked separately:
 *
 * <ol>
 *   <li>the file that actually ships sets the three dials, env override and default included;</li>
 *   <li>Spring really carries {@code spring.kafka.producer.properties.*} through to the producer, so
 *       the keys in that file are the keys that take effect.</li>
 * </ol>
 *
 * <p>CAVEAT, the same one memes' pin carries: these are env-overridable at DEPLOY time
 * (COMMENTS_KAFKA_MAX_BLOCK_MS and friends), where no test runs. Which is why half 1 pins the
 * override's NAME as well — a deployment manifest setting the wrong variable name would leave the
 * default in place, and the default is the value this service promises.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class},
        // sentinels, not the real numbers: half 2 is about the PATH from a property to the producer,
        // and values that could also come from somewhere else would not prove a path
        properties = {
                "spring.kafka.producer.properties.max.block.ms=4321",
                "spring.kafka.producer.properties.delivery.timeout.ms=8765",
                "spring.kafka.producer.properties.request.timeout.ms=6543"})
class KafkaProducerClocksTest {

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    private static final Path DEPLOYED_PROPERTIES = Path.of("src/main/resources/application.properties");

    @Autowired
    ProducerFactory<?, ?> producerFactory;

    @Test
    @DisplayName("the shipped configuration pins max.block.ms to 5s, and delivery/request to the cascade's clocks")
    void the_deployed_properties_pin_the_blocking_clocks() throws IOException {
        Properties deployed = new Properties();
        try (InputStream file = Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }

        assertEquals("${COMMENTS_KAFKA_MAX_BLOCK_MS:5000}",
                deployed.getProperty("spring.kafka.producer.properties.max.block.ms"),
                "the announcing thread (a cascade hop runs on the Kafka listener thread) may wait"
                        + " this long per event and no longer — Kafka's 60s default would make"
                        + " KafkaCommentDispatch's half of the Dispatch contract false");
        assertEquals("${COMMENTS_KAFKA_DELIVERY_TIMEOUT_MS:30000}",
                deployed.getProperty("spring.kafka.producer.properties.delivery.timeout.ms"),
                "the same clock microservice-memes, offboarding's KafkaLoop and collections'"
                        + " PurgeCommandsConsumer run on — one cascade, one set of clocks");
        assertEquals("${COMMENTS_KAFKA_REQUEST_TIMEOUT_MS:15000}",
                deployed.getProperty("spring.kafka.producer.properties.request.timeout.ms"),
                "likewise");
    }

    @Test
    @DisplayName("a spring.kafka.producer.properties.* dial really reaches the producer's own configuration")
    void the_producer_gets_what_the_properties_say() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertEquals("4321", String.valueOf(config.get("max.block.ms")),
                "the prefix in application.properties must be the one Spring strips and forwards —"
                        + " a misspelt prefix would leave Kafka's own defaults in place, silently");
        assertEquals("8765", String.valueOf(config.get("delivery.timeout.ms")));
        assertEquals("6543", String.valueOf(config.get("request.timeout.ms")));
    }
}
