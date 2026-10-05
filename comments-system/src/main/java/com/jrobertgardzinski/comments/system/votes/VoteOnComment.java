package com.jrobertgardzinski.comments.system.votes;

import com.jrobertgardzinski.comments.domain.core.CommentRepository;
import com.jrobertgardzinski.comments.domain.votes.CommentVotes;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import com.jrobertgardzinski.voting.Voting;

import java.util.Optional;

/**
 * Casts a voter's vote on a comment — the toggle semantics come from the voting library; this use
 * case anchors them to a comment that exists under the given meme.
 */
public class VoteOnComment {

    private final CommentRepository commentRepository;
    private final Voting voting;

    public VoteOnComment(CommentRepository commentRepository, CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.voting = new Voting(commentVotes);
    }

    /**
     * The voter is an id, as everywhere else a use case names a person ({@code DeleteComment} takes
     * one too): one identity has one shape in this module. The ballot store keys votes by the id in
     * its WIRE form, so the flattening happens here, at the library's edge — the same place
     * {@code PurgeUserComments} does it.
     */
    public Optional<VoteTally> execute(String memeId, String commentId, UserId voter, VoteDirection direction) {
        try {
            return commentRepository.find(commentId)
                    .filter(comment -> comment.memeId().equals(memeId))
                    .map(comment -> voting.toggle(commentId, voter.toString(), direction));
        } catch (CommentVotes.UnknownComment deletedMidVote) {
            // the comment passed the check above but was deleted before the ballot landed (the
            // store's foreign key caught it) — same outcome as failing the check: no such comment
            return Optional.empty();
        }
    }
}
