package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.purge.PurgeRule;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Where the account-deletion saga's own closure and the MEME_DELETED cascade can meet on the SAME
 * row, forced onto the real database rather than argued about: a leaver's own comment (marked by a
 * running saga) sits under a meme that has its own thread, and the saga's {@link PurgeUserComments}
 * and the cascade's {@link DeleteThread} run as two REAL, concurrent Postgres transactions against
 * it — no Kafka, no orchestrator, no decision about monolith vs. microservices. Both use cases are
 * transport-agnostic by design (the same reason {@code MemesClosureParticipant} in
 * microservice-memes is its own module and not a class in an adapter), which is what lets a
 * persistence-level test answer the question the transport question cannot: does the storage layer
 * survive the two flows landing on one row at once, whichever carrier eventually delivers them?
 *
 * <p>The fixture crosses the two transactions' lock order on purpose, matching the interleaving a
 * review of the two use cases predicted rather than one this test invented: {@link PurgeUserComments}
 * takes the leaver's OWN votes first ({@code CommentVotes.purgeVoter}) and the marked comment's
 * votes second; {@link DeleteThread} walks the thread oldest-first ({@code allUnder}'s
 * {@code ORDER BY created_at}) and takes each comment's votes in that order. With the leaver's own
 * comment first in the thread, and the leaver having voted on the SECOND comment, the two
 * transactions reach for the same two {@code comment_votes} rows in opposite order — the textbook
 * condition for Postgres' own deadlock detector to fire (SQLSTATE 40P01), not for either side to
 * hang.
 *
 * <p>Skipped where Docker is absent, like {@link PostgresDialectTest}.
 */
@Epic("Saga")
@Feature("Meme-deleted cascade")
@Story("The saga's closure and the cascade race on the same comment")
@Testcontainers(disabledWithoutDocker = true)
class MarkedCommentRaceTest {

    private static final String LEAVER = "leaver@example.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static DriverManagerDataSource dataSource;
    static JdbcClient jdbc;
    static PlatformTransactionManager txManager;
    static CommentRepository commentRepository;
    static CommentErasure commentErasure;

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        // one real connection per Spring transaction (DataSourceTransactionManager binds it to the
        // calling thread), which is exactly the guarantee this test spends its whole budget on
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
        commentRepository = new JdbcCommentRepository(jdbc);
        commentErasure = new JdbcCommentErasure(jdbc);
    }

    @Test
    @DisplayName("PG18: the saga's purge and the meme-deleted cascade deadlock on crossed votes — "
            + "and both recover, because both are idempotent")
    void purge_and_cascade_deadlock_then_recover() throws Exception {
        String meme = UUID.randomUUID().toString();
        // c1 is the leaver's own comment (oldest, so DeleteThread's allUnder visits it first);
        // c2 is somebody else's reply. A fan liked c1; the leaver liked c2 — the cross-vote that
        // puts the two transactions' lock order in opposition.
        String c1 = savedComment(meme, LEAVER, Instant.parse("2026-01-01T10:00:00Z"));
        String c2 = savedComment(meme, "bob@example.com", Instant.parse("2026-01-01T10:00:01Z"));
        castVote(c1, "fan@example.com", "UP");
        castVote(c2, LEAVER, "UP");
        markForErasure(c1);

        CountDownLatch purgeHoldsItsOwnVote = new CountDownLatch(1);
        CountDownLatch cascadeHoldsC1Vote = new CountDownLatch(1);

        // T1: the saga's ERASE step. purgeVoter(LEAVER) is the FIRST thing PurgeUserComments does
        // (it must run before any score is read — see the use case's javadoc) and it locks the
        // (c2, LEAVER) row; this override pauses right after, so T2 can grab (c1, fan) first.
        CommentVotes votesForPurge = new JdbcCommentVotes(jdbc) {
            @Override
            public void purgeVoter(String voter) {
                super.purgeVoter(voter);
                purgeHoldsItsOwnVote.countDown();
                awaitLatch(cascadeHoldsC1Vote, "the cascade to lock c1's vote");
            }
        };
        // T2: the MEME_DELETED cascade. Its first purgeComment call (c1, oldest-first) locks
        // (c1, fan); this override pauses right after, so both sides are now committed to the
        // opposite order before either reaches for the other's row.
        CommentVotes votesForCascade = new JdbcCommentVotes(jdbc) {
            private boolean first = true;

            @Override
            public void purgeComment(String commentId) {
                super.purgeComment(commentId);
                if (first) {
                    first = false;
                    cascadeHoldsC1Vote.countDown();
                    awaitLatch(purgeHoldsItsOwnVote, "the purge to lock its own vote");
                }
            }
        };

        TransactionTemplate purgeTx = new TransactionTemplate(txManager);
        PurgeUserComments purge = new PurgeUserComments(
                commentRepository, commentErasure, votesForPurge, new PurgeRule.Delete());
        TransactionTemplate cascadeTx = new TransactionTemplate(txManager);
        DeleteThread cascade = new DeleteThread(commentRepository, commentErasure, votesForCascade);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> purgeOutcome = pool.submit(() -> {
                try {
                    purgeTx.executeWithoutResult(status -> purge.execute(LEAVER, Optional.empty()));
                    return null;
                } catch (RuntimeException raised) {
                    return raised;
                }
            });
            Future<Throwable> cascadeOutcome = pool.submit(() -> {
                try {
                    cascadeTx.executeWithoutResult(status -> cascade.execute(meme));
                    return null;
                } catch (RuntimeException raised) {
                    return raised;
                }
            });

            // bounded get(): if the deadlock detector never fires, this fails as a readable
            // TimeoutException instead of hanging until surefire's global kill
            Throwable purgeFailure = purgeOutcome.get(20, TimeUnit.SECONDS);
            Throwable cascadeFailure = cascadeOutcome.get(20, TimeUnit.SECONDS);

            // Postgres' own detector, not a hang: exactly one side loses the deadlock and rolls
            // all the way back — never both, because killing one breaks the cycle for the other
            int failures = (purgeFailure != null ? 1 : 0) + (cascadeFailure != null ? 1 : 0);
            assertEquals(1, failures, "exactly one side must lose the deadlock; purge failed with "
                    + purgeFailure + ", cascade failed with " + cascadeFailure);
            Throwable deadlockLoser = purgeFailure != null ? purgeFailure : cascadeFailure;
            assertTrue(deadlockLoser instanceof DataAccessException,
                    "the loser must be Spring's translation of Postgres' own deadlock detector "
                            + "(SQLSTATE 40P01), not some other failure: " + deadlockLoser);

            // idempotent recovery: whichever side Postgres killed is retried exactly as the real
            // carrier would (PurgeCommandsListener / MemesEventsListener's error handler backs off
            // and redelivers), and the retry finds nothing left undone
            if (purgeFailure != null) {
                purgeTx.executeWithoutResult(status -> purge.execute(LEAVER, Optional.empty()));
            } else {
                cascadeTx.executeWithoutResult(status -> cascade.execute(meme));
            }
        } finally {
            pool.shutdownNow();
        }

        // the end state is what both mechanisms promise, regardless of which one actually deleted
        // which row first: the whole thread gone, no vote left behind on either comment
        assertEquals(0, rowCountByMeme(meme), "the whole thread must be gone");
        assertEquals(0, rowCountByComment(c1), "c1's votes must be gone");
        assertEquals(0, rowCountByComment(c2), "c2's votes must be gone");
    }

    private static void awaitLatch(CountDownLatch latch, String waitingFor) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                fail("timed out waiting for " + waitingFor + " — the deadlock never formed");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for " + waitingFor);
        }
    }

    private static String savedComment(String memeId, String author, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO comments (id, meme_id, author, content, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)")
                .params(id, memeId, author, "under race test", Timestamp.from(createdAt))
                .update();
        return id;
    }

    private static void castVote(String commentId, String voter, String direction) {
        jdbc.sql("INSERT INTO comment_votes (comment_id, voter, direction) VALUES (?, ?, ?)")
                .params(commentId, voter, direction)
                .update();
    }

    private static void markForErasure(String commentId) {
        jdbc.sql("UPDATE comments SET status = 'PENDING_ERASURE', "
                        + "marked_for_erasure_at = CURRENT_TIMESTAMP WHERE id = ?")
                .param(commentId).update();
    }

    private static long rowCountByMeme(String memeId) {
        return jdbc.sql("SELECT COUNT(*) FROM comments WHERE meme_id = ?")
                .param(memeId).query(Long.class).single();
    }

    private static long rowCountByComment(String commentId) {
        return jdbc.sql("SELECT COUNT(*) FROM comment_votes WHERE comment_id = ?")
                .param(commentId).query(Long.class).single();
    }
}
