package com.jrobertgardzinski.comments.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
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
 * The build-time guard on the retired key: <strong>the author id is the key of a row, the e-mail
 * address is not.</strong> The cutover moved every read, every ownership check and the whole
 * closure onto the id; what it cannot do is stop the next honestly-written SELECT from matching
 * comments by address again. A query like that looks harmless and quietly brings back the four
 * defects the id was adopted to end: a member who renames becomes a stranger to their own content,
 * their closure marks nothing while confirming an erasure, and whoever registers the freed address
 * next inherits what was left under it.
 *
 * <p><strong>Why this shape and not an ArchUnit rule.</strong> The same reason
 * {@code MemeReadFilterTest} gives: the rule is about the text of a SQL string, and a
 * bytecode-level rule cannot see inside a string constant. So it is enforced over the literals.
 *
 * <p>Only STRING LITERALS are scanned for the predicate, never prose — this javadoc names the
 * forbidden column several times, and a guard that a comment can trip is a guard that gets deleted.
 * The retired-machinery rule below is the opposite: it reads the whole file, identifiers included,
 * because {@code Rekey} and {@code EMAIL_CHANGED} must not come back as a class or a topic name
 * either.
 *
 * <p>What is deliberately NOT forbidden, each for a recorded reason:
 * <ul>
 *   <li>nothing in this service writes an address any more: the column is gone, the anonymisation
 *       only clears the id, and the name a thread shows is fetched from security at read time.</li>
 *   <li>{@code voter} on {@code comment_votes} holds the voter's id in its wire form (the 1e
 *       decision: the Ballots API was left untouched), so the word is not an address at all.</li>
 * </ul>
 *
 * <p>The last test is the counterweight: the scan MUST have seen SQL at all, so the rules above
 * cannot pass in a world where the queries moved somewhere this test does not look.
 */
@Epic("Identity")
@Feature("The id is the only key")
@Story("No address-keyed SQL")
class RetiredAddressKeyTest {

    /** The whole service from this module: every module's main sources, not just this one's. */
    private static final Path SERVICE = Path.of("..");

    private static final Path SCHEMA = Path.of("src/main/resources/db/migration/V1__schema.sql");

    /**
     * {@code WHERE author =} / {@code AND author =} — never {@code author_id} (a
     * different word: the underscore is part of it), and never a write, which names the column to
     * set it.
     */
    private static final Pattern ADDRESS_PREDICATE =
            Pattern.compile("\\b(WHERE|AND|OR)\\s+author\\s*=", Pattern.CASE_INSENSITIVE);

    /** The machinery 1e deleted: the rekey use case and adapters, and the fact that drove them. */
    private static final Pattern RETIRED_MACHINERY = Pattern.compile("Rekey|EMAIL_CHANGED|user_email");

    /** A Java string literal, escapes included, so a SQL fragment split over "+" is still seen. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Enough of a query to prove the scan is looking at SQL and not at an empty directory. */
    private static final Pattern ANY_READ = Pattern.compile("\\b(FROM|JOIN)\\s+\\w", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("no query matches a row by the address")
    void every_query_is_keyed_by_the_id() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            for (String sql : stringLiteralsIn(source)) {
                if (ADDRESS_PREDICATE.matcher(sql).find()) {
                    offenders.add(source.getFileName() + ": " + sql);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these queries match comments by e-mail address, which the cutover retired as a "
                        + "key: an address moves, so the query answers for the wrong member — or for "
                        + "whoever registered it next. Match on author_id instead:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("no main source brings the rekey machinery or its fact back")
    void the_retired_machinery_stays_deleted() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            Matcher matcher = RETIRED_MACHINERY.matcher(Files.readString(source));
            if (matcher.find()) {
                offenders.add(source.getFileName() + ": " + matcher.group());
            }
        }
        assertTrue(offenders.isEmpty(),
                "1e deleted the rekey use case, its adapters and the EMAIL_CHANGED consumer, because "
                        + "content keyed by an id has nothing to re-key when an address changes. "
                        + "Something here names them again:\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the schema keeps no address-shaped key")
    void the_schema_is_keyed_by_the_id() throws IOException {
        String schema = Files.readString(SCHEMA);
        assertFalse(schema.contains("user_email"),
                "the user_email column went with the cutover; the id column is the key");
        assertFalse(Pattern.compile("(?im)^\\s*author\\s+(VARCHAR|TEXT|CHAR)", Pattern.CASE_INSENSITIVE)
                        .matcher(schema).find(),
                "the author column is gone: a comment carries the id of whoever wrote it and no "
                        + "address at all, and a column back in the table is an address back in "
                        + "the database");
        assertTrue(schema.contains("idx_comments_author_id"),
                "the index the id is looked up by is gone, so the rule above is passing for the "
                        + "wrong reason: either the key moved, or the table did");
    }

    @Test
    @DisplayName("the exemption-free rules are earned: the scan really does see SQL")
    void the_scan_sees_sql_at_all() throws IOException {
        List<String> reads = new ArrayList<>();
        for (Path source : mainSources()) {
            stringLiteralsIn(source).stream().filter(sql -> ANY_READ.matcher(sql).find()).forEach(reads::add);
        }
        assertFalse(reads.isEmpty(),
                "not one SQL read was found in this service's main sources, so the rules above prove "
                        + "nothing: the queries moved somewhere this test does not look");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> tree = Files.walk(SERVICE)) {
            return tree.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("src/main/java"))
                    .toList();
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
