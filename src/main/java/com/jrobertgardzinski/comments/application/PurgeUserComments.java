package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.config.PurgeRule;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.DeletedAccount;

import java.util.Optional;

/**
 * The comments axis of an account deletion (GDPR): the {@link PurgeRule} (deployment default, or
 * the leaver's wizard choice carried with the saga command) decides each comment's fate by its
 * score — keep it as "deleted account", or delete it with its votes. Votes the leaver cast are
 * always retracted. Idempotent.
 */
public class PurgeUserComments {

    private final CommentRepository commentRepository;
    private final CommentVotes commentVotes;
    private final PurgeRule defaultRule;

    public PurgeUserComments(CommentRepository commentRepository, CommentVotes commentVotes,
                             PurgeRule defaultRule) {
        this.commentRepository = commentRepository;
        this.commentVotes = commentVotes;
        this.defaultRule = defaultRule;
    }

    public void execute(String author, Optional<PurgeRule> requested) {
        PurgeRule rule = requested.orElse(defaultRule);
        for (Comment comment : commentRepository.findByAuthor(author)) {
            if (rule.keeps(commentVotes.scoreOf(comment.id()))) {
                commentRepository.reassignAuthor(comment.id(), DeletedAccount.AUTHOR);
            } else {
                commentVotes.purgeComment(comment.id());
                commentRepository.delete(comment.id());
            }
        }
        commentVotes.purgeVoter(author);
    }
}
