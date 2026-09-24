package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.comments.application.CommentErasureContract;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.domain.Comment;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The REFERENCE. Everything the contract asks is answered here by the adapter the service
 * actually runs on, against a real database — so "the stand-in behaves like the adapter" means
 * something, instead of meaning "the stand-ins agree with each other".
 */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
@SpringBootTest(classes = CommentsApplication.class)
class JdbcCommentErasureContractTest extends CommentErasureContract {

    @Autowired
    CommentErasure erasure;

    @Autowired
    CommentRepository comments;

    @Override
    protected CommentErasure erasure() {
        return erasure;
    }

    @Override
    protected void givenActiveComment(String id, String author) {
        comments.save(new Comment(id, "a-meme", author, "a comment"));
    }
}
