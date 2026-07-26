package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget's arithmetic, on a steered clock — because the numbers ARE the decision here.
 *
 * <p>Two failure modes have to be told apart, and a count of attempts cannot tell them apart: a store
 * that refuses instantly (a rolled-back transaction) and a store that refuses after blocking (Postgres
 * gone, one Hikari {@code connection-timeout} per attempt). Ten attempts mean 90 seconds in the first
 * case and five minutes in the second — which is why the budget is measured on the wall clock, and why
 * this test drives the clock rather than the count.
 */
class SagaRetryBudgetTest {

    /** The steerable clock: nanoseconds this test hands out, in the order it chooses. */
    private long nowNanos;

    private final SagaRetryBudget budget = new SagaRetryBudget(Duration.ofSeconds(90),
            Duration.ofSeconds(1), Duration.ofSeconds(15), () -> nowNanos);

    private void elapse(Duration time) {
        nowNanos += time.toNanos();
    }

    @Test
    @DisplayName("fast failures: the pauses double to the 15s cap and the whole budget is 90s of them")
    void the_pauses_grow_to_the_cap_and_stop_at_the_budget() {
        BackOffExecution retrying = budget.start();
        List<Long> pauses = new ArrayList<>();

        // the attempts themselves cost nothing (an instantly refused transaction), so the budget is
        // spent entirely on pauses — this is the arithmetic the class javadoc quotes
        long pause;
        while ((pause = retrying.nextBackOff()) != BackOffExecution.STOP) {
            pauses.add(pause);
            elapse(Duration.ofMillis(pause));
        }

        assertEquals(List.of(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L),
                pauses, "1s doubling to the 15s cap, until the 90s budget is gone");
        assertEquals(90_000L, pauses.stream().mapToLong(Long::longValue).sum(),
                "the pauses spend the budget exactly — the last one is trimmed to what is left");
        assertEquals(10, pauses.size() + 1,
                "nine pauses = ten attempts at the leaver's content before the command is dropped");
    }

    @Test
    @DisplayName("blocking failures: fewer attempts, spread over minutes, because their time counts too")
    void attempts_that_block_spend_the_budget_themselves() {
        // the budget opens at the FIRST failure, so the first attempt's own 30s block is not in it
        elapse(Duration.ofSeconds(30));
        long recordArrivedAt = 0;
        BackOffExecution retrying = budget.start();
        int attempts = 1;

        long pause;
        while ((pause = retrying.nextBackOff()) != BackOffExecution.STOP) {
            elapse(Duration.ofMillis(pause));
            // every attempt sits out a full Hikari connection timeout before it throws
            elapse(Duration.ofSeconds(30));
            attempts++;
        }

        assertEquals(4, attempts, "30+1+30+2+30+4+30: four real attempts across a database restart,"
                + " where the framework default's ten immediate ones rode out nothing");
        Duration heldTheRecord = Duration.ofNanos(nowNanos - recordArrivedAt);
        assertTrue(heldTheRecord.compareTo(Duration.ofSeconds(150)) <= 0,
                "and the record is released within 150s of arriving (the 90s budget plus the block of"
                        + " the attempt that opens it and of the one that closes it) — the bound the"
                        + " listener's stall tolerance is derived from, was: " + heldTheRecord);
    }

    @Test
    @DisplayName("the last pause is trimmed to the budget instead of overshooting it")
    void the_last_pause_never_outlives_the_budget() {
        BackOffExecution retrying = budget.start();
        long pause;
        while ((pause = retrying.nextBackOff()) < 15_000L) {
            elapse(Duration.ofMillis(pause));   // 1+2+4+8: the doubling up to the cap
        }
        elapse(Duration.ofMillis(pause));       // and the first capped pause: 30s spent
        elapse(Duration.ofSeconds(57));         // 87s spent — three seconds left, a 15s pause due

        pause = retrying.nextBackOff();

        assertEquals(3_000L, pause, "the budget is a deadline: a 15s pause here would spend 102s of a"
                + " 90s promise, so it is cut to the remainder and buys one last attempt");
        elapse(Duration.ofMillis(pause));
        assertEquals(BackOffExecution.STOP, retrying.nextBackOff(), "and then it is over");
    }

    @Test
    @DisplayName("a spent budget stops instead of retrying forever — that is the whole point")
    void a_spent_budget_stops() {
        BackOffExecution retrying = budget.start();
        elapse(Duration.ofSeconds(91));

        assertEquals(BackOffExecution.STOP, retrying.nextBackOff(),
                "an eternal retry would purge the content of an account the saga has already restored"
                        + " — the orchestrator gives up at ~165s (120s timeout + 3 x 15s)");
    }

    @Test
    @DisplayName("every record gets its own deadline, not a share of a global one")
    void each_record_starts_its_own_budget() {
        BackOffExecution first = budget.start();
        elapse(Duration.ofSeconds(91));
        assertEquals(BackOffExecution.STOP, first.nextBackOff());

        // the sweeper's re-command is a NEW record: it arrives with the full budget, whatever the
        // previous one spent (Spring Kafka calls start() per record)
        BackOffExecution recommanded = budget.start();
        assertEquals(1_000L, recommanded.nextBackOff());
    }

    @Test
    @DisplayName("the service runs on the documented numbers, not on a test's")
    void the_production_budget_is_the_documented_one() {
        assertEquals(Duration.ofSeconds(90), SagaRetryBudget.BUDGET,
                "under the orchestrator's 120s purge timeout, over two Hikari connection timeouts");
        assertEquals(Duration.ofSeconds(15), SagaRetryBudget.LONGEST_PAUSE,
                "the sweeper's own interval, and far below max.poll.interval.ms (300s) — these pauses"
                        + " sleep on the consumer thread");
        assertTrue(SagaRetryBudget.forSagaRecords().start().nextBackOff() > 0,
                "the production budget must offer at least one retry, or the handler would treat it"
                        + " as 'no retries configured' and drop every failed record immediately");
    }
}
