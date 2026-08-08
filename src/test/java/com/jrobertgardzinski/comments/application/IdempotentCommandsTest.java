package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.config.PurgeRule;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteDirection;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The teeth of workspace ADR 0006 for this service: every command is idempotent BY DEFAULT —
 * running it twice must leave exactly the state one run leaves. One generic test enforces the
 * law; a command that CANNOT obey it is a DECLARED EXCEPTION and carries its own proof below,
 * as the ADR demands.
 *
 * <p>This class used to claim "every command … the ONE command that cannot obey it" while covering
 * three of the service's six and naming one exception. Hiding was idempotent and simply unenforced;
 * voting BREAKS the law by design — a second identical vote retracts it, which the feature file
 * pins as its own scenario — and was neither enforced nor declared. A registry that quietly omits
 * the interesting cases is worse than no registry, because it reads like proof.
 */
class IdempotentCommandsTest {

    /** A fresh little world per run: two threads, three comments, some votes. */
    private static final class World {
        final List<Comment> comments = new ArrayList<>();
        final Map<String, Map<String, VoteDirection>> votes = new HashMap<>();

        final CommentRepository repository = new CommentRepository() {
            public void save(Comment comment) { comments.add(comment); }
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
        final CommentVotes commentVotes = new CommentVotes() {
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
            public void purgeComment(String commentId) { votes.remove(commentId); }
            public void purgeVoter(String voter) { votes.values().forEach(v -> v.remove(voter)); }
        };

        World() {
            comments.add(new Comment("c1", "m1", "alice@example.com", "first"));
            comments.add(new Comment("c2", "m1", "bob@example.com", "second"));
            comments.add(new Comment("c3", "m2", "alice@example.com", "elsewhere"));
            votes.put("c1", new HashMap<>(Map.of("bob@example.com", VoteDirection.UP)));
            votes.put("c3", new HashMap<>(Map.of("bob@example.com", VoteDirection.DOWN)));
        }

        final Map<String, Boolean> hidden = new HashMap<>();

        final com.jrobertgardzinski.comments.application.CommentModeration moderation =
                new com.jrobertgardzinski.comments.application.CommentModeration() {
                    public void setHidden(String commentId, boolean isHidden) {
                        hidden.put(commentId, isHidden);
                    }

                    public boolean isHidden(String commentId) {
                        return hidden.getOrDefault(commentId, false);
                    }

                    public java.util.Set<String> hiddenIn(String memeId) {
                        return hidden.entrySet().stream().filter(Map.Entry::getValue)
                                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                    }
                };

        final FakeCommentErasure erasure = new FakeCommentErasure(comments);

        Map<String, Object> fingerprint() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("comments", List.copyOf(comments));
            // the saga's reservations are state like any other: a redelivered mark that MOVED an
            // old obligation's timestamp is exactly the drift ADR 0006 forbids, and it would be
            // invisible without this line
            f.put("marks", erasure.marks());
            f.put("hidden", Map.copyOf(hidden));
            f.put("votes", votes.entrySet().stream().collect(
                    LinkedHashMap::new, (m, e) -> m.put(e.getKey(), Map.copyOf(e.getValue())),
                    Map::putAll));
            return f;
        }
    }

    /** A stopped clock: two runs of the same command must not differ by when they ran. */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            java.time.Instant.parse("2026-08-08T10:00:00Z"), java.time.ZoneOffset.UTC);

    private static final Map<String, Consumer<World>> COMMANDS = commands();

    private static Map<String, Consumer<World>> commands() {
        Map<String, Consumer<World>> c = new LinkedHashMap<>();
        c.put("delete a comment (author's own)",
                w -> new DeleteComment(w.repository, w.commentVotes)
                        .execute("c1", "alice@example.com", false));
        c.put("delete a comment that is not there",
                w -> new DeleteComment(w.repository, w.commentVotes)
                        .execute("ghost", "alice@example.com", false));
        c.put("delete a whole thread",
                w -> new DeleteThread(w.repository, w.commentVotes).execute("m1"));
        // hiding was idempotent all along and simply was not enforced — three of six commands were
        // in this map, which is not "every command" however the javadoc read
        c.put("hide a comment (moderator)",
                w -> new HideComment(w.repository, w.moderation).execute("c1", true, true));
        c.put("mark a leaver's comments for erasure",
                w -> new MarkUserCommentsForErasure(w.erasure, CLOCK).execute("alice@example.com"));
        c.put("compensate: mark, then restore",
                w -> {
                    new MarkUserCommentsForErasure(w.erasure, CLOCK).execute("alice@example.com");
                    new RestoreUserComments(w.erasure).execute("alice@example.com");
                });
        c.put("purge a leaver's comments (default rule)",
                w -> {
                    // the whole saga, both phases: the closure erases what the mark reserved
                    new MarkUserCommentsForErasure(w.erasure, CLOCK).execute("alice@example.com");
                    new PurgeUserComments(w.repository, w.erasure, w.commentVotes,
                            new PurgeRule.AnonymizeAuthor())
                            .execute("alice@example.com", Optional.empty());
                });
        return c;
    }

    @TestFactory
    Stream<DynamicTest> every_command_twice_equals_once() {
        return COMMANDS.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    World once = new World();
                    entry.getValue().accept(once);
                    World twice = new World();
                    entry.getValue().accept(twice);
                    entry.getValue().accept(twice);
                    assertEquals(once.fingerprint(), twice.fingerprint(),
                            "ADR 0006: a command run twice must leave the state of one run");
                }));
    }

    @Test
    @DisplayName("DECLARED EXCEPTION: voting twice retracts the vote — a toggle, by design")
    void voting_twice_is_the_second_declared_exception() {
        // The product decision this encodes: clicking an up-vote again means "actually, no". So the
        // second run does NOT leave the state of the first, and that is correct rather than a bug —
        // which is exactly what a declared exception is for. Left undeclared, the next person to
        // read the law above would have taken this command for an oversight and "fixed" it.
        World once = new World();
        new VoteOnComment(once.repository, once.commentVotes)
                .execute("m1", "c1", "dave@example.com", VoteDirection.UP);
        World twice = new World();
        new VoteOnComment(twice.repository, twice.commentVotes)
                .execute("m1", "c1", "dave@example.com", VoteDirection.UP);
        new VoteOnComment(twice.repository, twice.commentVotes)
                .execute("m1", "c1", "dave@example.com", VoteDirection.UP);

        assertNotEquals(once.fingerprint(), twice.fingerprint(),
                "the same vote cast twice is a retraction — the exception the ADR must name");
    }

    @Test
    @DisplayName("DECLARED EXCEPTION: adding a comment twice is two comments — by design")
    void add_comment_is_the_declared_exception() {
        World once = new World();
        MemeDirectory anyMeme = memeId -> true;
        new AddComment(anyMeme, once.repository).execute("m1", "carol@example.com", "hello");
        World twice = new World();
        new AddComment(anyMeme, twice.repository).execute("m1", "carol@example.com", "hello");
        new AddComment(anyMeme, twice.repository).execute("m1", "carol@example.com", "hello");
        assertNotEquals(once.repository.countByMeme("m1"), twice.repository.countByMeme("m1"),
                "two identical calls are two comments — the exception the ADR names, proven");
    }
}
