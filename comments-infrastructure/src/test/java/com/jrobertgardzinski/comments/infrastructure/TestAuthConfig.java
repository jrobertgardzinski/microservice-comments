package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.identity.UserId;
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
    public static final UserId SIGNED_IN_USER_ID = UserId.of("11111111-1111-4111-8111-111111111111");
    /** Alice's id, signing in after a change of address: the same person. */
    public static final String RENAMED_TOKEN = "test-token-alice-renamed";
    public static final String RENAMED_USER = "alice.new@example.com";
    public static final String SECOND_TOKEN = "test-token-bob";
    public static final String SECOND_USER = "bob@example.com";
    public static final UserId SECOND_USER_ID = UserId.of("22222222-2222-4222-8222-222222222222");
    public static final String MODERATOR_TOKEN = "test-token-mod";
    public static final String MODERATOR_USER = "mod@example.com";
    public static final UserId MODERATOR_USER_ID = UserId.of("33333333-3333-4333-8333-333333333333");
    public static final String EXISTING_MEME = "known-meme";
    /** Alice's old address under a brand-new account: somebody else. */
    public static final String IMPOSTOR_TOKEN = "test-token-impostor";

    @Bean
    @Primary
    SecurityAuthenticationGate stubSecurityAuthenticationGate() {
        return token -> switch (token == null ? "" : token) {
            case VALID_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Optional.of(SIGNED_IN_USER_ID), Set.of("USER")));
            case RENAMED_TOKEN -> Optional.of(new Caller(RENAMED_USER, Optional.of(SIGNED_IN_USER_ID), Set.of("USER")));
            case SECOND_TOKEN -> Optional.of(new Caller(SECOND_USER, Optional.of(SECOND_USER_ID), Set.of("USER")));
            case MODERATOR_TOKEN -> Optional.of(new Caller(MODERATOR_USER, Optional.of(MODERATOR_USER_ID), Set.of("USER", "MODERATOR")));
            case IMPOSTOR_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Optional.of(UserId.random()), Set.of("USER")));
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

    /**
     * The names security would show for the test accounts: the masked address, as the real
     * directory answers it. An id outside this list is an account security no longer knows.
     */
    @Bean
    @Primary
    com.jrobertgardzinski.authors.AuthorDirectory stubAuthorDirectory() {
        java.util.Map<UserId, com.jrobertgardzinski.authors.AuthorName> known = java.util.Map.of(
                SIGNED_IN_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(SIGNED_IN_USER)),
                SECOND_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(SECOND_USER)),
                MODERATOR_USER_ID, new com.jrobertgardzinski.authors.AuthorName(masked(MODERATOR_USER)));
        return ids -> {
            java.util.Map<UserId, com.jrobertgardzinski.authors.AuthorName> found = new java.util.HashMap<>();
            for (UserId id : ids) {
                if (known.containsKey(id)) {
                    found.put(id, known.get(id));
                }
            }
            return found;
        };
    }

    private static String masked(String address) {
        return address.charAt(0) + "***@" + address.substring(address.indexOf('@') + 1);
    }
}
