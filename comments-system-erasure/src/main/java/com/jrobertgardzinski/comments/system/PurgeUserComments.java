package com.jrobertgardzinski.comments.system;

import com.jrobertgardzinski.comments.domain.CommentErasure;
import com.jrobertgardzinski.comments.domain.CommentRepository;
import com.jrobertgardzinski.comments.domain.CommentVotes;
import com.jrobertgardzinski.identity.UserId;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.comments.domain.Comment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><strong>It REPORTS what it destroyed, and announces nothing.</strong> A comment this method
 * deletes may be saved in somebody else's collection, and that pointer has to go the same way it
 * goes when a meme's thread is dropped — the portal already promises it for the cascade
 * ({@code meme-deletion.feature}) and said nothing about it for a closure, so the reference
 * outlived the comment for ever. What it does NOT do is publish: this class carries a
 * transactional decorator of its own ({@code CommentsConfig}) and the announcement has to share
 * the delete's fate in both directions, so it belongs in the caller's unit of work, exactly as
 * {@link DeleteThread} hands its ids to {@code CommentsDeletionParticipant}. Grouped by meme
 * because that is the announcement's key, and a leaver's comments are scattered over many threads.
 *
 * <p>Only the DELETED ones are reported. A comment the rule keeps comes back into its thread
 * anonymised, so every pointer at it is still good and announcing it would tell collections to
 * drop a reference to something that is still there.
 *
 * <p><strong>Where the pivot is, and where it is not.</strong> This service crosses no point of no
 * return of its own: its whole world is rows in one database, and a row deleted here is a row the
 * saga could in principle have kept. The saga's real pivot is in microservice-memes, at the moment
 * an image leaves object storage — see its {@code PurgeUserContent}. That the orchestrator closes
 * both participants with the same command is what makes the whole case irreversible at one instant
 * rather than gradually.
 */
public class PurgeUserComments {

    /**
     * What the closure destroyed, keyed the way COMMENTS_DELETED is keyed. Empty when the rule kept
     * everything, or when the second delivery of an ERASE found nothing left reserved.
     *
     * @param deletedByMeme the deleted comments' ids, per meme whose thread they hung under
     */
    public record Purged(Map<String, List<String>> deletedByMeme) {

        /** Nothing was destroyed — the rule kept everything, or the ERASE was a redelivery. */
        public static final Purged NOTHING = new Purged(Map.of());

        public Purged {
            deletedByMeme = Map.copyOf(deletedByMeme);
        }

        public int count() {
            return deletedByMeme.values().stream().mapToInt(List::size).sum();
        }
    }

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

    public Purged execute(UserId author, Optional<PurgeRule> requested) {
        PurgeRule rule = requested.orElse(defaultRule);
        // FIRST, before any score is read: the leaver's own votes are leaving with him anyway, and a
        // rule like "keep what the community liked" must be answered by the COMMUNITY. Retracting
        // them afterwards meant the threshold was measured against a score that no longer existed a
        // moment later — a leaver who had upvoted his own comment bought its survival with a vote
        // this method was about to delete. The sibling service fixed the same ordering as P18 poz.
        // 39; it is also why the rule is not read at MARK time (MarkUserCommentsForErasure): the
        // mark must change nothing, and this ordering needs the votes to go first.
        commentVotes.purgeVoter(author.toString());   // ballots are keyed by the voter's id, in its wire form
        Map<String, List<String>> deleted = new LinkedHashMap<>();
        for (Comment comment : erasure.pendingOf(author)) {
            if (rule.keeps(commentVotes.scoreOf(comment.id()))) {
                commentRepository.anonymise(comment.id());
                erasure.store(comment.restore());   // kept: back into the thread, anonymised
            } else {
                commentVotes.purgeComment(comment.id());
                commentRepository.delete(comment.id());
                deleted.computeIfAbsent(comment.memeId(), meme -> new ArrayList<>()).add(comment.id());
            }
        }
        return new Purged(deleted);
    }
}
