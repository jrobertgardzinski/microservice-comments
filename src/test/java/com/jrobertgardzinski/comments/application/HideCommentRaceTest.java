package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hide-vs-delete race: the comment passes the existence check, is deleted by someone else,
 * and the store's foreign key then refuses the flag as {@link CommentModeration.UnknownComment}.
 * The use case answers exactly as if the check itself had failed — NO_SUCH_COMMENT, which the
 * controller already renders as its check-then-act 404 — instead of letting the store's refusal
 * escape as a 500.
 */
class HideCommentRaceTest {

    private static final Comment COMMENT =
            new Comment("c1", "m1", "author@example.com", "soon to be gone");

    private final CommentRepository repository = new CommentRepository() {
        public void save(Comment comment) { }
        public List<Comment> findByMeme(String memeId) { return List.of(COMMENT); }
        public List<Comment> findByMeme(String memeId, int offset, int limit) { return List.of(COMMENT); }
        public int countByMeme(String memeId) { return 1; }
        public Optional<Comment> find(String commentId) { return Optional.of(COMMENT); }
        public List<Comment> findByAuthor(String author) { return List.of(); }
        public void delete(String commentId) { }
        public void deleteByMeme(String memeId) { }
        public void reassignAuthor(String commentId, String newAuthor) { }
    };

    /** A store whose comment vanished between the check and the flag — the FK already refuses. */
    private final CommentModeration commentGoneMidHide = new CommentModeration() {
        public void setHidden(String commentId, boolean hidden) {
            throw new UnknownComment(commentId, new RuntimeException("FK violation"));
        }
        public boolean isHidden(String commentId) { return false; }
        public Set<String> hiddenIn(String memeId) { return Set.of(); }
    };

    @Test
    @DisplayName("a comment deleted mid-hide reads as 'no such comment', not as an error")
    void deleted_mid_hide_is_no_such_comment() {
        HideComment.Status status = new HideComment(repository, commentGoneMidHide)
                .execute("c1", true, true);

        assertEquals(HideComment.Status.NO_SUCH_COMMENT, status,
                "the FK refusal must collapse into the same answer as a failed existence check");
    }
}
