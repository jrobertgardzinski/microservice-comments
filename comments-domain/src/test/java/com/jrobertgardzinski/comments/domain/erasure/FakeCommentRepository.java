package com.jrobertgardzinski.comments.domain.erasure;

import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.comments.domain.core.CommentRepository;
import com.jrobertgardzinski.comments.domain.core.CommentStatus;

import com.jrobertgardzinski.identity.UserId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An in-memory {@link CommentRepository} over the same list {@link FakeCommentErasure} keeps its
 * marks against, so one object answers both axes of a comment: what it says, and whether it is
 * still in its thread.
 *
 * <p>The reads mirror the adapter's {@code active_comments} view: {@link #findByMeme} and
 * {@link #find} hide a comment {@link FakeCommentErasure#isMarked} still remembers. Only
 * {@link #deleteByMeme} is status-blind, exactly like the adapter's cascade delete — a marked
 * comment goes with the rest of its thread.
 *
 * <p>Beside the port and published by this module's test-jar, for the reason
 * {@link FakeMemeRepository} gives on the other side of the portal.
 */
public class FakeCommentRepository extends FakeCommentErasure implements CommentRepository {

    /** The rows, shared with the erasure axis above. A list, because the schema has no natural order. */
    protected final List<Comment> rows;

    public FakeCommentRepository() {
        this(new ArrayList<>());
    }

    protected FakeCommentRepository(List<Comment> rows) {
        super(rows);
        this.rows = rows;
    }

    /** One comment of this person's under a named meme. */
    public void wrote(String id, String memeId, UserId author) {
        rows.add(new Comment(id, memeId, author, "a comment"));
    }

    @Override
    public void save(Comment comment) {
        rows.add(comment);
    }

    @Override
    public List<Comment> findByMeme(String memeId) {
        return rows.stream()
                .filter(row -> row.memeId().equals(memeId))
                .filter(row -> !isMarked(row.id()))
                .toList();
    }

    @Override
    public List<Comment> findByMeme(String memeId, int offset, int limit) {
        return findByMeme(memeId).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public int countByMeme(String memeId) {
        return findByMeme(memeId).size();
    }

    @Override
    public Optional<Comment> find(String commentId) {
        return rows.stream()
                .filter(row -> row.id().equals(commentId))
                .filter(row -> !isMarked(commentId))
                .findFirst();
    }

    @Override
    public void delete(String commentId) {
        rows.removeIf(row -> row.id().equals(commentId));
        // the status is a column of the row in the schema, so it goes with it; here the marks live
        // in a map of their own and have to be told
        forgetMark(commentId);
    }

    @Override
    public void deleteByMeme(String memeId) {
        List<Comment> doomed = rows.stream().filter(row -> row.memeId().equals(memeId)).toList();
        rows.removeAll(doomed);
        doomed.forEach(row -> forgetMark(row.id()));
    }

    @Override
    public void anonymise(String commentId) {
        for (int i = 0; i < rows.size(); i++) {
            Comment held = rows.get(i);
            if (held.id().equals(commentId)) {
                rows.set(i, new Comment(held.id(), held.memeId(), Optional.empty(), held.text(),
                        CommentStatus.ACTIVE, null));
                return;
            }
        }
    }
}
