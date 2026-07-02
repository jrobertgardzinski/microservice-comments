package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.util.Optional;
import java.util.UUID;

/**
 * Adds a comment to a meme the meme service confirms exists. Returns the stored comment, or empty
 * when there is no such meme.
 */
public class AddComment {

    private final MemeDirectory memeDirectory;
    private final CommentRepository commentRepository;

    public AddComment(MemeDirectory memeDirectory, CommentRepository commentRepository) {
        this.memeDirectory = memeDirectory;
        this.commentRepository = commentRepository;
    }

    public Optional<Comment> execute(String memeId, String author, String text) {
        if (!memeDirectory.exists(memeId)) {
            return Optional.empty();
        }
        Comment comment = new Comment(UUID.randomUUID().toString(), memeId, author, text);
        commentRepository.save(comment);
        return Optional.of(comment);
    }
}
