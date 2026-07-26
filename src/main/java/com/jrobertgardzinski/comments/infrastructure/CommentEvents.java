package com.jrobertgardzinski.comments.infrastructure;

import java.util.List;

/**
 * The outbound edge of the deletion CHOREOGRAPHY: what this service tells the rest of the portal
 * after it dropped a thread. One implementation talks to the broker ({@link KafkaCommentEvents}),
 * one says nothing ({@link NoopCommentEvents}, for tests and broker-less dev runs).
 *
 * <p>Deliberately an infrastructure interface, not an application port: no use case calls it. The
 * announcement is a consequence of the CASCADE, not of dropping a thread — the use case knows
 * nothing of brokers, and the only place that knows an announcement is even called for is the
 * listener that started the hop.
 *
 * <p>Since round 10 the implementation writes an outbox row, so the call belongs INSIDE the hop's
 * transaction rather than after it: the announcement shares the fate of the delete in both
 * directions. See {@link MemesEventsListener} for the unit of work and {@link KafkaCommentEvents}
 * for why the durability is worth having now.
 */
interface CommentEvents {

    /**
     * Announce that a meme's thread went with it, naming every comment that went.
     *
     * @param memeId     the meme whose thread was dropped — also the event's partition key
     * @param commentIds the dropped comments, never empty (an empty announcement carries no fact)
     */
    void commentsDeleted(String memeId, List<String> commentIds);
}
