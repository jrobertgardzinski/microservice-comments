package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;

/**
 * How long this service keeps re-trying ONE record of the account-deletion saga before it gives up
 * on it — a wall-clock <strong>deadline</strong>, not a number of attempts.
 *
 * <p><strong>What it replaces.</strong> Registering no error handler left Spring Kafka's
 * {@code DefaultErrorHandler} with its {@code FixedBackOff(0L, 9)} in charge: ten attempts with NO
 * pause between them (the whole burst inside a millisecond), then the record is "recovered" — logged
 * and skipped, its offset committed. A one-second hiccup of Postgres, a Hikari pool momentarily
 * exhausted, a restarting MinIO — anything that is over in a second rather than in a microsecond —
 * therefore made the purge command DISAPPEAR, silently, while the contract this service signed says
 * the saga must not lose a purge.
 *
 * <p><strong>Why it is not the eternal retry the sibling participant uses.</strong>
 * microservice-user-collections retries forever with backoff and never commits, with a javadoc
 * paragraph explaining that the saga must not lose a purge — true, and yet it cannot simply be
 * copied here, because the orchestrator gives up in finite time:
 *
 * <ul>
 *   <li>{@code OFFBOARDING_PURGE_TIMEOUT_SEC} = 120s — a saga unconfirmed for that long is overdue;</li>
 *   <li>the sweeper wakes every 15s and re-commands while retries remain
 *       ({@code SweepOverdue.DEFAULT_MAX_RETRIES} = 3), so the re-commands land at ≈120s, ≈135s
 *       and ≈150s;</li>
 *   <li>at ≈165s the retries are spent: the saga compensates, microservice-security hands the
 *       account back and mails the leaver that the deletion FAILED.</li>
 * </ul>
 *
 * <p>A participant that retried without end would purge whenever its store came back — an hour
 * later, a day later — deleting the content of an account the saga has already restored to its
 * owner. So the retrying is bounded, and the bound is this class.
 *
 * <p><strong>The arithmetic of the 90 seconds.</strong>
 *
 * <ul>
 *   <li><em>Floor ≈ 60s.</em> The failures this budget exists for BLOCK rather than fail fast: with
 *       Postgres unreachable, one attempt can spend a whole Hikari {@code connection-timeout} (the
 *       30s default, not overridden in this service) before it even throws. A budget under a minute
 *       would buy a single real attempt, which is the status quo with extra steps.</li>
 *   <li><em>Ceiling = 120s.</em> That is when the orchestrator re-commands. Retrying past it means
 *       competing with the command the sweeper has already sent for the same account: the purge is
 *       idempotent so nothing breaks, but it stops being THIS record's job. Ending first means the
 *       record in hand heals the hiccup, or hands the problem back to the sweeper. (The budget opens
 *       at the FIRST failure, so a record's whole life here is the budget plus that first attempt:
 *       ≈90s when failures are instant, ≈120s when the first one sits out a connection timeout —
 *       either way about the orchestrator's own patience, which is the number it should resemble.)</li>
 *   <li><em>90s is between them</em> and admits, concretely: ten attempts when the failure is fast
 *       (pauses 1+2+4+8+15+15+15+15+15 = 90s), and still four spread over two minutes when every
 *       attempt burns a full 30s connection timeout. Four attempts across two minutes is what rides
 *       out a database restart; ten immediate ones ride out nothing.</li>
 *   <li><em>The pause cap is 15s</em> — the sweeper's own interval, the coarsest clock in the saga —
 *       and it must stay far below {@code max.poll.interval.ms} (300s), because these pauses sleep
 *       ON the consumer thread: a pause longer than that interval would turn a database hiccup into
 *       a consumer-group rebalance.</li>
 * </ul>
 *
 * <p><strong>What "bounded" buys at the estate level.</strong> The orchestrator sends at most 1+3
 * commands per saga, the retries block their partition, and the commands of one leaver share a
 * partition (they are keyed by the address), so the re-commands queue behind the record being
 * retried. A permanent outage therefore costs at most 4 × 90s ≈ 6 minutes of retrying per saga and
 * then silence — bounded, logged and counted (see {@link SagaParticipantConfig}), instead of a purge
 * that lands whenever the store happens to return.
 *
 * <p><strong>The residual window, named rather than hidden.</strong> The last re-command lands at
 * ≈150s and may still complete its purge up to 90s later — up to ≈75s AFTER the saga compensated.
 * That overshoot is deliberate: "some participants purged, others did not" is already an outcome the
 * orchestrator announces (PORTAL_PURGE_FAILED names the participants that DID purge), it stays
 * inside the incident window an operator is already looking at, and it is the price of surviving an
 * outage that the ten-immediate-attempts default drops in a millisecond. What was NOT acceptable is
 * the unbounded version of the same window.
 */
final class SagaRetryBudget implements BackOff {

    /** The whole retrying of one record, measured on the wall clock. See the class javadoc. */
    static final Duration BUDGET = Duration.ofSeconds(90);

    /** The first pause: short, because most store hiccups are over in a second. */
    static final Duration FIRST_PAUSE = Duration.ofSeconds(1);

    /** The pause cap — the sweeper's interval, and far below {@code max.poll.interval.ms}. */
    static final Duration LONGEST_PAUSE = Duration.ofSeconds(15);

    private final Duration budget;
    private final Duration firstPause;
    private final Duration longestPause;

    /** The elapsed-time source: monotonic in production, steerable in the tests. */
    private final java.util.function.LongSupplier nanoTime;

    static SagaRetryBudget forSagaRecords() {
        return new SagaRetryBudget(BUDGET, FIRST_PAUSE, LONGEST_PAUSE, System::nanoTime);
    }

    SagaRetryBudget(Duration budget, Duration firstPause, Duration longestPause,
                    java.util.function.LongSupplier nanoTime) {
        this.budget = budget;
        this.firstPause = firstPause;
        this.longestPause = longestPause;
        this.nanoTime = nanoTime;
    }

    /**
     * A fresh deadline per record. Spring Kafka's {@code FailedRecordTracker} calls this on a
     * record's FIRST failure (and once more, throwaway, while it works out whether retries are
     * configured at all), so the budget starts when the trouble starts — not when the service booted.
     *
     * <p>{@code System.nanoTime}, not the wall clock: the deadline measures elapsed time, and a wall
     * clock can step (NTP). Backwards it would hand out a budget that never expires; forwards it
     * would cut a purge short mid-outage.
     */
    @Override
    public BackOffExecution start() {
        long deadline = nanoTime.getAsLong() + budget.toNanos();
        return new BackOffExecution() {

            private long nextPauseMillis = firstPause.toMillis();

            @Override
            public long nextBackOff() {
                long remainingNanos = deadline - nanoTime.getAsLong();
                if (remainingNanos <= 0) {
                    return STOP;   // the budget is spent: the record is dropped, loudly and counted
                }
                long pause = Math.min(nextPauseMillis, longestPause.toMillis());
                nextPauseMillis = pause * 2;
                // never sleep PAST the deadline: a 15s pause with 3s of budget left would push the
                // record's last attempt beyond the budget the class name promises. Trimming it
                // spends the remainder on one final attempt instead.
                return Math.min(pause, remainingNanos / 1_000_000);
            }

            @Override
            public String toString() {
                return "SagaRetryBudget[" + budget.toSeconds() + "s, next pause "
                        + nextPauseMillis + "ms]";
            }
        };
    }

    @Override
    public String toString() {
        return "SagaRetryBudget[" + budget.toSeconds() + "s budget, pauses " + firstPause.toMillis()
                + "ms..." + longestPause.toMillis() + "ms]";
    }
}
