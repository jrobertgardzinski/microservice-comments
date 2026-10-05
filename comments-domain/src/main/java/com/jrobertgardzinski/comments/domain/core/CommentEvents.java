package com.jrobertgardzinski.comments.domain.core;

import java.util.List;

/**
 * The outbound edge of the deletion CHOREOGRAPHY: what this service tells the rest of the portal
 * after it dropped a thread. One implementation talks to the broker, one says nothing (for tests
 * and broker-less dev runs); both live in infrastructure, as adapters of this port do.
 *
 * <p>It was an infrastructure interface until the cascade became a protocol, on the grounds that
 * no use case calls it — true, and beside the point: it is the hop's outbound edge, and while it
 * was declared in infrastructure no application-level test could drive the hop end to end. That
 * is the same leak RequestedBy was in offboarding before 2026-09-12. The announcement is still a
 * consequence of the CASCADE rather than of dropping a thread, which is why no use case calls it
 * and why CommentsDeletionParticipant does.
 *
 * <p>The implementation writes an outbox row, so the call belongs INSIDE the hop's unit of work
 * rather than after it: the announcement shares the fate of the delete in both directions.
 */
public interface CommentEvents {

    /**
     * Announce that a meme's thread went with it, naming every comment that went.
     *
     * @param memeId     the meme whose thread was dropped — also the event's partition key
     * @param commentIds the dropped comments, never empty (an empty announcement carries no fact)
     */
    void commentsDeleted(String memeId, List<String> commentIds);
}
