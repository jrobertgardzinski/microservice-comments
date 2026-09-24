package com.jrobertgardzinski.comments.domain;

import java.time.Duration;

/**
 * Something this service has noticed about itself and considers worth saying out loud — a domain
 * probe: the code states a fact in the language of the business, and whatever is watching
 * translates it into its own.
 *
 * <p>Sealed because it is a vocabulary, not an extension point. Every fact here is a sentence about
 * account deletion that <strong>no tool could derive on its own</strong>: an agent knows a method
 * took 40ms, but nothing outside this service can know that a leaver's words are sitting
 * marked-but-not-erased, because that is a conclusion drawn from this service's own rules.
 *
 * <p>Timings, spans, retries and error rates never appear here — they arrive by instrumentation,
 * without a line of ours, spelled in whatever vocabulary this year's tool uses. What is in here is
 * what survives replacing that tool.
 */
public sealed interface Observation {

    /**
     * Comments reserved by a deletion saga whose closure never came: hidden from every reader,
     * still stored. {@code oldest} is how long the oldest such mark has stood — an age, and
     * therefore not personal data, which matters because the people behind these comments are
     * exactly the ones this service is trying to forget.
     *
     * <p>Stated on EVERY pass, zero included: the question is "how many obligations am I sitting on
     * right now", so silence would leave a stale answer standing.
     */
    record ErasureBacklog(int marked, Duration oldest) implements Observation {

        public static final ErasureBacklog NONE = new ErasureBacklog(0, Duration.ZERO);
    }

    /**
     * One saga command this service will now never carry out: the retry budget ran out with the
     * database or the broker still unreachable. Whether that is survivable depends on which command
     * it was — a lost mark ends in the orchestrator compensating, a lost closure ends in content
     * hidden for ever — and the service cannot tell which, so it says what it knows.
     */
    record SagaCommandDropped(String topic) implements Observation {
    }

    /**
     * A deletion saga reserved NOTHING for the person it named, and this service confirmed that it
     * had nothing to reserve. Harmless when the person really never commented — and the whole of
     * F-014 when they did, under the address they used to have: the rename travels on another topic
     * and can arrive after the purge, so this is the one moment at which "I hold nothing of theirs"
     * and "I have not caught up with their new address" look identical from the inside.
     *
     * <p>Stated because nothing else can state it. A broker dashboard sees a command consumed and a
     * confirmation produced; only this service knows the confirmation was empty, and the shape of
     * the defect is that a rise in this count accompanies deletions that leave words behind.
     */
    record PurgeReservedNothing() implements Observation {
    }
}
