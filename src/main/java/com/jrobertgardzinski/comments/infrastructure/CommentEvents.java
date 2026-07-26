package com.jrobertgardzinski.comments.infrastructure;

import java.util.List;

/**
 * The outbound edge of the deletion CHOREOGRAPHY: what this service tells the rest of the portal
 * after it dropped a thread. One implementation talks to the broker ({@link KafkaCommentEvents}),
 * one says nothing ({@link NoopCommentEvents}, for tests and broker-less dev runs).
 *
 * <p>Deliberately an infrastructure interface, not an application port: no use case calls it. The
 * announcement is a consequence of a committed delete, and the only place that knows the delete
 * committed is outside the transaction — the listener that started the cascade.
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
