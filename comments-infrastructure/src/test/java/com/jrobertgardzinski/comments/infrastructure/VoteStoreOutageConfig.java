package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.voting.VoteDirection;
import com.jrobertgardzinski.voting.VoteTally;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The real JDBC vote store behind a breaker scenarios can trip: while "down", every call fails
 * the way a lost database would, so Cucumber can watch the thread listing degrade (score unknown)
 * instead of dying with it. Off by default and reset before each scenario — every other scenario
 * sees the store exactly as before.
 */
@TestConfiguration
public class VoteStoreOutageConfig {

    /** Delegates to the real store until {@link #outage(boolean)} pulls the plug. */
    public static final class OutageProneCommentVotes implements CommentVotes {

        private final CommentVotes real;
        private final AtomicBoolean down = new AtomicBoolean(false);

        OutageProneCommentVotes(CommentVotes real) {
            this.real = real;
        }

        public void outage(boolean storeIsDown) {
            down.set(storeIsDown);
        }

        private CommentVotes store() {
            if (down.get()) {
                throw new IllegalStateException("the vote store is down (scenario-staged outage)");
            }
            return real;
        }

        @Override
        public void cast(String targetId, String voter, VoteDirection direction) {
            store().cast(targetId, voter, direction);
        }

        @Override
        public void retract(String targetId, String voter) {
            store().retract(targetId, voter);
        }

        @Override
        public Optional<VoteDirection> voteOf(String targetId, String voter) {
            return store().voteOf(targetId, voter);
        }

        @Override
        public int scoreOf(String targetId) {
            return store().scoreOf(targetId);
        }

        @Override
        public Map<String, VoteTally> tallyAll(List<String> commentIds, Optional<String> viewer) {
            return store().tallyAll(commentIds, viewer);
        }

        @Override
        public void purgeComment(String commentId) {
            store().purgeComment(commentId);
        }

        @Override
        public void purgeVoter(String voter) {
            store().purgeVoter(voter);
        }
    }

    @Bean
    @Primary
    public OutageProneCommentVotes outageProneCommentVotes(JdbcCommentVotes real) {
        return new OutageProneCommentVotes(real);
    }
}
