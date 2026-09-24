package com.jrobertgardzinski.comments.application;

/**
 * A member changed their e-mail address, and their words follow them.
 *
 * <p>This service stores a comment's author as the address the token carried when it was written,
 * and a ballot as the address the voter had when they cast it. Nothing used to rewrite either, so a
 * confirmed change of address made a person a stranger to their own thread: the listing showed
 * {@code own:false}, a delete of their own comment came back 403 — and, the part that cost more than
 * a wrong flag, an account deletion marked nothing, confirmed the erasure anyway and left the
 * comments in the public thread, where the next registrant of the freed address inherited the
 * authorship and the rights that come with it. Re-keying the rows is what makes the address a NAME
 * for the person rather than the person themselves.
 *
 * <p><strong>Idempotent</strong> by arithmetic rather than by bookkeeping, which is why there is no
 * dedup table here: the second delivery of the same rename finds no row under the old address and
 * moves nothing. The fact is at-least-once, like every other fact in this estate, and this is what
 * it costs to absorb that — one UPDATE that matches nothing.
 *
 * <p><strong>What it deliberately does NOT do: wait, or check.</strong> There is no verification
 * that the new address is free of rows, because there cannot be any: microservice-security refuses a
 * move onto a registered address, and a deletion takes its owner's rows with it. And there is no
 * ordering promise against the deletion saga — the rename travels on {@code security-events} while
 * the purge commands travel on {@code content-commands}, so a deletion requested seconds after a
 * rename can still overtake this. That window is the producer's documented, accepted limit (the
 * durable answer is keying the estate on a stable user id), and what this service does about it is
 * to stop CLAIMING the erasure happened — see {@code PurgeCommandsListener}.
 */
public class RekeyUserComments {

    private final UserCommentsRekey rekey;

    public RekeyUserComments(UserCommentsRekey rekey) {
        this.rekey = rekey;
    }

    /** Returns how many rows moved — for the log line; nothing branches on it. */
    public int execute(String oldEmail, String newEmail) {
        return rekey.moveTo(oldEmail, newEmail);
    }
}
