package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.domain.core.CommentStatus;
import java.util.Optional;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.comments.domain.erasure.CommentErasure;
import com.jrobertgardzinski.comments.domain.erasure.CommentErasureContractTest;
import com.jrobertgardzinski.comments.domain.core.CommentRepository;
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
class JdbcCommentErasureTest extends CommentErasureContractTest {

    @Autowired
    CommentErasure erasure;

    @Autowired
    CommentRepository comments;

    @Override
    protected CommentErasure erasure() {
        return erasure;
    }

    @Override
    protected void givenActiveComment(String id, Optional<UserId> authorId) {
        comments.save(new Comment(id, "a-meme", authorId, "a comment", CommentStatus.ACTIVE, null));
    }
}
