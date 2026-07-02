package com.jrobertgardzinski.comments.domain;

/**
 * A comment posted on a meme: its id, the meme it belongs to, the author and the text. Author and
 * text must not be blank; null-freedom is the boundary's responsibility (ADR 0001 — no null guards
 * in domain types).
 */
public record Comment(String id, String memeId, String author, String text) {

    public Comment {
        if (author.isBlank()) {
            throw new IllegalArgumentException("author must not be blank");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
