package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;

import java.util.List;
import java.util.Optional;

/** Port for storing comments, keyed by the meme they belong to. Backed by Postgres. */
public interface CommentRepository {

    void save(Comment comment);

    List<Comment> findByMeme(String memeId);

    /** One page of a meme's comments, oldest first (offset/limit). */
    List<Comment> findByMeme(String memeId, int offset, int limit);

    /** The size of a meme's whole thread. Off the listing's hot path — no client used the total. */
    int countByMeme(String memeId);

    Optional<Comment> find(String commentId);

    List<Comment> findByAuthor(String author);

    void delete(String commentId);

    void deleteByMeme(String memeId);

    /** Replace one comment's author (account deletion may keep the text, never the identity). */
    void reassignAuthor(String commentId, String newAuthor);
}
