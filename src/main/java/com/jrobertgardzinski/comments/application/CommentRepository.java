package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.util.List;
import java.util.Optional;

/** Port for storing comments, keyed by the meme they belong to. Backed by Postgres. */
public interface CommentRepository {

    void save(Comment comment);

    List<Comment> findByMeme(String memeId);

    Optional<Comment> find(String commentId);

    List<Comment> findByAuthor(String author);

    void delete(String commentId);

    void deleteByMeme(String memeId);

    /** Replace one comment's author (account deletion may keep the text, never the identity). */
    void reassignAuthor(String commentId, String newAuthor);
}
