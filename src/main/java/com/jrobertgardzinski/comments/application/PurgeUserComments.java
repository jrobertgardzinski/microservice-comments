package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.config.PurgeRule;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.DeletedAccount;

import java.util.Optional;

/**
 * The IRREVERSIBLE half of the comments axis of an account deletion. It runs on the orchestrator's
 * closure command — after every participant has confirmed its reversible mark, so after the last
 * moment at which the saga could still decide to compensate. It acts on exactly the comments this
 * service reserved ({@link CommentErasure#pendingOf(String)}), never on everything by that author:
 * a comment written after the mark belongs to no saga.
 *
 * <p>The {@link PurgeRule} (deployment default, or the leaver's wizard choice carried with the saga
 * command) decides each reserved comment's fate by its score — keep it as "deleted account", or
 * delete it with its votes. A comment the rule KEEPS comes back into its thread under the
 * placeholder author: the mark was a reservation, and a comment nobody is erasing must not stay
 * hidden — or sit in the erasure backlog for ever. Votes the leaver cast are always retracted.
 * Idempotent — the second delivery finds nothing reserved.
 *
 * <p><strong>Where the pivot is, and where it is not.</strong> This service crosses no point of no
 * return of its own: its whole world is rows in one database, and a row deleted here is a row the
 * saga could in principle have kept. The saga's real pivot is in microservice-memes, at the moment
 * an image leaves object storage — see its {@code PurgeUserContent}. That the orchestrator closes
 * both participants with the same command is what makes the whole case irreversible at one instant
 * rather than gradually.
 */
public class PurgeUserComments {

    private final CommentRepository commentRepository;
    private final CommentErasure erasure;
    private final CommentVotes commentVotes;
    private final PurgeRule defaultRule;

    public PurgeUserComments(CommentRepository commentRepository, CommentErasure erasure,
                             CommentVotes commentVotes, PurgeRule defaultRule) {
        this.commentRepository = commentRepository;
        this.erasure = erasure;
        this.commentVotes = commentVotes;
        this.defaultRule = defaultRule;
    }

    public void execute(String author, Optional<PurgeRule> requested) {
        PurgeRule rule = requested.orElse(defaultRule);
        for (Comment comment : erasure.pendingOf(author)) {
            if (rule.keeps(commentVotes.scoreOf(comment.id()))) {
                commentRepository.reassignAuthor(comment.id(), DeletedAccount.AUTHOR);
                erasure.store(comment.restore());   // kept: back into the thread, anonymised
            } else {
                commentVotes.purgeComment(comment.id());
                commentRepository.delete(comment.id());
            }
        }
        commentVotes.purgeVoter(author);
    }
}
