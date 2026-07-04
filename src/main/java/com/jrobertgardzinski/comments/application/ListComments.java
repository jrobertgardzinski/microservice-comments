package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.Voting;

import java.util.List;
import java.util.Optional;

/**
 * Lists the comments on a meme, each with its tally through the viewer's eyes (signed-in viewers
 * see which way they voted). One PAGE at a time — long threads are read in slices, so a viral
 * meme's discussion never ships as one enormous response.
 */
public class ListComments {

    /** A page of comments plus the total, so a client knows whether more remain. */
    public record Page(List<CommentWithScore> comments, int total, int offset, int limit) {
        public boolean hasMore() {
            return offset + comments.size() < total;
        }
    }

    private final CommentRepository commentRepository;
    private final Voting voting;

    public ListComments(CommentRepository commentRepository, CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.voting = new Voting(commentVotes);
    }

    public Page execute(String memeId, Optional<String> viewer, int offset, int limit) {
        List<CommentWithScore> comments = commentRepository.findByMeme(memeId, offset, limit).stream()
                .map(comment -> new CommentWithScore(comment, voting.tally(comment.id(), viewer)))
                .toList();
        return new Page(comments, commentRepository.countByMeme(memeId), offset, limit);
    }
}
