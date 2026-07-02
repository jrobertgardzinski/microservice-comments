package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.AddComment;
import com.jrobertgardzinski.comments.application.ListComments;
import com.jrobertgardzinski.comments.application.VoteOnComment;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
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

    private final AddComment addComment;
    private final ListComments listComments;
    private final VoteOnComment voteOnComment;

    CommentController(AddComment addComment, ListComments listComments, VoteOnComment voteOnComment) {
        this.addComment = addComment;
        this.listComments = listComments;
        this.voteOnComment = voteOnComment;
    }

    @PostMapping
    ResponseEntity<?> add(@PathVariable("memeId") String memeId,
                          @RequestAttribute(RequireSignInFilter.AUTHENTICATED_USER) String author,
                          @RequestBody CommentRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID_COMMENT"));
        }
        return addComment.execute(memeId, author, request.text())
                .<ResponseEntity<?>>map(comment ->
                        ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", comment.id())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    List<Map<String, Object>> list(@PathVariable("memeId") String memeId,
                                   @RequestAttribute(name = RequireSignInFilter.AUTHENTICATED_USER,
                                           required = false) String viewer) {
        return listComments.execute(memeId, Optional.ofNullable(viewer)).stream()
                .map(entry -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("id", entry.comment().id());
                    body.put("author", entry.comment().author());
                    body.put("text", entry.comment().text());
                    body.put("score", entry.tally().score());
                    body.put("myVote", entry.tally().voterChoice().map(Enum::name).orElse(null));
                    return body;
                })
                .toList();
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

    private static Optional<VoteDirection> parseDirection(VoteRequest request) {
        try {
            return Optional.of(VoteDirection.valueOf(String.valueOf(request.direction()).trim().toUpperCase()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
