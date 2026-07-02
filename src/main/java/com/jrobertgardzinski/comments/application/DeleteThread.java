package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

/**
 * Drops a meme's whole comment thread — the cascade behind a MEME_DELETED event from
 * microservice-memes: when the meme goes, its conversation goes. Idempotent.
 */
public class DeleteThread {

    private final CommentRepository commentRepository;
    private final CommentVotes commentVotes;

    public DeleteThread(CommentRepository commentRepository, CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.commentVotes = commentVotes;
    }

    public void execute(String memeId) {
        for (Comment comment : commentRepository.findByMeme(memeId)) {
            commentVotes.purgeComment(comment.id());
        }
        commentRepository.deleteByMeme(memeId);
    }
}
