package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.core.CommentService;
import com.jrobertgardzinski.comments.application.votes.CommentVoteService;
import com.jrobertgardzinski.comments.system.core.CommentWithScore;
import com.jrobertgardzinski.comments.domain.core.Comment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** What a moderator sends to hide or reveal a comment. */
    record HideRequest(Boolean hidden) {}

    /** Server policy: a thread page is at most this many comments. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final CommentService comments;
    private final CommentVoteService votes;
    private final com.jrobertgardzinski.authors.AuthorDirectory authors;

    CommentController(CommentService comments, CommentVoteService votes,
                      com.jrobertgardzinski.authors.AuthorDirectory authors) {
        this.comments = comments;
        this.votes = votes;
        this.authors = authors;
    }

    @PostMapping
    ResponseEntity<?> add(@PathVariable("memeId") String memeId,
                          @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER_ID)
                          com.jrobertgardzinski.identity.UserId authorId,
                          @RequestBody CommentRequest request) {
        return switch (comments.add(memeId, authorId, request.text())) {
            case CommentService.Commenting.Added added ->
                    ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", added.id()));
            case CommentService.Commenting.Invalid invalid ->
                    ResponseEntity.badRequest().body(Map.of("status", "INVALID_COMMENT"));
            case CommentService.Commenting.TooLong tooLong -> ResponseEntity.badRequest().body(Map.of("status", "COMMENT_TOO_LONG",
                    "maxLength", tooLong.maxLength()));
            case CommentService.Commenting.RateLimited limited -> ResponseEntity.status(429).header("Retry-After", "60")
                    .body(Map.of("status", "RATE_LIMITED", "detail", "you are commenting too fast"));
            case CommentService.Commenting.NoSuchMeme none -> ResponseEntity.notFound().build();
        };
    }

    @GetMapping
    List<Map<String, Object>> list(@PathVariable("memeId") String memeId,
                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                   @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                                   @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_USER_ID,
                                           required = false) com.jrobertgardzinski.identity.UserId viewer) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        // long arithmetic on purpose: page * limit in ints overflows for an absurd page number,
        // and a NEGATIVE offset reaches the database as a broken statement (a bare 500) instead
        // of the empty page an out-of-range page honestly is. The port pages in ints, so the far
        // end is capped rather than wrapped — a thread with two billion comments does not exist,
        // and both the cap and the number it stands for list nothing. (The gallery's own listing
        // in microservice-memes takes the same care, 1c86a5a.)
        int offset = (int) Math.min((long) Math.max(0, page) * limit, Integer.MAX_VALUE);
        return comments.list(memeId, viewer, offset, limit).stream().map(this::toBody).toList();
    }

    private Map<String, Object> toBody(CommentWithScore entry) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", entry.comment().id());
        body.put("author", nameOf(entry.comment()));
        // the full author never leaves the service, so the UI cannot compare it against the
        // signed-in user any more — "own" carries that answer instead (from the viewer's token)
        body.put("own", entry.viewerIsAuthor());
        // a null tally means the vote store was unavailable: score/myVote are unknown ("n/a"
        // client-side), not zero — the thread itself still lists
        body.put("score", entry.tally() == null ? null : entry.tally().score());
        body.put("myVote", entry.tally() == null ? null
                : entry.tally().voterChoice().map(Enum::name).orElse(null));
        if (entry.hidden()) {
            // a tombstone for readers; the author still sees their own words, marked hidden
            body.put("hidden", true);
            body.put("text", entry.viewerIsAuthor() ? entry.comment().text() : null);
        } else {
            body.put("text", entry.comment().text());
        }
        return body;
    }

    /** Hide or reveal a comment — a MODERATOR-only soft touch; the comment stays as a tombstone. */
    @PutMapping("/{commentId}/hidden")
    ResponseEntity<?> hide(@PathVariable("memeId") String memeId,
                           @PathVariable("commentId") String commentId,
                           @RequestBody HideRequest request,
                           @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_ROLES,
                                   required = false) java.util.Set<String> roles) {
        return switch (comments.hide(memeId, commentId, request.hidden(), roles)) {
            case CommentService.Hiding.Updated updated ->
                    ResponseEntity.ok(Map.of("status", updated.hidden() ? "HIDDEN" : "REVEALED", "id", commentId));
            case CommentService.Hiding.MissingHidden missing -> ResponseEntity.badRequest().body(Map.of("status", "MISSING_HIDDEN",
                    "detail", "the body must carry hidden: true or false"));
            case CommentService.Hiding.NotAModerator notAModerator -> ResponseEntity.status(403).body(Map.of("status", "NOT_A_MODERATOR",
                    "detail", "only a moderator can hide a comment"));
            case CommentService.Hiding.NoSuchComment none -> ResponseEntity.notFound().build();
        };
    }

    @PostMapping("/{commentId}/votes")
    ResponseEntity<?> vote(@PathVariable("memeId") String memeId,
                           @PathVariable("commentId") String commentId,
                           @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER_ID)
                           com.jrobertgardzinski.identity.UserId voter,
                           @RequestBody VoteRequest request) {
        return switch (votes.vote(memeId, commentId, voter, request.direction())) {
            case CommentVoteService.Vote.InvalidDirection invalid ->
                    ResponseEntity.badRequest().body(Map.of("status", "INVALID_DIRECTION"));
            case CommentVoteService.Vote.NoSuchComment none -> ResponseEntity.notFound().build();
            case CommentVoteService.Vote.Counted counted -> {
                Map<String, Object> body = new HashMap<>();
                body.put("score", counted.tally().score());
                body.put("myVote", counted.tally().voterChoice().map(Enum::name).orElse(null));
                yield ResponseEntity.ok(body);
            }
        };
    }

    /** Remove a comment: its author may remove their own, a MODERATOR may remove anyone's. */
    @DeleteMapping("/{commentId}")
    ResponseEntity<?> delete(@PathVariable("memeId") String memeId,
                             @PathVariable("commentId") String commentId,
                             @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER_ID)
                             com.jrobertgardzinski.identity.UserId caller,
                             @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_ROLES,
                                     required = false) java.util.Set<String> roles) {
        return switch (comments.delete(memeId, commentId, caller, roles)) {
            case CommentService.Deletion.Deleted deleted -> ResponseEntity.ok(Map.of("status", "DELETED", "id", commentId,
                    "by", deleted.byModerator() ? "MODERATOR" : "AUTHOR"));
            case CommentService.Deletion.NotYours notYours -> ResponseEntity.status(403).body(Map.of("status", "NOT_YOURS",
                    "detail", "only the author or a moderator can delete this comment"));
            case CommentService.Deletion.NoSuchComment none -> ResponseEntity.notFound().build();
        };
    }

    /** The name security shows for the author's id; a row without one, or with one security no longer knows, is a deleted account. */
    private String nameOf(Comment comment) {
        return comment.authorId()
                .map(id -> authors.namesOf(java.util.List.of(id)).getOrDefault(id,
                        new com.jrobertgardzinski.authors.AuthorName(DELETED_ACCOUNT)).display())
                .orElse(DELETED_ACCOUNT);
    }

    private static final String DELETED_ACCOUNT = "deleted account";


}
