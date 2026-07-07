package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteTally;

/**
 * A comment with its tally through the viewer's eyes — what the listing shows. {@code hidden} is a
 * moderator's soft flag: readers see a tombstone (no text), while the author still sees their own
 * with a "hidden by a moderator" note, so a listing can tell the two apart without a second query.
 */
public record CommentWithScore(Comment comment, VoteTally tally, boolean hidden, boolean viewerIsAuthor) {
}
