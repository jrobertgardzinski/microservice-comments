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
    /**
     * The same person as {@link #SIGNED_IN_USER}, signing in after security confirmed a change of
     * address — a new token, because the old sessions are revoked by the move, and a new subject,
     * because the subject IS the address.
     */
    public static final String RENAMED_TOKEN = "test-token-alice-renamed";
    public static final String RENAMED_USER = "alice.new@example.com";
    public static final String SECOND_TOKEN = "test-token-bob";
    public static final String SECOND_USER = "bob@example.com";
    public static final String MODERATOR_TOKEN = "test-token-mod";
    public static final String MODERATOR_USER = "mod@example.com";
    public static final String EXISTING_MEME = "known-meme";
    /** Tokens minted after the cutover: the subject is the id, the address is a claim. */
    public static final com.jrobertgardzinski.identity.UserId ALICE_ID =
            com.jrobertgardzinski.identity.UserId.of("6f1d2c3b-4a59-4e8f-9b0c-1d2e3f4a5b6c");
    public static final String ALICE_ID_TOKEN = "test-token-alice-id";
    /** The same id after a change of address. */
    public static final String ALICE_ID_RENAMED_TOKEN = "test-token-alice-id-renamed";
    /** Alice's old address under a brand-new account. */
    public static final String IMPOSTOR_TOKEN = "test-token-impostor";

    @Bean
    @Primary
    SecurityAuthenticationGate stubSecurityAuthenticationGate() {
        return token -> switch (token == null ? "" : token) {
            case VALID_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Set.of("USER")));
            case RENAMED_TOKEN -> Optional.of(new Caller(RENAMED_USER, Set.of("USER")));
            case SECOND_TOKEN -> Optional.of(new Caller(SECOND_USER, Set.of("USER")));
            case MODERATOR_TOKEN -> Optional.of(new Caller(MODERATOR_USER, Set.of("USER", "MODERATOR")));
            case ALICE_ID_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER, Optional.of(ALICE_ID), Set.of("USER")));
            case ALICE_ID_RENAMED_TOKEN -> Optional.of(new Caller(RENAMED_USER, Optional.of(ALICE_ID), Set.of("USER")));
            case IMPOSTOR_TOKEN -> Optional.of(new Caller(SIGNED_IN_USER,
                    Optional.of(com.jrobertgardzinski.identity.UserId.random()), Set.of("USER")));
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
