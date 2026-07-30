package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.voting.VoteDirection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The dialect bet, settled on the real database: the standard-SQL MERGE upserts and the V3
 * foreign key against PostgreSQL 18 itself (postgres:18-alpine via Testcontainers, the same
 * Flyway migrations as production), not only H2's PostgreSQL mode. Three things H2 could not
 * prove:
 *
 * <ul>
 *   <li>PG's MERGE speaks the exact SQL the adapters send (upsert semantics: one row, last
 *       direction wins);</li>
 *   <li>PG's MERGE really does raise a unique violation when two first-time casts race WHEN NOT
 *       MATCHED — forced here with two transactions, the review's probe: tx1 holds an
 *       uncommitted insert, tx2 runs the same MERGE and, once tx1 commits, gets the violation
 *       Spring maps to {@link DuplicateKeyException} — and that the adapter's catch+retry then
 *       recovers on the second pass;</li>
 *   <li>a vote for a nonexistent comment is the FOREIGN KEY violation (SQLSTATE 23503), a
 *       {@link DataIntegrityViolationException} that is NOT a DuplicateKeyException — the
 *       distinction the adapter's UnknownComment translation rests on.</li>
 * </ul>
 *
 * Skipped where docker is absent (the same pattern as memes' S3ObjectStoreTest); the H2 suite
 * (JdbcPersistenceTest) keeps covering the adapters on every dockerless run.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresDialectTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static DriverManagerDataSource dataSource;
    static JdbcClient jdbc;
    static JdbcCommentVotes votes;
    static JdbcCommentModeration moderation;

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        // DriverManagerDataSource: a fresh connection per statement, so the concurrency probes
        // below really are separate PG sessions, not one pooled connection talking to itself
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        votes = new JdbcCommentVotes(jdbc);
        moderation = new JdbcCommentModeration(jdbc);
    }

    @Test
    @DisplayName("PG16: cast is an upsert — two casts are one row, a direction change stays one row")
    void merge_upsert_is_one_row_on_postgres() {
        String commentId = savedComment();

        votes.cast(commentId, "voter@example.com", VoteDirection.UP);
        votes.cast(commentId, "voter@example.com", VoteDirection.UP);      // same again — no PK clash
        assertEquals(1, voteRows(commentId));

        votes.cast(commentId, "voter@example.com", VoteDirection.DOWN);    // switch — still one row
        assertEquals(1, voteRows(commentId));
        assertEquals(Optional.of(VoteDirection.DOWN),
                votes.voteOf(commentId, "voter@example.com"));
    }

    @Test
    @DisplayName("PG16: hiding twice is an upsert on the real database too")
    void flag_merge_upsert_is_one_row_on_postgres() {
        String commentId = savedComment();

        moderation.setHidden(commentId, true);
        moderation.setHidden(commentId, true);
        assertTrue(moderation.isHidden(commentId));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM comment_flags WHERE comment_id = ?")
                .param(commentId).query(Long.class).single());
    }

    @Test
    @DisplayName("PG16: a concurrent first vote makes MERGE raise the violation Spring maps to"
            + " DuplicateKeyException — the exact exception the catch+retry is written for")
    void concurrent_first_vote_raises_a_duplicate_key_on_postgres() throws Exception {
        String commentId = savedComment();
        String voter = "raced@example.com";

        ExecutorService secondSession = Executors.newSingleThreadExecutor();
        try (Connection tx1 = dataSource.getConnection()) {
            // tx1: the review's probe — an UNCOMMITTED first vote holds the PK index entry
            tx1.setAutoCommit(false);
            insertVote(tx1, commentId, voter, "UP");

            // tx2 (its own connection): the same MERGE takes WHEN NOT MATCHED, hits tx1's
            // in-doubt index entry and blocks; once tx1 commits it MUST become the unique
            // violation (23505), not a silent WHEN MATCHED
            Future<Throwable> mergeOutcome = secondSession.submit(() -> {
                try {
                    votes.mergeVote(commentId, voter, VoteDirection.DOWN);
                    return null;
                } catch (RuntimeException raised) {
                    return raised;
                }
            });
            awaitALockWaiter();
            tx1.commit();

            // bounded get(): if the MERGE somehow never unblocks after tx1's commit, fail HERE
            // with a readable TimeoutException instead of hanging until surefire's global kill
            Throwable raised = mergeOutcome.get(15, TimeUnit.SECONDS);
            assertInstanceOf(DuplicateKeyException.class, raised,
                    "PG's MERGE must lose the WHEN NOT MATCHED race as the unique violation"
                            + " Spring maps to DuplicateKeyException — the H2 suite can only"
                            + " simulate this, the real dialect must actually do it");
        } finally {
            secondSession.shutdownNow();
        }

        // and the adapter's answer to that exception recovers: a retried cast lands WHEN
        // MATCHED and records the later direction
        votes.cast(commentId, voter, VoteDirection.DOWN);
        assertEquals(1, voteRows(commentId));
        assertEquals(Optional.of(VoteDirection.DOWN), votes.voteOf(commentId, voter));
    }

    @Test
    @DisplayName("PG16: cast() catch+retry absorbs the race end to end — first pass loses,"
            + " retry lands WHEN MATCHED")
    void cast_retry_recovers_from_the_race_on_postgres() throws Exception {
        String commentId = savedComment();
        String voter = "retried@example.com";
        AtomicInteger mergePasses = new AtomicInteger();
        var instrumented = new JdbcCommentVotes(jdbc) {
            volatile Throwable firstPassFailure;

            @Override
            void mergeVote(String id, String v, VoteDirection direction) {
                mergePasses.incrementAndGet();
                try {
                    super.mergeVote(id, v, direction);
                } catch (RuntimeException raised) {
                    if (firstPassFailure == null) {
                        firstPassFailure = raised;
                    }
                    throw raised;
                }
            }
        };

        ExecutorService secondSession = Executors.newSingleThreadExecutor();
        try (Connection tx1 = dataSource.getConnection()) {
            tx1.setAutoCommit(false);
            insertVote(tx1, commentId, voter, "UP");     // the concurrent winner, uncommitted

            Future<?> cast = secondSession.submit(
                    () -> instrumented.cast(commentId, voter, VoteDirection.DOWN));
            awaitALockWaiter();
            tx1.commit();
            try {
                // must NOT throw: the retry absorbed the race — bounded, so a cast() that
                // never returns fails as a readable TimeoutException, not a surefire hang
                cast.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException surfaced) {
                fail("cast() must survive losing the MERGE race on real PG, but threw: "
                        + surfaced.getCause());
            }

            assertEquals(2, mergePasses.get(), "one losing pass plus exactly one retry");
            assertInstanceOf(DuplicateKeyException.class, instrumented.firstPassFailure,
                    "the first pass must have lost the race as a DuplicateKeyException");
        } finally {
            secondSession.shutdownNow();
        }

        assertEquals(1, voteRows(commentId), "the race must still end as one row");
        assertEquals(Optional.of(VoteDirection.DOWN), votes.voteOf(commentId, voter),
                "the retry lands WHEN MATCHED and the later direction wins");
    }

    @Test
    @DisplayName("PG16: a vote for a vanished comment is the 23503 FK violation — a"
            + " DataIntegrityViolation that is NOT a DuplicateKey, and cast() reads it as such")
    void fk_violation_is_integrity_not_duplicate_on_postgres() {
        String noSuchComment = UUID.randomUUID().toString();

        DataIntegrityViolationException fkViolation =
                assertThrows(DataIntegrityViolationException.class,
                        () -> votes.mergeVote(noSuchComment, "voter@example.com", VoteDirection.UP));
        assertFalse(fkViolation instanceof DuplicateKeyException,
                "23503 must translate to a plain DataIntegrityViolationException — the"
                        + " adapter tells the FK apart from the PK race by exactly this");

        // and through the adapter's translation: the FK refusal reads as "no such comment"
        assertThrows(com.jrobertgardzinski.comments.application.CommentVotes.UnknownComment.class,
                () -> votes.cast(noSuchComment, "voter@example.com", VoteDirection.UP));
    }

    // ---- the harness ----

    private static String savedComment() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO comments (id, meme_id, author, content, created_at)"
                        + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)")
                .params(id, UUID.randomUUID().toString(), "author@example.com", "under pg test")
                .update();
        return id;
    }

    private static void insertVote(Connection tx, String commentId, String voter, String direction)
            throws Exception {
        try (PreparedStatement insert = tx.prepareStatement(
                "INSERT INTO comment_votes (comment_id, voter, direction) VALUES (?, ?, ?)")) {
            insert.setString(1, commentId);
            insert.setString(2, voter);
            insert.setString(3, direction);
            insert.executeUpdate();
        }
    }

    /**
     * Wait (briefly) until some other session is blocked on a lock — the sign that tx2's MERGE
     * has reached the in-doubt index entry, so committing tx1 now forces the unique violation
     * instead of letting a late MERGE see the committed row and slide into WHEN MATCHED.
     *
     * <p>GUARD: the probe counts lock waiters across the WHOLE cluster (pg_stat_activity is not
     * filtered down to this test's sessions), so it assumes the tests of this class run
     * sequentially — the JUnit default. Under parallel execution (junit.jupiter.execution.parallel)
     * another test's waiter would satisfy the probe and commit tx1 too early: a false positive
     * where the MERGE slides into WHEN MATCHED and the assertion fails flakily.</p>
     */
    private static void awaitALockWaiter() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Long waiters = jdbc.sql("SELECT COUNT(*) FROM pg_stat_activity"
                            + " WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiters > 0) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for the concurrent MERGE to block on the uncommitted insert");
    }

    private static long voteRows(String commentId) {
        return jdbc.sql("SELECT COUNT(*) FROM comment_votes WHERE comment_id = ?")
                .param(commentId).query(Long.class).single();
    }
}
