package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.voting.VoteTally;

/** A comment with its tally through the viewer's eyes — what the listing shows. */
public record CommentWithScore(Comment comment, VoteTally tally) {
}
