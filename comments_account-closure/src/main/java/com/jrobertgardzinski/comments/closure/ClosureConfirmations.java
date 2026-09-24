package com.jrobertgardzinski.comments.closure;

/**
 * How this participant tells the orchestrator that the reversible step is done — and how many
 * comments it reserved, because "I marked forty" and "I found nothing of theirs" are different
 * answers and used to go out in the same words.
 *
 * <p>An interface because the answer's route is an assembly's business: an outbox row that a
 * broker later carries, or a call on an in-memory bus. What is NOT negotiable is when it is
 * called — inside the same unit of work as the mark it confirms (see {@link Atomically}).
 */
@FunctionalInterface
public interface ClosureConfirmations {

    void confirm(String sagaId, String leaver, int reserved);
}
