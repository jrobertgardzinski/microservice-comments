package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.comments.application.CommentErasure;
import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.comments.application.WatchErasureBacklog;
import com.jrobertgardzinski.comments.config.ErasureTolerance;
import com.jrobertgardzinski.comments.domain.Comment;
import com.jrobertgardzinski.comments.domain.Observation;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The boundary, asserted instead of promised: observability is a layer this service can be
 * assembled WITHOUT.
 *
 * <p>Two halves, and both are needed. The first says nothing above infrastructure has ever heard of
 * the watching tool — the day Micrometer is swapped out, the search below is what proves the blast
 * radius is one package. The second says the work itself does not depend on being watched: run the
 * same use case with silence in place of the adapter and the answer is identical.
 *
 * <p>Honest about its reach: Micrometer is on this module's classpath whatever this test says,
 * because the adapter lives here. What is pinned is the DIRECTION of the dependency — domain,
 * config and application know about facts, and only infrastructure knows what a metric is.
 */
@Epic("Architecture")
@Feature("Observability is a layer, not a dependency")
class ObservabilityIsOptionalTest {

    /** The layers that must not name a tool — everything except the adapters. */
    private static final List<Path> ABOVE_INFRASTRUCTURE = List.of(
            Path.of("src/main/java/com/jrobertgardzinski/comments/domain"),
            Path.of("src/main/java/com/jrobertgardzinski/comments/config"),
            Path.of("src/main/java/com/jrobertgardzinski/comments/application"));

    /**
     * Vendor words, not concepts. {@code observ} is deliberately absent: {@code Observation} and
     * {@code Observations} ARE the domain's own vocabulary and must be free to appear.
     */
    private static final List<String> TOOLS = List.of(
            "micrometer", "prometheus", "meterregistry", "opentelemetry", "otel",
            "traceparent", "grafana", "loki", "tempo");

    @Test
    @DisplayName("no layer above infrastructure names the tool that watches it")
    void the_tool_stays_in_the_adapter() throws IOException {
        for (Path layer : ABOVE_INFRASTRUCTURE) {
            try (Stream<Path> tree = Files.walk(layer)) {
                List<String> leaks = tree
                        .filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> mentionsATool(read(file)))
                        .map(Path::toString)
                        .toList();
                assertEquals(List.of(), leaks,
                        "these files below the adapters name a watching tool — that is the coupling"
                                + " this layering exists to prevent: " + leaks);
            }
        }
    }

    @Test
    @DisplayName("with nothing watching, the work is unchanged — the answer is the same")
    void the_service_works_unwatched() {
        Instant markedAt = Instant.parse("2026-08-08T10:00:00Z");
        Observations<Observation> silence = observation -> { };

        Observation.ErasureBacklog said = new WatchErasureBacklog(holding(markedAt),
                new ErasureTolerance(Duration.ofMinutes(30)), silence,
                Clock.fixed(markedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC)).execute();

        // the obligation is still counted and still answered to the caller; the only thing missing
        // is somebody to tell — which is what "no watcher" means and all it may mean
        assertEquals(1, said.marked());
        assertEquals(Duration.ofHours(2), said.oldest());
    }

    private static boolean mentionsATool(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return TOOLS.stream().anyMatch(lower::contains);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + file, unreadable);
        }
    }

    private static CommentErasure holding(Instant markedAt) {
        Comment marked = new Comment("c1", "m1", "leaver@example.com", "text").markForErasure(markedAt);
        return new CommentErasure() {
            public List<Comment> activeOf(String author) {
                return List.of();
            }

            public List<Comment> pendingOf(String author) {
                return List.of();
            }

            public void store(Comment state) {
            }

            public List<Comment> pendingSince(Instant cutoff) {
                return List.of(marked);
            }
        };
    }
}
