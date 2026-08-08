package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build-time guard on the account-deletion filter: <strong>no read in this service may name the
 * {@code comments} table.</strong> Reads go through the {@code active_comments} view (V6), which is
 * where {@code WHERE status = 'ACTIVE'} is written down once, and a query that skips it is how a
 * leaver's content — hidden from the gallery, hidden from {@code /meta}, hidden from the ranking —
 * would come back out through some later, honestly-written SELECT.
 *
 * <p><strong>Why this shape and not an ArchUnit rule.</strong> ArchUnit reasons about TYPES: who
 * may call whom, which package may depend on which. The rule that matters here is not about types
 * at all — every read already goes through one adapter and one port — it is about the text of a SQL
 * string, and a bytecode-level rule cannot see inside a string constant. So the rule is enforced
 * where it lives: over the SQL literals themselves. (The workspace has no ArchUnit dependency, and
 * adding one to express a rule it cannot express would buy a green test and no guarantee.)
 *
 * <p>Only STRING LITERALS are scanned, never prose: this class's own javadoc says "the comments table"
 * several times, and a guard that a comment can trip is a guard that gets deleted.
 *
 * <p>The exemption list is the point of the design and is deliberately one entry long. The last
 * assertion is its counterweight: the exempt adapter MUST still contain such a query, so the rule
 * cannot be satisfied by quietly deleting the erasure feature.
 */
class CommentReadFilterTest {

    private static final Path ADAPTERS =
            Path.of("src/main/java/com/jrobertgardzinski/comments/infrastructure");

    /**
     * The one adapter that is allowed to see a marked meme, because seeing them is its whole job:
     * it marks, restores and erases, and it feeds the backlog alarm. Every other read in this
     * service is a read of a live thread.
     */
    private static final String ERASURE_ADAPTER = "JdbcCommentErasure.java";

    /**
     * {@code FROM comments} / {@code JOIN comments} — never {@code FROM active_comments} (a different word),
     * and never {@code DELETE FROM comments}, which is a WRITE. Writes have to name the table: a view
     * is not what a row is deleted from, and the erasure is allowed to delete — after the
     * orchestrator's closure, which is the whole design. It is READING a marked row that leaks it.
     */
    private static final Pattern BASE_TABLE_READ =
            Pattern.compile("(?<!DELETE )\\b(FROM|JOIN)\\s+comments\\b", Pattern.CASE_INSENSITIVE);

    /** A Java string literal, escapes included, so a SQL fragment split over "+" is still seen. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    @DisplayName("no query outside the erasure adapter reads the comments table directly")
    void every_read_goes_through_the_active_view() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            if (source.getFileName().toString().equals(ERASURE_ADAPTER)) {
                continue;
            }
            for (String sql : stringLiteralsIn(source)) {
                if (BASE_TABLE_READ.matcher(sql).find()) {
                    offenders.add(source.getFileName() + ": " + sql);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these queries bypass the ACTIVE filter and would serve content that a running "
                        + "account-deletion saga has marked for erasure. Read from active_comments "
                        + "instead — or, if the query genuinely needs to see marked rows, it "
                        + "belongs in " + ERASURE_ADAPTER + ":\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the exemption is earned: the erasure adapter really does read the table")
    void the_exemption_is_earned() throws IOException {
        // Without this, the rule above would also pass in a world where the erasure feature had
        // been deleted, or quietly moved somewhere the rule does not look. A guard that cannot
        // tell "nobody breaks it" from "there is nothing left to break" proves nothing.
        List<String> erasureQueries = stringLiteralsIn(ADAPTERS.resolve(ERASURE_ADAPTER)).stream()
                .filter(sql -> BASE_TABLE_READ.matcher(sql).find())
                .toList();
        assertFalse(erasureQueries.isEmpty(),
                ERASURE_ADAPTER + " no longer reads the comments table, so the rule above is passing "
                        + "for the wrong reason: either the erasure moved, or it is gone");
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> tree = Files.walk(ADAPTERS)) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> stringLiteralsIn(Path source) throws IOException {
        List<String> literals = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(Files.readString(source));
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }
}
