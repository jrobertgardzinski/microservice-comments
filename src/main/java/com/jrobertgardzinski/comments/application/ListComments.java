package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteTally;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lists the comments on a meme, each with its tally through the viewer's eyes (signed-in viewers
 * see which way they voted). One PAGE at a time — long threads are read in slices, so a viral
 * meme's discussion never ships as one enormous response. The tallies come in one batch read; if
 * the vote store is unavailable, the thread still lists — with the tallies marked unknown (null)
 * rather than the whole page failing over a side dish.
 */
public class ListComments {

    private static final Logger LOG = LoggerFactory.getLogger(ListComments.class);

    /**
     * A page of comments. A page shorter than {@code limit} is the last one — no total: the
     * listing stopped counting the whole thread on every read, and no client used the count.
     */
    public record Page(List<CommentWithScore> comments, int offset, int limit) {
    }

    private final CommentRepository commentRepository;
    private final CommentModeration moderation;
    private final CommentVotes commentVotes;

    public ListComments(CommentRepository commentRepository, CommentModeration moderation,
                        CommentVotes commentVotes) {
        this.commentRepository = commentRepository;
        this.moderation = moderation;
        this.commentVotes = commentVotes;
    }

    public Page execute(String memeId, Optional<String> viewer, int offset, int limit) {
        Set<String> hidden = moderation.hiddenIn(memeId);
        List<Comment> page = commentRepository.findByMeme(memeId, offset, limit);
        Map<String, VoteTally> tallies = talliesOrNull(page, viewer);
        List<CommentWithScore> comments = page.stream()
                .map(comment -> new CommentWithScore(comment,
                        tallies == null ? null
                                : tallies.getOrDefault(comment.id(), new VoteTally(0, Optional.empty())),
                        hidden.contains(comment.id()),
                        viewer.map(comment.author()::equals).orElse(false)))
                .toList();
        return new Page(comments, offset, limit);
    }

    /** All tallies in one batch; null when the vote store fails — degrade, don't take the thread down. */
    private Map<String, VoteTally> talliesOrNull(List<Comment> page, Optional<String> viewer) {
        List<String> ids = page.stream().map(Comment::id).toList();
        try {
            return commentVotes.tallyAll(ids, viewer);
        } catch (RuntimeException voteStoreDown) {
            // the FULL exception, stack trace included: this branch also swallows adapter bugs
            // (an NPE degrades the same way an outage does), and only the trace tells them apart
            LOG.warn("vote store unavailable, listing without tallies", voteStoreDown);
            return null;
        }
    }
}
