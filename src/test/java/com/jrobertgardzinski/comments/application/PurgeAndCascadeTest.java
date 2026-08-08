package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.config.PurgeRule;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.DeletedAccount;
import com.jrobertgardzinski.voting.VoteDirection;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Epic("Use case")
@Feature("Purge and thread cascade")
class PurgeAndCascadeTest {

    private final List<Comment> comments = new ArrayList<>();
    private final Map<String, Map<String, VoteDirection>> votes = new HashMap<>();

    private final CommentRepository repository = new CommentRepository() {
        public void save(Comment comment) {
            comments.add(comment);
        }

        public List<Comment> findByMeme(String memeId) {
            return comments.stream().filter(c -> c.memeId().equals(memeId)).toList();
        }

        public List<Comment> findByMeme(String memeId, int offset, int limit) {
            return findByMeme(memeId).stream().skip(offset).limit(limit).toList();
        }

        public int countByMeme(String memeId) {
            return findByMeme(memeId).size();
        }

        public Optional<Comment> find(String commentId) {
            return comments.stream().filter(c -> c.id().equals(commentId)).findFirst();
        }

        public List<Comment> findByAuthor(String author) {
            return comments.stream().filter(c -> c.author().equals(author)).toList();
        }

        public void delete(String commentId) {
            comments.removeIf(c -> c.id().equals(commentId));
        }

        public void deleteByMeme(String memeId) {
            comments.removeIf(c -> c.memeId().equals(memeId));
        }

        public void reassignAuthor(String commentId, String newAuthor) {
            comments.replaceAll(c -> c.id().equals(commentId)
                    ? new Comment(c.id(), c.memeId(), newAuthor, c.text()) : c);
        }
    };
    private final CommentVotes commentVotes = new CommentVotes() {
        public void cast(String commentId, String voter, VoteDirection direction) {
            votes.computeIfAbsent(commentId, id -> new HashMap<>()).put(voter, direction);
        }

        public void retract(String commentId, String voter) {
            votes.getOrDefault(commentId, Map.of()).remove(voter);
        }

        public Optional<VoteDirection> voteOf(String commentId, String voter) {
            return Optional.ofNullable(votes.getOrDefault(commentId, Map.of()).get(voter));
        }

        public int scoreOf(String commentId) {
            return votes.getOrDefault(commentId, Map.of()).values().stream()
                    .mapToInt(d -> d == VoteDirection.UP ? 1 : -1).sum();
        }

        public void purgeComment(String commentId) {
            votes.remove(commentId);
        }

        public void purgeVoter(String voter) {
            votes.values().forEach(v -> v.remove(voter));
        }
    };

    private final FakeCommentErasure erasure = new FakeCommentErasure(comments);
    private final java.time.Clock clock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-08-08T10:00:00Z"), java.time.ZoneOffset.UTC);
    private final MarkUserCommentsForErasure mark = new MarkUserCommentsForErasure(erasure, clock);
    private final RestoreUserComments restore = new RestoreUserComments(erasure);

    @Test
    @DisplayName("default purge keeps texts as 'deleted account'; KEEP_POPULAR decides by score")
    void purge_honours_the_rules() {
        comments.add(new Comment("praised", "m1", "leaver@example.com", "keeper"));
        comments.add(new Comment("ignored", "m1", "leaver@example.com", "goner"));
        votes.put("praised", new HashMap<>(Map.of("fan@example.com", VoteDirection.UP)));

        // the saga in full: the reversible mark, then the orchestrator's closure
        mark.execute("leaver@example.com");
        new PurgeUserComments(repository, erasure, commentVotes, new PurgeRule.AnonymizeAuthor())
                .execute("leaver@example.com", Optional.of(new PurgeRule.KeepPopularAnonymized(1)));

        assertEquals(1, comments.size());
        assertEquals("keeper", comments.get(0).text());
        assertEquals(DeletedAccount.AUTHOR, comments.get(0).author());
        assertTrue(!votes.containsKey("ignored"));
        assertTrue(!erasure.isMarked("praised"),
                "a comment the rule keeps belongs back in the thread, not in the erasure backlog");
    }

    @Test
    @DisplayName("the mark hides the leaver's comments and destroys nothing")
    void the_mark_is_reversible() {
        comments.add(new Comment("reserved", "m1", "leaver@example.com", "still here"));
        votes.put("reserved", new HashMap<>(Map.of("fan@example.com", VoteDirection.UP)));

        mark.execute("leaver@example.com");

        assertTrue(erasure.isMarked("reserved"), "out of the thread");
        assertEquals(1, comments.size(), "...and still stored");
        assertEquals(Map.of("fan@example.com", VoteDirection.UP), votes.get("reserved"));
    }

    @Test
    @DisplayName("the compensation puts the conversation back exactly as it was")
    void restore_undoes_the_mark() {
        comments.add(new Comment("reserved", "m1", "leaver@example.com", "still here"));
        mark.execute("leaver@example.com");

        restore.execute("leaver@example.com");

        assertTrue(!erasure.isMarked("reserved"));
        assertEquals("leaver@example.com", comments.get(0).author());
        assertEquals("still here", comments.get(0).text());
    }

    @Test
    @DisplayName("a closure that arrives without a mark erases nothing")
    void the_closure_only_acts_on_what_the_mark_reserved() {
        comments.add(new Comment("never-marked", "m1", "leaver@example.com", "untouched"));

        new PurgeUserComments(repository, erasure, commentVotes, new PurgeRule.Delete())
                .execute("leaver@example.com", Optional.empty());

        assertEquals(1, comments.size(),
                "the erasure acts on the reservation, never on 'everything by that author'");
    }

    @Test
    @DisplayName("a deleted meme's whole thread goes, votes included")
    void thread_cascade() {
        comments.add(new Comment("c1", "gone-meme", "a@example.com", "one"));
        comments.add(new Comment("c2", "gone-meme", "b@example.com", "two"));
        comments.add(new Comment("c3", "other", "a@example.com", "stays"));
        votes.put("c1", new HashMap<>(Map.of("x@example.com", VoteDirection.UP)));

        List<String> dropped = new DeleteThread(repository, commentVotes).execute("gone-meme");

        assertEquals(List.of("c3"), comments.stream().map(Comment::id).toList());
        assertTrue(votes.isEmpty() || !votes.containsKey("c1"));
        // the cascade also REPORTS what it took: this service is the only one that ever knew which
        // comments hung under that meme, so the next hop of the choreography rides on this list
        assertEquals(List.of("c1", "c2"), dropped, "every dropped comment, and no other meme's");
    }

    @Test
    @DisplayName("a meme nobody commented on reports nothing to pass on")
    void thread_cascade_on_an_empty_thread() {
        comments.add(new Comment("c1", "other", "a@example.com", "stays"));

        assertEquals(List.of(), new DeleteThread(repository, commentVotes).execute("quiet-meme"),
                "no comments went, so there is no fact to announce (and a rerun says the same)");
    }
}
