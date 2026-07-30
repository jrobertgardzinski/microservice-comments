package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A configuration pin, the same species as {@link KafkaProducerClocksTest}: it asserts that a
 * property the CODE'S PROMISES depend on actually reaches the component that honours it — here, the
 * consumer's starting position for a group with no committed offset.
 *
 * <p>What depends on it: Kafka's own default is {@code latest}, and {@code latest} loses records
 * without a trace. A consumer group that has not committed an offset yet — a fresh environment, or
 * Kafka switched on after this service ran without it — starts AFTER whatever the topic already
 * holds, so purge commands and MEME_DELETED hops published before the group's first poll are never
 * seen: no log line, no metric, a saga that times out and threads that outlive their memes for no
 * visible reason. {@code earliest} walks the retained history instead, and both listeners being
 * idempotent ({@code PurgeCommandsListener}, {@code MemesEventsListener}), repeating it costs
 * nothing — the reasoning microservice-user-collections wrote down next to the very same setting.
 *
 * <p><strong>Why this is two assertions where memes needs one</strong> — the same split as
 * {@link KafkaProducerClocksTest}, for the same reason: this repository has a
 * {@code src/test/resources/application.properties}, which REPLACES the shipped file on the test
 * classpath rather than merging with it, so asserting the running context's value would pin the
 * TEST file — a green test about a file no container ever sees. So the two halves are checked
 * separately: the file that ships sets the dial, and Spring really carries
 * {@code spring.kafka.consumer.auto-offset-reset} through to the consumer, so the key in that file
 * is the key that takes effect.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class},
        // a sentinel, not the real value: half 2 is about the PATH from the property to the
        // consumer, and a value that could also come from somewhere else would not prove a path
        properties = "spring.kafka.consumer.auto-offset-reset=none")
class KafkaConsumerOffsetResetTest {

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    private static final Path DEPLOYED_PROPERTIES = Path.of("src/main/resources/application.properties");

    @Autowired
    ConsumerFactory<?, ?> consumerFactory;

    @Test
    @DisplayName("the shipped configuration pins auto-offset-reset to earliest — a group with no committed offset must read history, not skip it")
    void the_deployed_properties_pin_the_offset_reset() throws IOException {
        Properties deployed = new Properties();
        try (InputStream file = Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }

        assertEquals("earliest",
                deployed.getProperty("spring.kafka.consumer.auto-offset-reset"),
                "with Kafka's latest default, a consumer group with no committed offset starts"
                        + " AFTER the records already on the topic — purge commands and MEME_DELETED"
                        + " hops published before this service's first poll would be skipped"
                        + " without a trace");
    }

    @Test
    @DisplayName("a spring.kafka.consumer.auto-offset-reset dial really reaches the consumer's own configuration")
    void the_consumer_gets_what_the_properties_say() {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();

        assertEquals("none", String.valueOf(config.get("auto.offset.reset")),
                "the key in application.properties must be the one Spring maps and forwards —"
                        + " a misspelt key would leave Kafka's latest default in place, silently");
    }
}
