package com.jrobertgardzinski.comments.closure;

/**
 * All of a step, or none of it.
 *
 * <p>The participant states that a step is atomic; it does not state what makes it so. Over a
 * database that is a transaction, and the only reason this participant needs one: the mark and
 * the promise to report it must commit together, or the comments end up hidden with nothing owing
 * the orchestrator a word about it — which ends with the leaver holding a restored account and
 * invisible content.
 */
@FunctionalInterface
public interface Atomically {

    void run(Runnable step);
}
