package com.jrobertgardzinski.comments.system.core;

import com.jrobertgardzinski.comments.domain.core.MemeDirectory;

import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.comments.domain.core.CommentRepository;

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

    /**
     * The author is an id. A token without one predates the cutover and is nobody here, so the gate
     * refuses it before this is ever called.
     */
    public Optional<Comment> execute(String memeId, com.jrobertgardzinski.identity.UserId author, String text) {
        if (!memeDirectory.exists(memeId)) {
            return Optional.empty();
        }
        Comment comment = new Comment(UUID.randomUUID().toString(), memeId, author, text);
        commentRepository.save(comment);
        return Optional.of(comment);
    }
}
