package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.AddComment;
import com.jrobertgardzinski.comments.application.CommentWithScore;
import com.jrobertgardzinski.comments.application.DeleteComment;
import com.jrobertgardzinski.comments.application.ListComments;
import com.jrobertgardzinski.comments.application.VoteOnComment;
import com.jrobertgardzinski.comments.config.RateLimit;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web boundary of the comments service — the same URLs the meme gallery always used
 * ({@code /memes/{memeId}/comments...}), now answered here. Posting and voting require signing in
 * (the identity comes from {@link RequireSignInFilter}); reading is public.
 */
@RestController
@RequestMapping("/memes/{memeId}/comments")
class CommentController {

    /** What a client posts to comment; the author comes from the session, not the body. */
    record CommentRequest(String text) {}

    /** What a client posts to vote: {@code direction} is UP or DOWN (case-insensitive). */
    record VoteRequest(String direction) {}

    /** Server policy: a thread page is at most this many comments. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AddComment addComment;
    private final ListComments listComments;
    private final VoteOnComment voteOnComment;
    private final DeleteComment deleteComment;
    private final RateLimit commentRate;

    CommentController(AddComment addComment, ListComments listComments, VoteOnComment voteOnComment,
                      DeleteComment deleteComment, RateLimit commentRate) {
        this.addComment = addComment;
        this.listComments = listComments;
        this.voteOnComment = voteOnComment;
        this.deleteComment = deleteComment;
        this.commentRate = commentRate;
    }

    @PostMapping
    ResponseEntity<?> add(@PathVariable("memeId") String memeId,
                          @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER) String author,
                          @RequestBody CommentRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_COMMENT"));
        }
        if (request.text().length() > Comment.MAX_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("status", "COMMENT_TOO_LONG",
                    "maxLength", Comment.MAX_LENGTH));
        }
        if (!commentRate.tryAcquire(author)) {
            return ResponseEntity.status(429).header("Retry-After", "60")
                    .body(Map.of("status", "RATE_LIMITED", "detail", "you are commenting too fast"));
        }
        return addComment.execute(memeId, author, request.text())
                .<ResponseEntity<?>>map(comment ->
                        ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", comment.id())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    List<Map<String, Object>> list(@PathVariable("memeId") String memeId,
                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                   @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                                   @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_USER,
                                           required = false) String viewer) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = Math.max(0, page) * limit;
        return listComments.execute(memeId, Optional.ofNullable(viewer), offset, limit)
                .comments().stream().map(CommentController::toBody).toList();
    }

    private static Map<String, Object> toBody(CommentWithScore entry) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", entry.comment().id());
        body.put("author", entry.comment().author());
        body.put("text", entry.comment().text());
        body.put("score", entry.tally().score());
        body.put("myVote", entry.tally().voterChoice().map(Enum::name).orElse(null));
        return body;
    }

    @PostMapping("/{commentId}/votes")
    ResponseEntity<?> vote(@PathVariable("memeId") String memeId,
                           @PathVariable("commentId") String commentId,
                           @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER) String voter,
                           @RequestBody VoteRequest request) {
        Optional<VoteDirection> direction = parseDirection(request);
        if (direction.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_DIRECTION"));
        }
        Optional<VoteTally> tally = voteOnComment.execute(memeId, commentId, voter, direction.get());
        if (tally.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("score", tally.get().score());
        body.put("myVote", tally.get().voterChoice().map(Enum::name).orElse(null));
        return ResponseEntity.ok(body);
    }

    /** Remove a comment: its author may remove their own, a MODERATOR may remove anyone's. */
    @DeleteMapping("/{commentId}")
    ResponseEntity<?> delete(@PathVariable("memeId") String memeId,
                             @PathVariable("commentId") String commentId,
                             @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER) String caller,
                             @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_ROLES,
                                     required = false) java.util.Set<String> roles) {
        boolean moderator = roles != null && (roles.contains("MODERATOR") || roles.contains("ADMIN"));
        DeleteComment.Result result = deleteComment.execute(commentId, caller, moderator);
        return switch (result.status()) {
            case DELETED -> ResponseEntity.ok(Map.of("status", "DELETED", "id", commentId,
                    "by", result.byModerator() ? "MODERATOR" : "AUTHOR"));
            case FORBIDDEN -> ResponseEntity.status(403).body(Map.of("status", "NOT_YOURS",
                    "detail", "only the author or a moderator can delete this comment"));
            case NO_SUCH_COMMENT -> ResponseEntity.notFound().build();
        };
    }

    private static Optional<VoteDirection> parseDirection(VoteRequest request) {
        try {
            return Optional.of(VoteDirection.valueOf(String.valueOf(request.direction()).trim().toUpperCase()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
