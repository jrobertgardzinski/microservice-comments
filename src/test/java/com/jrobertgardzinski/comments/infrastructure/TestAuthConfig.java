package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.MemeDirectory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test doubles for the two outbound dependencies: the security gate knows two users by their
 * well-known tokens, and the meme directory says one meme exists. The real HTTP adapters run in
 * the compose smoke test.
 */
@TestConfiguration
public class TestAuthConfig {

    public static final String VALID_TOKEN = "test-token";
    public static final String SIGNED_IN_USER = "alice@example.com";
    public static final String SECOND_TOKEN = "test-token-bob";
    public static final String SECOND_USER = "bob@example.com";
    public static final String MODERATOR_TOKEN = "test-token-mod";
    public static final String MODERATOR_USER = "mod@example.com";
    public static final String EXISTING_MEME = "known-meme";

    @Bean
    @Primary
    SecurityAuthenticationGate stubSecurityAuthenticationGate() {
        return token -> switch (token == null ? "" : token) {
            case VALID_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Set.of("USER")));
            case SECOND_TOKEN -> Optional.of(new Caller(SECOND_USER, Set.of("USER")));
            case MODERATOR_TOKEN -> Optional.of(new Caller(MODERATOR_USER, Set.of("USER", "MODERATOR")));
            default -> Optional.empty();
        };
    }

    @Bean
    @Primary
    MemeDirectory stubMemeDirectory() {
        return Set.of(EXISTING_MEME)::contains;
    }

    /**
     * The cascade's SECOND hop, minus the broker: what this service would have told
     * microservice-user-collections. Scenarios read it to prove the hop happened (and, just as
     * importantly, that it did not happen for an empty thread).
     */
    public static class RecordedCommentEvents implements CommentEvents {

        /** One COMMENTS_DELETED that would have gone out. */
        public record Announcement(String memeId, List<String> commentIds) {
        }

        private final List<Announcement> announced = new CopyOnWriteArrayList<>();

        @Override
        public void commentsDeleted(String memeId, List<String> commentIds) {
            announced.add(new Announcement(memeId, List.copyOf(commentIds)));
        }

        public List<Announcement> announcements() {
            return List.copyOf(announced);
        }

        /** Scenarios share one app: the clean-slate cascade must not count as an announcement. */
        public void forget() {
            announced.clear();
        }
    }

    @Bean
    @Primary
    public RecordedCommentEvents recordedCommentEvents() {
        return new RecordedCommentEvents();
    }

    /** The MEME_DELETED cascade, minus the broker: scenarios hand the listener a payload
     *  directly (Kafka listeners are disabled in tests, so the real bean is absent). The real
     *  TransactionTemplate goes in, though — since round 10 the hop's transaction is the listener's,
     *  and a scenario running it outside one would be testing an arrangement nobody deploys. */
    @Bean
    public Consumer<String> memesEventsAnnouncer(DeleteThread deleteThread,
                                                 RecordedCommentEvents commentEvents,
                                                 ObjectMapper mapper,
                                                 TransactionTemplate tx) {
        MemesEventsListener listener =
                new MemesEventsListener(deleteThread, commentEvents, mapper, tx);
        return payload -> listener.receive(payload, null);   // no cid on the direct, broker-less path
    }
}
