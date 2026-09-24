package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.UserCommentsRekey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every column in this schema that holds a person's e-mail address, in one place — because the value
 * of writing them down together is that the next one cannot be forgotten quietly.
 *
 * <p>The list, and why each is here:
 * <ul>
 *   <li>{@code comments.author} (V1) — the authorship itself, and the one the thread authorises on:
 *       who may delete a comment, whose words the tombstone still shows, and which comments an
 *       erasure goes looking for;</li>
 *   <li>{@code comment_votes.voter} (V1) — the ballots. Left behind they would be counted for a
 *       person who can no longer see or change them, a deletion's {@code purgeVoter} would not find
 *       them either, and the freed address would hand the next registrant somebody else's opinion
 *       of a comment.</li>
 * </ul>
 * Nothing else keys on an address. {@code comment_flags} is keyed by comment id, and the one
 * remaining occurrence — a leaver's address inside a {@code comment_events_outbox} payload — is
 * deliberately NOT rewritten. That table is a record of messages already built, some already sent,
 * each stored under an id its consumers deduplicate on, so editing history there would change what a
 * redelivery says without changing its id. It would also be wrong in its own right for the one
 * message that carries an address: a {@code USER_CONTENT_PURGED} confirmation is matched by the
 * orchestrator against the address it commanded the purge for, so moving it on would answer a saga
 * nobody started. Those rows expire on the 24h retention.
 *
 * <p>{@code comments} and not {@code active_comments}: a comment a running saga has already reserved
 * must move with the rest, or the closure command — which looks for the leaver's PENDING comments by
 * address — would find nothing left to erase. (The view is the READ filter; a write names the table,
 * which is the distinction {@code CommentReadFilterTest} draws.)
 *
 * <p>Two plain UPDATEs, no upsert and no conflict handling, and that is a statement about the data
 * rather than an omission. The only unique constraint an address takes part in is
 * {@code comment_votes}' primary key {@code (comment_id, voter)}, so a collision would need live rows
 * under the NEW address at the moment of the move — which cannot happen: security refuses a move onto
 * a registered address, and an unregistered one is either untouched or has had its owner's rows taken
 * by the deletion that freed it. If that ever stopped being true the UPDATE would fail loudly inside
 * the listener's transaction and be retried, which is the right failure: nothing is silently merged.
 */
@Repository
class JdbcUserCommentsRekey implements UserCommentsRekey {

    private final JdbcClient jdbc;

    JdbcUserCommentsRekey(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int moveTo(String oldEmail, String newEmail) {
        return jdbc.sql("UPDATE comments SET author = ? WHERE author = ?")
                       .params(newEmail, oldEmail).update()
                + jdbc.sql("UPDATE comment_votes SET voter = ? WHERE voter = ?")
                       .params(newEmail, oldEmail).update();
    }
}
