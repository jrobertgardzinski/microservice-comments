package com.jrobertgardzinski.comments.domain.core;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;

/**
 * The stand-in the closure specs run on, held to the same promises as the real adapter — including
 * the ones the account closure leans on: the leaver's ballots are retracted BEFORE any score is
 * read, and a mock answers all of that with zero.
 */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
class FakeCommentVotesTest extends CommentVotesContractTest {

    private final FakeCommentVotes votes = new FakeCommentVotes();

    @Override
    protected CommentVotes votes() {
        return votes;
    }

    @Override
    protected void givenVotableComment(String commentId) {
        // nothing to record: this stand-in is ballots and no comment store, so every id is votable
        // as far as it knows. Which comments exist is the schema's foreign key — see the contract's
        // note on UnknownComment.
    }
}
