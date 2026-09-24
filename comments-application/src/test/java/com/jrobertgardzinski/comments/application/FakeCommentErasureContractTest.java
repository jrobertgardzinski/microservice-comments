package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;

import java.util.ArrayList;
import java.util.List;

/** The stand-in these use-case tests run on, held to the same promises as the real adapter. */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
class FakeCommentErasureContractTest extends CommentErasureContract {

    private final List<Comment> comments = new ArrayList<>();
    private final FakeCommentErasure erasure = new FakeCommentErasure(comments);

    @Override
    protected CommentErasure erasure() {
        return erasure;
    }

    @Override
    protected void givenActiveComment(String id, String author) {
        comments.add(new Comment(id, "a-meme", author, "a comment"));
    }
}
