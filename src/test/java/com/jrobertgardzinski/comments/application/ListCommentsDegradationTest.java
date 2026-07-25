package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The listing must survive the vote store being down: comments still come back, with their
 * tallies marked unknown (null) — votes are a side dish, not the meal.
 */
class ListCommentsDegradationTest {

    private final CommentRepository repository = new CommentRepository() {
        private final List<Comment> comments = List.of(
                new Comment("c1", "m1", "alice@example.com", "first"),
                new Comment("c2", "m1", "bob@example.com", "second"));

        public void save(Comment comment) { }
        public List<Comment> findByMeme(String memeId) {
            return comments.stream().filter(c -> c.memeId().equals(memeId)).toList();
        }
        public List<Comment> findByMeme(String memeId, int offset, int limit) {
            return findByMeme(memeId).stream().skip(offset).limit(limit).toList();
        }
        public int countByMeme(String memeId) { return findByMeme(memeId).size(); }
        public Optional<Comment> find(String commentId) {
            return comments.stream().filter(c -> c.id().equals(commentId)).findFirst();
        }
        public List<Comment> findByAuthor(String author) { return List.of(); }
        public void delete(String commentId) { }
        public void deleteByMeme(String memeId) { }
        public void reassignAuthor(String commentId, String newAuthor) { }
    };

    private final CommentModeration noModeration = new CommentModeration() {
        public void setHidden(String commentId, boolean hidden) { }
        public boolean isHidden(String commentId) { return false; }
        public Set<String> hiddenIn(String memeId) { return Set.of(); }
    };

    /** A vote store whose every read fails — the database behind it is gone. */
    private final CommentVotes downVotes = new CommentVotes() {
        public void cast(String commentId, String voter, VoteDirection direction) { throw down(); }
        public void retract(String commentId, String voter) { throw down(); }
        public Optional<VoteDirection> voteOf(String commentId, String voter) { throw down(); }
        public int scoreOf(String commentId) { throw down(); }
        public Map<String, VoteTally> tallyAll(List<String> ids, Optional<String> viewer) { throw down(); }
        public void purgeComment(String commentId) { throw down(); }
        public void purgeVoter(String voter) { throw down(); }

        private RuntimeException down() {
            return new RuntimeException("vote store unavailable");
        }
    };

    @Test
    @DisplayName("when the vote store throws, the thread lists with null tallies instead of failing")
    void listing_survives_a_dead_vote_store() {
        ListComments.Page page = new ListComments(repository, noModeration, downVotes)
                .execute("m1", Optional.of("alice@example.com"), 0, 10);

        assertEquals(2, page.comments().size());
        assertTrue(page.comments().stream().allMatch(entry -> entry.tally() == null),
                "an unavailable store degrades every tally to unknown");
        // everything that does not need the vote store is still intact
        assertEquals("first", page.comments().get(0).comment().text());
        assertTrue(page.comments().get(0).viewerIsAuthor());
    }

    @Test
    @DisplayName("with a healthy store the batch read feeds every comment's tally")
    void listing_uses_the_batch_read() {
        CommentVotes healthy = new CommentVotes() {
            public void cast(String commentId, String voter, VoteDirection direction) { }
            public void retract(String commentId, String voter) { }
            public Optional<VoteDirection> voteOf(String commentId, String voter) {
                return "c1".equals(commentId) ? Optional.of(VoteDirection.UP) : Optional.empty();
            }
            public int scoreOf(String commentId) { return "c1".equals(commentId) ? 3 : 0; }
            public void purgeComment(String commentId) { }
            public void purgeVoter(String voter) { }
        };

        ListComments.Page page = new ListComments(repository, noModeration, healthy)
                .execute("m1", Optional.of("alice@example.com"), 0, 10);

        assertEquals(new VoteTally(3, Optional.of(VoteDirection.UP)), page.comments().get(0).tally());
        assertEquals(new VoteTally(0, Optional.empty()), page.comments().get(1).tally());
    }
}
