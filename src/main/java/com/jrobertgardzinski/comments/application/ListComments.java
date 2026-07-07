package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.Voting;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final CommentModeration moderation;
    private final Voting voting;

    public ListComments(CommentRepository commentRepository, CommentModeration moderation,
                        CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.moderation = moderation;
        this.voting = new Voting(commentVotes);
    }

    public Page execute(String memeId, Optional<String> viewer, int offset, int limit) {
        Set<String> hidden = moderation.hiddenIn(memeId);
        List<CommentWithScore> comments = commentRepository.findByMeme(memeId, offset, limit).stream()
                .map(comment -> new CommentWithScore(comment, voting.tally(comment.id(), viewer),
                        hidden.contains(comment.id()),
                        viewer.map(comment.author()::equals).orElse(false)))
                .toList();
        return new Page(comments, commentRepository.countByMeme(memeId), offset, limit);
    }
}
