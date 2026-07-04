package com.jrobertgardzinski.comments.domain;

/**
 * A comment posted on a meme: its id, the meme it belongs to, the author and the text. Author and
 * text must not be blank, and the text has an upper bound — a comment is a remark, not an essay;
 * null-freedom is the boundary's responsibility (ADR 0001 — no null guards in domain types).
 */
public record Comment(String id, String memeId, String author, String text) {

    /** The longest a comment may be. A hard domain rule, not server policy. */
    public static final int MAX_LENGTH = 2000;

    public Comment {
        if (author.isBlank()) {
            throw new IllegalArgumentException("author must not be blank");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("a comment is at most " + MAX_LENGTH + " characters");
        }
    }
}
