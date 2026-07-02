package com.jrobertgardzinski.comments.application;

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

    public Optional<VoteTally> execute(String memeId, String commentId, String voter, VoteDirection direction) {
        return commentRepository.find(commentId)
                .filter(comment -> comment.memeId().equals(memeId))
                .map(comment -> voting.toggle(commentId, voter, direction));
    }
}
