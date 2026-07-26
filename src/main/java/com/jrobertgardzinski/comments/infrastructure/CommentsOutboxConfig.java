package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.outbox.OutboxDials;
import com.jrobertgardzinski.outbox.OutboxRepublisher;
import com.jrobertgardzinski.outbox.OutboxTable;
import com.jrobertgardzinski.outbox.RepublisherSettings;
import com.jrobertgardzinski.outbox.spring.ScheduledOutboxRepublisher;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires the shared outbox library into this service: the store, the send, and the schedule that
 * makes the guarantee true. Everything the library refuses to decide for its host is decided here —
 * the table name, the property names, whether a scheduler exists at all.
 *
 * <p>All of it hangs off {@code comments.kafka-enabled}, like the two Kafka listeners: without a
 * broker there is nothing to be durable towards, {@link NoopCommentEvents} takes the port, no row is
 * ever written, and no scheduler thread wakes up every 15 seconds in a test JVM to poll a table.
 * That is also why {@code @EnableScheduling} sits HERE and not in the library — switching on a
 * framework subsystem inside somebody else's application is not a library's business.
 *
 * <p>The {@code Clock} it needs is declared in {@link CommentsConfig}, unconditionally, even though
 * the outbox is currently its only consumer: a bean graph whose SHAPE depends on whether a broker
 * exists is one more difference between the environment that is tested and the one that runs.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "comments.kafka-enabled", havingValue = "true")
class CommentsOutboxConfig {

    /**
     * The retention dial as the OPERATOR spells it (env {@code COMMENTS_OUTBOX_RETENTION_HOURS}).
     * The library validates the value but cannot know this string — it owns no configuration
     * namespace — so the name travels with the value into {@link OutboxDials#retentionHours}, which
     * is what puts it into the message the operator reads when they set it to zero.
     */
    static final String RETENTION_PROPERTY = "comments.outbox.retention-hours";

    /** The table V4 creates, from the library's own {@link OutboxTable#ddl()}. */
    static final String TABLE = "comment_events_outbox";

    @Bean
    SpringOutbox commentEventsOutbox(DataSource dataSource, Clock clock,
                                     KafkaTemplate<String, String> kafka) {
        return new SpringOutbox(dataSource, OutboxTable.named(TABLE), clock,
                new KafkaCommentDispatch(kafka));
    }

    @Bean
    CommentEvents commentEvents(SpringOutbox commentEventsOutbox, ObjectMapper mapper) {
        return new KafkaCommentEvents(commentEventsOutbox, mapper);
    }

    @Bean
    ScheduledOutboxRepublisher commentEventsOutboxRepublisher(
            SpringOutbox commentEventsOutbox,
            @Value("${comments.outbox.retention-hours:24}") long retentionHours) {
        return new ScheduledOutboxRepublisher(republisher(commentEventsOutbox, retentionHours));
    }

    /**
     * The republisher this service runs, as a plain factory rather than only a {@code @Bean} body —
     * so a test can pin what happens to a BROKEN dial without booting a context that refuses to
     * start (and without going through a {@code @Configuration} proxy, which would hand back the
     * singleton and ignore the argument).
     *
     * <p>{@link RepublisherSettings#defaults} on purpose, rather than numbers of this service's own:
     * a 30s minimum age (comfortably above the 30s delivery timeout below, so a re-send never races
     * an attempt still in flight), 5s confirmation patience — the same 5s as {@code max.block.ms},
     * one "how long do we wait for a broker" number for the service — 500-row retention batches at
     * most 4 per pass, and 500 rows re-sent per pass. Sharing the defaults with microservice-memes
     * is the whole point of the shared library: two services, one answer.
     */
    static OutboxRepublisher republisher(SpringOutbox outbox, long retentionHours) {
        return new OutboxRepublisher(outbox.outbox(), outbox.publisher(),
                RepublisherSettings.defaults(
                        OutboxDials.retentionHours(RETENTION_PROPERTY, retentionHours)));
    }
}
