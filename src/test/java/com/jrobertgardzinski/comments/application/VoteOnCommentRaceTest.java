package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The vote-vs-delete race after V3: the comment passes the existence check, is deleted by someone
 * else, and the store's foreign key then refuses the ballot as {@link CommentVotes.UnknownComment}.
 * The use case answers exactly as if the check itself had failed — an empty result, which the
 * controller already renders as its check-then-act 404 — instead of letting the store's refusal
 * escape as a 500.
 */
class VoteOnCommentRaceTest {

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

    /** A store whose comment vanished between the check and the cast — the FK already refuses. */
    private final CommentVotes commentGoneMidVote = new CommentVotes() {
        public void cast(String commentId, String voter, VoteDirection direction) {
            throw new UnknownComment(commentId, new RuntimeException("FK violation"));
        }
        public void retract(String commentId, String voter) { }
        public Optional<VoteDirection> voteOf(String commentId, String voter) { return Optional.empty(); }
        public int scoreOf(String commentId) { return 0; }
        public void purgeComment(String commentId) { }
        public void purgeVoter(String voter) { }
    };

    @Test
    @DisplayName("a comment deleted mid-vote reads as 'no such comment', not as an error")
    void deleted_mid_vote_is_an_empty_result() {
        Optional<VoteTally> tally = new VoteOnComment(repository, commentGoneMidVote)
                .execute("m1", "c1", "voter@example.com", VoteDirection.UP);

        assertEquals(Optional.empty(), tally,
                "the FK refusal must collapse into the same answer as a failed existence check");
    }
}
