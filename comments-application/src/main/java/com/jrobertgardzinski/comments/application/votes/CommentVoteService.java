package com.jrobertgardzinski.comments.application.votes;

import com.jrobertgardzinski.comments.system.votes.VoteOnComment;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;

import java.util.Locale;

/** Voting on a comment. */
public final class CommentVoteService {

    private final VoteOnComment voteOnComment;

    public CommentVoteService(VoteOnComment voteOnComment) {
        this.voteOnComment = voteOnComment;
    }

    /** {@code direction} is UP or DOWN, in any case. */
    public Vote vote(String memeId, String commentId, UserId voter, String direction) {
        VoteDirection parsed;
        try {
            parsed = VoteDirection.valueOf(String.valueOf(direction).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return new Vote.InvalidDirection();
        }
        return voteOnComment.execute(memeId, commentId, voter, parsed)
                .<Vote>map(Vote.Counted::new)
                .orElseGet(Vote.NoSuchComment::new);
    }

    public sealed interface Vote {
        record Counted(VoteTally tally) implements Vote {}

        record InvalidDirection() implements Vote {}

        record NoSuchComment() implements Vote {}
    }
}
