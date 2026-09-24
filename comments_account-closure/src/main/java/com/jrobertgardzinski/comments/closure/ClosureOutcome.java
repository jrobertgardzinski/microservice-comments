package com.jrobertgardzinski.comments.closure;

/**
 * What the participant did with a command. Returned rather than logged-and-forgotten so that a
 * caller in any assembly can act on it: over a broker the adapter acknowledges the record, in one
 * process the bus can hand it straight back to the orchestrator.
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
