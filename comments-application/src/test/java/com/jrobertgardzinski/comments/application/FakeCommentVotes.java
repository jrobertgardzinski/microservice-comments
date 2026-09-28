package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.voting.VoteDirection;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link CommentVotes}: ballots in a map, keyed by comment and then by voter, so a
 * test can give a comment a score instead of asserting against a store that has none.
 *
 * <p>Public, and this module's own test-jar publishes it, for the reason {@link FakeCommentErasure}
 * is. Two byte-identical copies of it used to live as anonymous classes inside
 * {@code PurgeAndCascadeTest} and {@code IdempotentCommandsTest}, and a consumer one repository up
 * — the portal's closure specs, where an administrator's {@code KEEP_POPULAR_ANONYMIZED} is
 * decided per part — had neither, so it reached for a mock. A mocked {@code scoreOf} answers 0 for
 * ever, which makes the only rule that reads a score unstatable.
 *
 * <p>The tests that need a store which MISBEHAVES — one that throws, one that loses a comment
 * mid-vote — deliberately do not use this class: a stand-in that can be told to fail is a stand-in
 * nobody can read.
 */
public class FakeCommentVotes implements CommentVotes {

    private final Map<String, Map<String, VoteDirection>> votes;

    /** Over ballots the test also inspects directly. */
    public FakeCommentVotes(Map<String, Map<String, VoteDirection>> votes) {
        this.votes = votes;
    }

    /** Over ballots nobody else looks at. */
    public FakeCommentVotes() {
        this(new HashMap<>());
    }

    @Override
    public void cast(String commentId, String voter, VoteDirection direction) {
        votes.computeIfAbsent(commentId, id -> new HashMap<>()).put(voter, direction);
    }

    @Override
    public void retract(String commentId, String voter) {
        votes.getOrDefault(commentId, Map.of()).remove(voter);
    }

    @Override
    public Optional<VoteDirection> voteOf(String commentId, String voter) {
        return Optional.ofNullable(votes.getOrDefault(commentId, Map.of()).get(voter));
    }

    @Override
    public int scoreOf(String commentId) {
        return votes.getOrDefault(commentId, Map.of()).values().stream()
                .mapToInt(direction -> direction == VoteDirection.UP ? 1 : -1).sum();
    }

    @Override
    public void purgeComment(String commentId) {
        votes.remove(commentId);
    }

    @Override
    public void purgeVoter(String voter) {
        votes.values().forEach(ballots -> ballots.remove(voter));
    }
}
