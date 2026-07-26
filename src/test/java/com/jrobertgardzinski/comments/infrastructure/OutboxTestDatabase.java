package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.outbox.OutboxTable;
import com.jrobertgardzinski.outbox.spring.SpringOutbox;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A one-table database for the cascade's unit tests: the outbox, on its own in-memory H2, with a
 * real {@link DataSourceTransactionManager} over it.
 *
 * <p>The cascade tests used to need no database at all — the announcement was a
 * {@code kafka.send()} and nothing more. Round 10 gave it a transactional outbox, and a fake outbox
 * would defeat the purpose of the round: the properties worth testing here ("a rollback lets nothing
 * out", "a failed send leaves the row for the republisher") are properties OF a real transaction and
 * a real row. So the tests get a real one, and it costs the twenty lines below.
 *
 * <p>The table is created by executing {@link OutboxTable#ddl()} — the same string
 * {@code V4__comment_events_outbox.sql} was copied from, so a test cannot pass against a shape the
 * migration does not produce. PostgreSQL mode with lower-cased identifiers, matching both the real
 * Postgres and the H2 the Spring suites run on.
 */
final class OutboxTestDatabase {

    /** A fresh database per fixture: no test can see another's rows, whatever the order. */
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final DataSource dataSource;
    private final SpringOutbox outbox;
    private final TransactionTemplate tx;

    private OutboxTestDatabase(DataSource dataSource, SpringOutbox outbox, TransactionTemplate tx) {
        this.dataSource = dataSource;
        this.outbox = outbox;
        this.tx = tx;
    }

    static OutboxTestDatabase with(KafkaTemplate<String, String> kafka) {
        return with(kafka, Clock.systemUTC());
    }

    static OutboxTestDatabase with(KafkaTemplate<String, String> kafka, Clock clock) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:comments-outbox-" + DATABASES.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        OutboxTable table = OutboxTable.named(CommentsOutboxConfig.TABLE);
        create(dataSource, table.ddl());
        return new OutboxTestDatabase(dataSource,
                // the same construction CommentsOutboxConfig performs, mock broker aside
                new SpringOutbox(dataSource, table, clock, new KafkaCommentDispatch(kafka)),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static void create(DataSource dataSource, String ddl) {
        try (Connection connection = dataSource.getConnection();
             Statement create = connection.createStatement()) {
            create.execute(ddl);
        } catch (SQLException impossible) {
            throw new IllegalStateException("could not create the outbox table from the library's"
                    + " own DDL — the shape V4 was copied from no longer executes", impossible);
        }
    }

    SpringOutbox outbox() {
        return outbox;
    }

    TransactionTemplate tx() {
        return tx;
    }

    /** Whether the row of an event is still an outstanding obligation. */
    boolean isPending(String memeId) {
        return "false".equals(scalar("SELECT published FROM " + CommentsOutboxConfig.TABLE
                + " WHERE event_key = '" + memeId + "'"));
    }

    boolean isPublished(String memeId) {
        return "true".equals(scalar("SELECT published FROM " + CommentsOutboxConfig.TABLE
                + " WHERE event_key = '" + memeId + "'"));
    }

    int rows() {
        return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + CommentsOutboxConfig.TABLE));
    }

    String cidOf(String memeId) {
        return scalar("SELECT cid FROM " + CommentsOutboxConfig.TABLE + " WHERE event_key = '"
                + memeId + "'");
    }

    /**
     * Ages a row past the republisher's minimum age. In production the 30s pass by themselves; a
     * test that waited for them would be a test nobody runs.
     */
    void backdateBeyondMinAge(String memeId) {
        execute("UPDATE " + CommentsOutboxConfig.TABLE
                + " SET created_at = DATEADD('SECOND', -60, created_at) WHERE event_key = '"
                + memeId + "'");
    }

    private String scalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement query = connection.createStatement();
             var rows = query.executeQuery(sql)) {
            return rows.next() ? String.valueOf(rows.getObject(1)) : null;
        } catch (SQLException failed) {
            throw new IllegalStateException(sql, failed);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement update = connection.createStatement()) {
            update.executeUpdate(sql);
        } catch (SQLException failed) {
            throw new IllegalStateException(sql, failed);
        }
    }
}
