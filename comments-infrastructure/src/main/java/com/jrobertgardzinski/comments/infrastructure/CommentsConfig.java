package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.comments.system.core.AddComment;
import com.jrobertgardzinski.comments.domain.core.CommentModeration;
import com.jrobertgardzinski.comments.domain.core.CommentRepository;
import com.jrobertgardzinski.comments.domain.votes.CommentVotes;
import com.jrobertgardzinski.comments.system.core.DeleteComment;
import com.jrobertgardzinski.comments.system.core.DeleteThread;
import com.jrobertgardzinski.comments.system.core.HideComment;
import com.jrobertgardzinski.comments.system.core.ListComments;
import com.jrobertgardzinski.comments.domain.erasure.CommentErasure;
import com.jrobertgardzinski.comments.system.erasure.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.domain.core.MemeDirectory;
import com.jrobertgardzinski.comments.system.erasure.RestoreUserComments;
import com.jrobertgardzinski.comments.system.erasure.PurgeUserComments;
import com.jrobertgardzinski.comments.system.votes.VoteOnComment;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.comments.config.core.RateLimit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
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
    com.jrobertgardzinski.authors.AuthorDirectory authorDirectory(
            @Value("${security.url}") String securityUrl,
            @Value("${comments.author-names.cache-seconds:60}") long cacheSeconds) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("comments.author-names");
        return com.jrobertgardzinski.authors.SecurityAuthorDirectory.overHttp(securityUrl,
                new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Duration.ofSeconds(cacheSeconds),
                java.time.Clock.systemUTC(),
                failure -> log.warn("author names unavailable, showing content without them: {}", failure.toString()));
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
            public Result execute(String memeId, String commentId,
                                  com.jrobertgardzinski.identity.UserId caller, boolean callerIsModerator) {
                return tx.execute(status -> super.execute(memeId, commentId, caller, callerIsModerator));
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
    com.jrobertgardzinski.comments.config.erasure.ErasureTolerance erasureTolerance(
            @Value("${comments.erasure.stuck-after-seconds:1800}") long stuckAfterSeconds) {
        return new com.jrobertgardzinski.comments.config.erasure.ErasureTolerance(
                java.time.Duration.ofSeconds(stuckAfterSeconds));
    }

    @Bean
    com.jrobertgardzinski.comments.system.erasure.WatchErasureBacklog watchErasureBacklog(
            CommentErasure erasure,
            com.jrobertgardzinski.comments.config.erasure.ErasureTolerance tolerance,
            com.jrobertgardzinski.observation.Observations<com.jrobertgardzinski.comments.domain.erasure.Observation> observations,
            java.time.Clock clock) {
        return new com.jrobertgardzinski.comments.system.erasure.WatchErasureBacklog(
                erasure, tolerance, observations, clock);
    }

    @Bean
    PurgeRule defaultCommentsPurgeRule(@Value("${comments.purge.comments:ANONYMIZE_AUTHOR}") String rule) {
        return PurgeRule.parse(rule);
    }

    @Bean
    PurgeUserComments purgeUserComments(CommentRepository commentRepository, CommentErasure erasure,
                                        CommentVotes commentVotes,
                                        PurgeRule defaultCommentsPurgeRule,
                                        PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // the whole GDPR sweep is one unit: a crash mid-purge must not leave half an account gone
        return new PurgeUserComments(commentRepository, erasure, commentVotes, defaultCommentsPurgeRule) {
            @Override
            public PurgeUserComments.Purged execute(com.jrobertgardzinski.identity.UserId author,
                                                    Optional<PurgeRule> requested) {
                // the report travels back out of the transaction: the caller announces what this
                // destroyed, and it announces INSIDE the outer transaction this one joined
                return tx.execute(status -> super.execute(author, requested));
            }
        };
    }

    /**
     * The saga's reversible step and its inverse. Neither needs a transactional decorator: both are
     * driven only by {@code PurgeCommandsListener}, which already opens ONE transaction per command
     * so the work and the outbox row reporting it commit together.
     */
    @Bean
    MarkUserCommentsForErasure markUserCommentsForErasure(CommentErasure erasure,
                                                          java.time.Clock clock) {
        return new MarkUserCommentsForErasure(erasure, clock);
    }

    @Bean
    RestoreUserComments restoreUserComments(CommentErasure erasure) {
        return new RestoreUserComments(erasure);
    }


    @Bean
    DeleteThread deleteThread(CommentRepository commentRepository, CommentErasure erasure,
                              CommentVotes commentVotes,
                              PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // per-comment vote purges + the thread delete land together (the cascade stays idempotent).
        //
        // Since round 10 this template usually JOINS an outer one rather than opening its own:
        // MemesEventsListener wraps the whole cascade hop — the thread delete AND the outbox row
        // announcing it — in a single transaction, and Spring's default propagation makes this
        // execute participate in it. That is deliberate, and it is what lets the announcement share
        // the delete's fate in both directions: a rollback discards it, a commit makes it durable.
        // The decorator stays because the use case must be atomic on its own too (nothing else
        // guarantees a caller wraps it), and because "join if there is one, open one otherwise" is
        // exactly what REQUIRED means
        return new DeleteThread(commentRepository, erasure, commentVotes) {
            @Override
            public List<String> execute(String memeId) {
                return tx.execute(status -> super.execute(memeId));
            }
        };
    }

    @Bean
    java.time.Clock clock() {
        // the outbox's row timestamps and both of its age comparisons (see CommentsOutboxConfig).
        // Declared unconditionally, though only the outbox takes it today: a bean graph whose SHAPE
        // depends on whether a broker exists is one more difference between what is tested and what
        // runs. Injectable, not Clock.systemUTC() inline, so a test can steer time.
        return java.time.Clock.systemUTC();
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

    // ---- the application services: the bridge the controller calls, mapped onto beans ----

    @Bean
    com.jrobertgardzinski.comments.application.core.CommentService commentService(AddComment addComment,
            com.jrobertgardzinski.comments.system.core.ListComments listComments,
            com.jrobertgardzinski.comments.system.core.HideComment hideComment,
            com.jrobertgardzinski.comments.system.core.DeleteComment deleteComment, RateLimit commentRate) {
        return new com.jrobertgardzinski.comments.application.core.CommentService(addComment, listComments,
                hideComment, deleteComment, commentRate);
    }

    @Bean
    com.jrobertgardzinski.comments.application.votes.CommentVoteService commentVoteService(VoteOnComment voteOnComment) {
        return new com.jrobertgardzinski.comments.application.votes.CommentVoteService(voteOnComment);
    }
}
