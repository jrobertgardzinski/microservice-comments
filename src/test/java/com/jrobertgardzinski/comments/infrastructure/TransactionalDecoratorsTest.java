package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.DeleteComment;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction decorators in {@link CommentsConfig} are the only thing standing between a
 * mid-teardown crash and half-done state — nothing else guarded them until this test. It takes
 * the DECORATED beans from the context (not freshly built use cases), makes the LAST port call of
 * a teardown blow up, and asserts on the real database that the earlier steps were rolled back.
 *
 * <p>The thread cascade is here for a second reason: its COMMENTS_DELETED announcement has no
 * outbox behind it, so "a rollback never announces" rests entirely on the announcement living
 * OUTSIDE the transaction (in MemesEventsListener, after the decorated use case returned). That
 * arrangement is only worth as much as a test against a real transaction manager proves it to be.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class,
        TransactionalDecoratorsTest.FailingPorts.class})
class TransactionalDecoratorsTest {

    /**
     * The real JDBC ports, each with a detonator on the call that is the last step of its
     * teardown: {@code delete} ends DeleteComment, {@code purgeVoter} ends PurgeUserComments.
     * Marked ids fail; everything else passes straight through to the same H2.
     */
    @TestConfiguration
    static class FailingPorts {

        static final Set<String> failDeleteOf = ConcurrentHashMap.newKeySet();
        static final Set<String> failPurgeVoterOf = ConcurrentHashMap.newKeySet();
        static final Set<String> failDeleteByMemeOf = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        CommentRepository failableCommentRepository(JdbcClient jdbc) {
            CommentRepository real = new JdbcCommentRepository(jdbc);
            return new CommentRepository() {
                public void save(Comment comment) { real.save(comment); }
                public List<Comment> findByMeme(String memeId) { return real.findByMeme(memeId); }
                public List<Comment> findByMeme(String memeId, int offset, int limit) {
                    return real.findByMeme(memeId, offset, limit);
                }
                public int countByMeme(String memeId) { return real.countByMeme(memeId); }
                public Optional<Comment> find(String commentId) { return real.find(commentId); }
                public List<Comment> findByAuthor(String author) { return real.findByAuthor(author); }
                public void delete(String commentId) {
                    if (failDeleteOf.contains(commentId)) {
                        throw new DataAccessResourceFailureException("forced crash on the last step");
                    }
                    real.delete(commentId);
                }
                public void deleteByMeme(String memeId) {
                    if (failDeleteByMemeOf.contains(memeId)) {
                        throw new DataAccessResourceFailureException("forced crash on the last step");
                    }
                    real.deleteByMeme(memeId);
                }
                public void reassignAuthor(String commentId, String newAuthor) {
                    real.reassignAuthor(commentId, newAuthor);
                }
            };
        }

        @Bean
        @Primary
        CommentVotes failableCommentVotes(JdbcClient jdbc) {
            CommentVotes real = new JdbcCommentVotes(jdbc);
            return new CommentVotes() {
                public void cast(String commentId, String voter, VoteDirection direction) {
                    real.cast(commentId, voter, direction);
                }
                public void retract(String commentId, String voter) { real.retract(commentId, voter); }
                public Optional<VoteDirection> voteOf(String commentId, String voter) {
                    return real.voteOf(commentId, voter);
                }
                public int scoreOf(String commentId) { return real.scoreOf(commentId); }
                public Map<String, VoteTally> tallyAll(List<String> ids, Optional<String> viewer) {
                    return real.tallyAll(ids, viewer);
                }
                public void purgeComment(String commentId) { real.purgeComment(commentId); }
                public void purgeVoter(String voter) {
                    if (failPurgeVoterOf.contains(voter)) {
                        throw new DataAccessResourceFailureException("forced crash on the last step");
                    }
                    real.purgeVoter(voter);
                }
            };
        }
    }

    @Autowired
    DeleteComment deleteComment;         // the decorated bean, transaction included
    @Autowired
    PurgeUserComments purgeUserComments; // likewise
    @Autowired
    CommentRepository repository;
    @Autowired
    CommentVotes votes;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Consumer<String> memesEventsAnnouncer;              // the MEME_DELETED listener, broker-less
    @Autowired
    TestAuthConfig.RecordedCommentEvents cascadeAnnouncements;

    @AfterEach
    void disarm() {
        FailingPorts.failDeleteOf.clear();
        FailingPorts.failPurgeVoterOf.clear();
        FailingPorts.failDeleteByMemeOf.clear();
        cascadeAnnouncements.forget();
    }

    @Test
    @DisplayName("DeleteComment: a crash on the final delete rolls the vote purge back too")
    void delete_comment_is_atomic() {
        String commentId = UUID.randomUUID().toString();
        repository.save(new Comment(commentId, UUID.randomUUID().toString(),
                "author@example.com", "doomed, but atomically"));
        votes.cast(commentId, "fan@example.com", VoteDirection.UP);
        votes.cast(commentId, "hater@example.com", VoteDirection.DOWN);

        FailingPorts.failDeleteOf.add(commentId);
        assertThrows(DataAccessResourceFailureException.class,
                () -> deleteComment.execute(commentId, "author@example.com", false));

        // the vote purge ran first and must have been rolled back with the failed delete —
        // without the decorator the votes would be gone while the comment survived
        assertTrue(repository.find(commentId).isPresent(), "the comment must survive the crash");
        assertEquals(2, voteRows(commentId), "the purged votes must be rolled back");
    }

    @Test
    @DisplayName("PurgeUserComments: a crash on the final voter purge rolls the anonymisation back")
    void purge_user_comments_is_atomic() {
        String leaver = "leaver-" + UUID.randomUUID() + "@example.com";
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        repository.save(new Comment(first, UUID.randomUUID().toString(), leaver, "one"));
        repository.save(new Comment(second, UUID.randomUUID().toString(), leaver, "two"));

        FailingPorts.failPurgeVoterOf.add(leaver);
        assertThrows(DataAccessResourceFailureException.class,
                () -> purgeUserComments.execute(leaver, Optional.empty()));

        // the default rule anonymised both comments before the crash; the rollback must have
        // restored the author — half an executed GDPR sweep is worse than a retried one
        assertEquals(2, repository.findByAuthor(leaver).size(),
                "the anonymisation must be rolled back with the failed final step");
    }

    @Test
    @DisplayName("DeleteThread: a committed cascade drops votes and thread, then names what it dropped")
    void delete_thread_announces_only_after_the_commit() {
        String memeId = UUID.randomUUID().toString();
        String first = comment(memeId, "one");
        String second = comment(memeId, "two");
        votes.cast(first, "fan@example.com", VoteDirection.UP);

        memesEventsAnnouncer.accept("{\"type\":\"MEME_DELETED\",\"memeId\":\"" + memeId + "\"}");

        assertTrue(repository.findByMeme(memeId).isEmpty(), "the thread goes with the meme");
        assertEquals(0, voteRows(first), "and so do its votes");
        assertEquals(List.of(new TestAuthConfig.RecordedCommentEvents.Announcement(
                        memeId, List.of(first, second))),
                cascadeAnnouncements.announcements(),
                "one announcement, naming exactly the comments that went");
    }

    @Test
    @DisplayName("DeleteThread: a crash on the thread delete rolls the vote purges back — and announces nothing")
    void delete_thread_is_atomic_and_silent_on_rollback() {
        String memeId = UUID.randomUUID().toString();
        String first = comment(memeId, "survives");
        String second = comment(memeId, "survives too");
        votes.cast(first, "fan@example.com", VoteDirection.UP);
        votes.cast(second, "hater@example.com", VoteDirection.DOWN);

        FailingPorts.failDeleteByMemeOf.add(memeId);
        assertThrows(DataAccessResourceFailureException.class, () -> memesEventsAnnouncer.accept(
                "{\"type\":\"MEME_DELETED\",\"memeId\":\"" + memeId + "\"}"));

        // the per-comment vote purges ran before the crash and must be back
        assertEquals(2, repository.findByMeme(memeId).size(), "the thread must survive the crash");
        assertEquals(1, voteRows(first), "the purged votes must be rolled back");
        assertEquals(1, voteRows(second));
        // and the point of announcing only from OUTSIDE the transaction: nothing left the building
        assertTrue(cascadeAnnouncements.announcements().isEmpty(),
                "a rollback must never let COMMENTS_DELETED out — the comments are still there");
    }

    /** A comment straight into the store (this test is about teardown, not about posting). */
    private String comment(String memeId, String text) {
        String id = UUID.randomUUID().toString();
        repository.save(new Comment(id, memeId, "author@example.com", text));
        return id;
    }

    private long voteRows(String commentId) {
        return jdbc.sql("SELECT COUNT(*) FROM comment_votes WHERE comment_id = ?")
                .param(commentId).query(Long.class).single();
    }
}
