package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.Ballots;
import com.jrobertgardzinski.voting.VoteTally;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The voting context's {@link Ballots} store applied to comments, extended with the
 * account-deletion purges and a batch read for listings.
 */
public interface CommentVotes extends Ballots {

    /**
     * Casting against a comment that no longer exists — the vote-vs-delete race, surfaced by the
     * store (the V3 foreign key) after the caller's existence check already passed. Callers treat
     * it exactly like "comment not found" on the check itself.
     */
    class UnknownComment extends RuntimeException {
        public UnknownComment(String commentId, Throwable cause) {
            super("no comment " + commentId + " to vote on", cause);
        }
    }

    void purgeComment(String commentId);

    void purgeVoter(String voter);

    /**
     * The tallies of a whole page of comments at once, through the viewer's eyes — so a listing
     * costs a constant number of store round-trips, not two per comment. The default walks the
     * per-comment reads (fine for in-memory doubles); the JDBC adapter overrides it with set
     * queries.
     */
    default Map<String, VoteTally> tallyAll(List<String> commentIds, Optional<String> viewer) {
        Map<String, VoteTally> tallies = new HashMap<>();
        for (String id : commentIds) {
            tallies.put(id, new VoteTally(scoreOf(id), viewer.flatMap(v -> voteOf(id, v))));
        }
        return tallies;
    }
}
