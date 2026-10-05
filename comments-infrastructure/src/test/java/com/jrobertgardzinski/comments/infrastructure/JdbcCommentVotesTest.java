package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.domain.core.Comment;
import com.jrobertgardzinski.comments.domain.core.CommentRepository;
import com.jrobertgardzinski.comments.domain.core.CommentStatus;
import com.jrobertgardzinski.comments.domain.votes.CommentVotes;
import com.jrobertgardzinski.comments.domain.votes.CommentVotesContractTest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

/**
 * The REFERENCE. Everything the contract asks is answered here by the adapter the service actually
 * runs on, against a real database — so "the stand-in behaves like the adapter" means something,
 * instead of meaning "the stand-ins agree with each other".
 *
 * <p>What is NOT here is the SQL's own business: the MERGE and its race, the foreign key behind
 * {@link CommentVotes.UnknownComment} and the dialect the whole of it is spoken in
 * ({@link JdbcPersistenceTest} on H2, {@link PostgresDialectTest} on PostgreSQL itself). This file
 * is only the part a stand-in also has to get right.
 */
@Epic("Architecture")
@Feature("A stand-in behaves like the adapter it stands in for")
@SpringBootTest(classes = CommentsApplication.class)
class JdbcCommentVotesTest extends CommentVotesContractTest {

    @Autowired
    CommentVotes votes;

    @Autowired
    CommentRepository comments;

    @Override
    protected CommentVotes votes() {
        return votes;
    }

    @Override
    protected void givenVotableComment(String commentId) {
        // a real comment row, because the V3 foreign key refuses a ballot without one: every cast
        // below would otherwise be an UnknownComment rather than a vote
        comments.save(new Comment(commentId, "a-meme", Optional.empty(), "a comment",
                CommentStatus.ACTIVE, null));
    }
}
