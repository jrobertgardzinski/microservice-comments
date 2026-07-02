package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.Ballots;

/**
 * The voting context's {@link Ballots} store applied to comments, extended with the
 * account-deletion purges.
 */
public interface CommentVotes extends Ballots {

    void purgeComment(String commentId);

    void purgeVoter(String voter);
}
