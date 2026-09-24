package com.jrobertgardzinski.comments.config;

/**
 * What happens to a leaver's comment (this service's axis of the account-deletion saga): delete
 * it; keep it with the author anonymised; or decide by popularity. The textual vocabulary
 * ({@code DELETE}, {@code ANONYMIZE_AUTHOR}, {@code KEEP_POPULAR_ANONYMIZED:100}) is shared with
 * the saga command and the deletion wizard — deliberately the same one microservice-memes speaks
 * for its own axis.
 */
public sealed interface PurgeRule {

    record Delete() implements PurgeRule {}

    record AnonymizeAuthor() implements PurgeRule {}

    record KeepPopularAnonymized(int minScore) implements PurgeRule {
        public KeepPopularAnonymized {
            if (minScore < 1) {
                throw new IllegalArgumentException("minScore must be at least 1, was " + minScore);
            }
        }
    }

    default boolean keeps(int score) {
        return switch (this) {
            case Delete() -> false;
            case AnonymizeAuthor() -> true;
            case KeepPopularAnonymized(int minScore) -> score >= minScore;
        };
    }

    static PurgeRule parse(String text) {
        if ("DELETE".equals(text)) {
            return new Delete();
        }
        if ("ANONYMIZE_AUTHOR".equals(text)) {
            return new AnonymizeAuthor();
        }
        if (text != null && text.startsWith("KEEP_POPULAR_ANONYMIZED:")) {
            try {
                return new KeepPopularAnonymized(
                        Integer.parseInt(text.substring("KEEP_POPULAR_ANONYMIZED:".length())));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("not a purge rule: " + text);
            }
        }
        throw new IllegalArgumentException("not a purge rule: " + text);
    }
}
