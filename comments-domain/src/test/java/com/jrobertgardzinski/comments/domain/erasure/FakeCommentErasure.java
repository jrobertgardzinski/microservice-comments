package com.jrobertgardzinski.comments.domain.erasure;

import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.comments.domain.core.CommentStatus;

import java.util.Optional;
import com.jrobertgardzinski.identity.UserId;

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
 *
 * <p>Public, and this module's own test-jar publishes it: it is the one reference stand-in for
 * {@link CommentErasure}, right beside the port and {@link CommentErasureContractTest}, so a
 * consumer that needs only the erasure axis (not a full {@link CommentRepository}) never has a
 * reason to write its own.
 */
public class FakeCommentErasure implements CommentErasure {

    private final List<Comment> comments;
    private final Map<String, Instant> marks = new HashMap<>();

    public FakeCommentErasure(List<Comment> comments) {
        this.comments = comments;
    }



    @Override
    public List<Comment> activeOf(UserId author) {
        return byAuthorId(author, false);
    }

    @Override
    public List<Comment> pendingOf(UserId author) {
        return byAuthorId(author, true);
    }

    private List<Comment> byAuthorId(UserId author, boolean marked) {
        List<Comment> found = new ArrayList<>();
        for (Comment comment : comments) {
            if (comment.authorId().equals(Optional.of(author)) && marks.containsKey(comment.id()) == marked) {
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
    public List<Comment> allUnder(String memeId) {
        return comments.stream()
                .filter(comment -> comment.memeId().equals(memeId))
                .map(this::withMark)
                .toList();
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
    public boolean isMarked(String commentId) {
        return marks.containsKey(commentId);
    }

    /**
     * The reservation goes with the row it was made against — for whoever destroys that row
     * ({@link FakeCommentRepository#delete}, {@link FakeCommentRepository#deleteByMeme}).
     *
     * <p>Not a convenience: in the schema the status IS a column of the comments row, so a deleted
     * comment cannot leave a mark behind, and this class keeps the marks in a map of their own only
     * because every read has to be able to ignore them. Without this call the two part company the
     * moment anything deletes — and {@code deleteByMeme} is status-blind, exactly like the
     * adapter's cascade, so a MARKED comment really is destroyed this way — which would leave the
     * map answering {@link #isMarked} for a comment nothing holds any more.
     */
    protected void forgetMark(String commentId) {
        marks.remove(commentId);
    }

    /** The reservations, for a test that fingerprints the whole world (idempotence). */
    public Map<String, Instant> marks() {
        return Map.copyOf(marks);
    }

    /** The stored comment with its reservation showing — what the repository axis built on
     *  top of this class ({@link FakeCommentRepository}) answers reads with. */
    protected Comment withMark(Comment comment) {
        Instant marked = marks.get(comment.id());
        return marked == null
                ? comment
                : new Comment(comment.id(), comment.memeId(), comment.authorId(),
                        comment.text(), CommentStatus.PENDING_ERASURE, marked);
    }
}
