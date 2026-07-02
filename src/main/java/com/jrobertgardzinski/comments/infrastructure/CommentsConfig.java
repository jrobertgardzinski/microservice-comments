package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.AddComment;
import com.jrobertgardzinski.comments.application.CommentRepository;
import com.jrobertgardzinski.comments.application.CommentVotes;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.ListComments;
import com.jrobertgardzinski.comments.application.MemeDirectory;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.VoteOnComment;
import com.jrobertgardzinski.comments.config.PurgeRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the framework-free use cases as beans and lets the gallery UI (served by
 * microservice-memes) call this service cross-origin.
 */
@Configuration
class CommentsConfig {

    @Bean
    AddComment addComment(MemeDirectory memeDirectory, CommentRepository commentRepository) {
        return new AddComment(memeDirectory, commentRepository);
    }

    @Bean
    ListComments listComments(CommentRepository commentRepository, CommentVotes commentVotes) {
        return new ListComments(commentRepository, commentVotes);
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
                                        PurgeRule defaultCommentsPurgeRule) {
        return new PurgeUserComments(commentRepository, commentVotes, defaultCommentsPurgeRule);
    }

    @Bean
    DeleteThread deleteThread(CommentRepository commentRepository, CommentVotes commentVotes) {
        return new DeleteThread(commentRepository, commentVotes);
    }

    @Bean
    WebMvcConfigurer corsForTheGalleryUi(@Value("${comments.ui-origin:http://localhost:8083}") String uiOrigin) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/memes/**").allowedOrigins(uiOrigin).allowedMethods("GET", "POST");
            }
        };
    }
}
