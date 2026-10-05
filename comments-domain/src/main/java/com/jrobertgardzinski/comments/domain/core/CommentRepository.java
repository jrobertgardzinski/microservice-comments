package com.jrobertgardzinski.comments.domain.core;

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

    void delete(String commentId);

    void deleteByMeme(String memeId);

    /**
     * Cut one comment loose from its author: the account is gone, the thread keeps the words, and
     * the row keeps no trace of whose they were. Nothing takes the id's place — a comment nobody
     * owns has no author id, and the thread renders that as a deleted account.
     */
    void anonymise(String commentId);
}
