package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.AddComment;
import com.jrobertgardzinski.comments.application.CommentModeration;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.DeleteComment;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.HideComment;
import com.jrobertgardzinski.comments.application.ListComments;
import com.jrobertgardzinski.comments.application.MemeDirectory;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.VoteOnComment;
import com.jrobertgardzinski.comments.config.PurgeRule;
import com.jrobertgardzinski.comments.config.RateLimit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Optional;

/**
 * Wires the framework-free use cases as beans and lets the gallery UI (served by
 * microservice-memes) call this service cross-origin.
 *
 * <p>The multi-step teardown use cases (delete a comment, purge a leaver, drop a thread) are
 * wrapped in a transaction HERE, as bean decorators — one crash can no longer leave votes without
 * their comment or half a purged account. The use cases themselves stay framework-free; the
 * boundary owns the transaction, exactly like it owns HTTP and Kafka.
 */
@Configuration
class CommentsConfig {

    @Bean
    AddComment addComment(MemeDirectory memeDirectory, CommentRepository commentRepository) {
        return new AddComment(memeDirectory, commentRepository);
    }

    @Bean
    RateLimit commentRate(@Value("${comments.rate-limit.per-minute:20}") int perMinute) {
        return new RateLimit(perMinute);
    }

    @Bean
    DeleteComment deleteComment(CommentRepository commentRepository, CommentVotes commentVotes,
                                PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // purge votes + delete comment must land or fail together
        return new DeleteComment(commentRepository, commentVotes) {
            @Override
            public Result execute(String commentId, String caller, boolean callerIsModerator) {
                return tx.execute(status -> super.execute(commentId, caller, callerIsModerator));
            }
        };
    }

    @Bean
    ListComments listComments(CommentRepository commentRepository, CommentModeration moderation,
                              CommentVotes commentVotes) {
        return new ListComments(commentRepository, moderation, commentVotes);
    }

    @Bean
    HideComment hideComment(CommentRepository commentRepository, CommentModeration moderation) {
        return new HideComment(commentRepository, moderation);
    }

    @Bean
    VoteOnComment voteOnComment(CommentRepository commentRepository, CommentVotes commentVotes) {
        return new VoteOnComment(commentRepository, commentVotes);
    }

    @Bean
    PurgeRule defaultCommentsPurgeRule(@Value("${comments.purge.comments:ANONYMIZE_AUTHOR}") String rule) {
        return PurgeRule.parse(rule);
    }

    @Bean
    PurgeUserComments purgeUserComments(CommentRepository commentRepository, CommentVotes commentVotes,
                                        PurgeRule defaultCommentsPurgeRule,
                                        PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // the whole GDPR sweep is one unit: a crash mid-purge must not leave half an account gone
        return new PurgeUserComments(commentRepository, commentVotes, defaultCommentsPurgeRule) {
            @Override
            public void execute(String author, Optional<PurgeRule> requested) {
                tx.executeWithoutResult(status -> super.execute(author, requested));
            }
        };
    }

    @Bean
    DeleteThread deleteThread(CommentRepository commentRepository, CommentVotes commentVotes,
                              PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // per-comment vote purges + the thread delete land together (the cascade stays idempotent)
        return new DeleteThread(commentRepository, commentVotes) {
            @Override
            public void execute(String memeId) {
                tx.executeWithoutResult(status -> super.execute(memeId));
            }
        };
    }

    @Bean
    WebMvcConfigurer corsForTheGalleryUi(@Value("${comments.ui-origin:http://localhost:8083}") String uiOrigin) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/memes/**").allowedOrigins(uiOrigin)
                        .allowedMethods("GET", "POST", "PUT", "DELETE");
            }
        };
    }
}
