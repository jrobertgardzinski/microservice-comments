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

    @Test
    @DisplayName("default purge keeps texts as 'deleted account'; KEEP_POPULAR decides by score")
    void purge_honours_the_rules() {
        comments.add(new Comment("praised", "m1", "leaver@example.com", "keeper"));
        comments.add(new Comment("ignored", "m1", "leaver@example.com", "goner"));
        votes.put("praised", new HashMap<>(Map.of("fan@example.com", VoteDirection.UP)));

        new PurgeUserComments(repository, commentVotes, new PurgeRule.AnonymizeAuthor())
                .execute("leaver@example.com", Optional.of(new PurgeRule.KeepPopularAnonymized(1)));

        assertEquals(1, comments.size());
        assertEquals("keeper", comments.get(0).text());
        assertEquals(DeletedAccount.AUTHOR, comments.get(0).author());
        assertTrue(!votes.containsKey("ignored"));
    }

    @Test
    @DisplayName("a deleted meme's whole thread goes, votes included")
    void thread_cascade() {
        comments.add(new Comment("c1", "gone-meme", "a@example.com", "one"));
        comments.add(new Comment("c2", "gone-meme", "b@example.com", "two"));
        comments.add(new Comment("c3", "other", "a@example.com", "stays"));
        votes.put("c1", new HashMap<>(Map.of("x@example.com", VoteDirection.UP)));

        new DeleteThread(repository, commentVotes).execute("gone-meme");

        assertEquals(List.of("c3"), comments.stream().map(Comment::id).toList());
        assertTrue(votes.isEmpty() || !votes.containsKey("c1"));
    }
}
