package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.Voting;

import java.util.List;
import java.util.Optional;

/**
 * Lists the comments on a meme, each with its tally through the viewer's eyes (signed-in viewers
 * see which way they voted).
 */
public class ListComments {

    private final CommentRepository commentRepository;
    private final Voting voting;

    public ListComments(CommentRepository commentRepository, CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.voting = new Voting(commentVotes);
    }

    public List<CommentWithScore> execute(String memeId, Optional<String> viewer) {
        return commentRepository.findByMeme(memeId).stream()
                .map(comment -> new CommentWithScore(comment, voting.tally(comment.id(), viewer)))
                .toList();
    }
}
