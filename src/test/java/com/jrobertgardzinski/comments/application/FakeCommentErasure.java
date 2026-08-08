package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.CommentStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link CommentErasure} over the same comment list the repository fakes use, so a
 * test can run the saga's two phases end to end: mark, then erase — or mark, then restore.
 *
 * <p>The marks live in their own map rather than being written back onto the stored records,
 * exactly as the real schema keeps them on the row and out of every read: whatever is not marked is
 * ACTIVE, which is what {@code active_comments} says.
 */
class FakeCommentErasure implements CommentErasure {

    private final List<Comment> comments;
    private final Map<String, Instant> marks = new HashMap<>();

    FakeCommentErasure(List<Comment> comments) {
        this.comments = comments;
    }

    @Override
    public List<Comment> activeOf(String author) {
        return byAuthor(author, false);
    }

    @Override
    public List<Comment> pendingOf(String author) {
        return byAuthor(author, true);
    }

    private List<Comment> byAuthor(String author, boolean marked) {
        List<Comment> found = new ArrayList<>();
        for (Comment comment : comments) {
            if (comment.author().equals(author) && marks.containsKey(comment.id()) == marked) {
                found.add(withMark(comment));
            }
        }
        return found;
    }

    @Override
    public void store(Comment state) {
        if (state.isPendingErasure()) {
            marks.put(state.id(), state.markedForErasureAt());
        } else {
            marks.remove(state.id());
        }
    }

    @Override
    public List<Comment> pendingSince(Instant cutoff) {
        return comments.stream()
                .filter(comment -> marks.containsKey(comment.id()))
                .filter(comment -> marks.get(comment.id()).isBefore(cutoff))
                .map(this::withMark)
                .toList();
    }

    /** Whether this comment is out of its thread right now — what a read-side assertion asks. */
    boolean isMarked(String commentId) {
        return marks.containsKey(commentId);
    }

    /** The reservations, for a test that fingerprints the whole world (idempotence). */
    Map<String, Instant> marks() {
        return Map.copyOf(marks);
    }

    private Comment withMark(Comment comment) {
        Instant marked = marks.get(comment.id());
        return marked == null
                ? comment
                : new Comment(comment.id(), comment.memeId(), comment.author(), comment.text(),
                        CommentStatus.PENDING_ERASURE, marked);
    }
}
