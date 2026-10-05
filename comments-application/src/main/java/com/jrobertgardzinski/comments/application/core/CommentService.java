package com.jrobertgardzinski.comments.application.core;

import com.jrobertgardzinski.comments.config.core.RateLimit;
import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.comments.system.core.AddComment;
import com.jrobertgardzinski.comments.system.core.CommentWithScore;
import com.jrobertgardzinski.comments.system.core.DeleteComment;
import com.jrobertgardzinski.comments.system.core.HideComment;
import com.jrobertgardzinski.comments.system.core.ListComments;
import com.jrobertgardzinski.identity.UserId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A meme's comment thread from the outside: commenting, reading the thread, hiding and deleting a
 * comment. Who the caller is arrives already proven; what they may do is decided here.
 */
public final class CommentService {

    private final AddComment addComment;
    private final ListComments listComments;
    private final HideComment hideComment;
    private final DeleteComment deleteComment;
    private final RateLimit commentRate;

    public CommentService(AddComment addComment, ListComments listComments, HideComment hideComment,
                          DeleteComment deleteComment, RateLimit commentRate) {
        this.addComment = addComment;
        this.listComments = listComments;
        this.hideComment = hideComment;
        this.deleteComment = deleteComment;
        this.commentRate = commentRate;
    }

    /** The text is judged before the rate is asked, so a refused text costs the author nothing. */
    public Commenting add(String memeId, UserId author, String text) {
        if (text == null || text.isBlank()) {
            return new Commenting.Invalid();
        }
        if (text.length() > Comment.MAX_LENGTH) {
            return new Commenting.TooLong(Comment.MAX_LENGTH);
        }
        if (!commentRate.tryAcquire(author.toString())) {
            return new Commenting.RateLimited();
        }
        return addComment.execute(memeId, author, text)
                .<Commenting>map(comment -> new Commenting.Added(comment.id()))
                .orElseGet(Commenting.NoSuchMeme::new);
    }

    /** One page of the thread, as {@code viewer} may see it; null for somebody not signed in. */
    public List<CommentWithScore> list(String memeId, UserId viewer, int offset, int limit) {
        return listComments.execute(memeId, Optional.ofNullable(viewer), offset, limit).comments();
    }

    /** Hides or reveals a comment — a moderator's soft touch; the comment stays as a tombstone. */
    public Hiding hide(String memeId, String commentId, Boolean hidden, Set<String> roles) {
        if (hidden == null) {
            // an absent flag is a malformed request, not a request to reveal — refused loudly
            // instead of silently defaulting to false
            return new Hiding.MissingHidden();
        }
        return switch (hideComment.execute(memeId, commentId, hidden, isModerator(roles)).status()) {
            case UPDATED -> new Hiding.Updated(hidden);
            case FORBIDDEN -> new Hiding.NotAModerator();
            case NO_SUCH_COMMENT -> new Hiding.NoSuchComment();
        };
    }

    /** Removes a comment: its author may remove their own, a moderator anyone's. */
    public Deletion delete(String memeId, String commentId, UserId caller, Set<String> roles) {
        DeleteComment.Result result = deleteComment.execute(memeId, commentId, caller, isModerator(roles));
        return switch (result.status()) {
            case DELETED -> new Deletion.Deleted(result.byModerator());
            case FORBIDDEN -> new Deletion.NotYours();
            case NO_SUCH_COMMENT -> new Deletion.NoSuchComment();
        };
    }

    private static boolean isModerator(Set<String> roles) {
        return roles != null && (roles.contains("MODERATOR") || roles.contains("ADMIN"));
    }

    public sealed interface Commenting {
        record Added(String id) implements Commenting {}

        record Invalid() implements Commenting {}

        record TooLong(int maxLength) implements Commenting {}

        record RateLimited() implements Commenting {}

        record NoSuchMeme() implements Commenting {}
    }

    public sealed interface Hiding {
        record Updated(boolean hidden) implements Hiding {}

        record MissingHidden() implements Hiding {}

        record NotAModerator() implements Hiding {}

        record NoSuchComment() implements Hiding {}
    }

    public sealed interface Deletion {
        record Deleted(boolean byModerator) implements Deletion {}

        record NotYours() implements Deletion {}

        record NoSuchComment() implements Deletion {}
    }
}
