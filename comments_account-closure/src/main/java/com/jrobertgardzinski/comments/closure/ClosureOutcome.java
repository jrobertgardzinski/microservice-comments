package com.jrobertgardzinski.comments.closure;

/**
 * What the participant did with a command.
 *
 * <p><strong>Nothing in this service reads it yet</strong>, and that is worth saying rather than
 * implying otherwise. The Kafka listener discards it: over a broker this participant answers
 * through the outbox, inside the same transaction as the mark, so a returned value has nowhere
 * to go. The portal's one-process specs read it, and nothing else does.
 *
 * <p>The collections participant works the other way round — it returns its outcome and its
 * consumer builds the confirmation — and which of the two shapes all three should take is a
 * decision about assembly, not about closing an account. It is open on purpose. Until it is
 * settled, this type states the answer and lets the caller ignore it.
 */
public sealed interface ClosureOutcome {

    /**
     * The reversible step is done and confirmed: this many of the leaver's comments are out of every
     * thread and none of them is destroyed. A count of zero is a real answer, not a failure —
     * and the one the service cannot interpret on its own (see the participant).
     */
    record Reserved(int comments) implements ClosureOutcome {
    }

    /** The closure was carried out: what the rule said to destroy is gone. Past the pivot. */
    record Erased() implements ClosureOutcome {
    }

    /** The compensation was carried out: the marks are off and the conversation is whole again. */
    record Restored() implements ClosureOutcome {
    }

    /**
     * A command this participant has no part in. Not an error: the saga's topic carries every
     * participant's commands, and meeting a name from a newer orchestrator must be survivable.
     */
    record NotOurs(String type) implements ClosureOutcome {
    }

    /**
     * A command that names no one. Dropped WITHOUT confirming: confirming would advance the saga
     * on the claim that a deletion happened, and nothing was deleted.
     */
    record Unaddressed(String type) implements ClosureOutcome {
    }
}
