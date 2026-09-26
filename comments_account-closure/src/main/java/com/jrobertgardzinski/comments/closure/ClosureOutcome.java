package com.jrobertgardzinski.comments.closure;

/** What the participant did with a command. Over Kafka nobody reads it; the one-process specs do. */
public sealed interface ClosureOutcome {

    /** Marked and confirmed; zero is a real answer. */
    record Reserved(int comments) implements ClosureOutcome {
    }

    record Erased() implements ClosureOutcome {
    }

    record Restored() implements ClosureOutcome {
    }

    /** The saga's topic carries every participant's commands; an unknown name is not an error. */
    record NotOurs(String type) implements ClosureOutcome {
    }

    /** Dropped WITHOUT confirming: nothing was deleted. */
    record Unaddressed(String type) implements ClosureOutcome {
    }
}
