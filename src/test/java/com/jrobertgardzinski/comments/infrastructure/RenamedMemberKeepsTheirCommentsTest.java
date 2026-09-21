package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RekeyUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.observation.Observations;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member changes their e-mail address, and everything this service holds of them follows — over
 * the real web boundary, the real listener and the real schema, because every claim here is a claim
 * about a row and about what an HTTP caller is then allowed to do.
 *
 * <p>What it is for (F-014). The author column holds the address the token carried when the comment
 * was written, and nothing rewrote it: after a confirmed change of address Alice was a stranger to
 * her own words — {@code own:false}, {@code DELETE} 403 — and the address she left behind carried
 * her authorship to whoever registered it next. The third test is the other half of the same defect
 * and the more expensive one: a deletion saga naming an address this service holds nothing under
 * used to be answered with a confirmation indistinguishable from a real erasure, so the account went
 * and the words stayed.
 *
 * <p>The listeners are built by hand rather than autowired, exactly as
 * {@code PurgeConfirmationOutboxTest} does: they hang off {@code comments.kafka-enabled}, which is
 * off without a broker, and what is under test is the handling of a record and not Kafka's delivery
 * of it. Everything below them — use cases, adapters, transaction manager, Flyway schema — is the
 * application's own.
 */
@Epic("Saga")
@Feature("Address changes")
@Story("The rows follow the member")
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class RenamedMemberKeepsTheirCommentsTest {

    private static final String THREAD = "/memes/" + TestAuthConfig.EXISTING_MEME + "/comments";
    private static final String OLD_ADDRESS = TestAuthConfig.SIGNED_IN_USER;
    private static final String NEW_ADDRESS = TestAuthConfig.RENAMED_USER;
    private static final String SAGA = "3f5c2b71-6a4e-44d9-9c0e-8b1d7f2a4e33";

    private static final String RENAME = "{\"id\":\"2a7f0f5a-1c4b-3e6d-8a9b-0c1d2e3f4a5b\","
            + "\"type\":\"EMAIL_CHANGED\",\"oldEmail\":\"" + OLD_ADDRESS + "\","
            + "\"email\":\"" + NEW_ADDRESS + "\",\"version\":1}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RekeyUserComments rekeyUserComments;

    @Autowired
    MarkUserCommentsForErasure markForErasure;

    @Autowired
    RestoreUserComments restoreUserComments;

    @Autowired
    PurgeUserComments purgeUserComments;

    @Autowired
    TransactionTemplate tx;

    private SecurityEventsListener renames;
    private String commentId;

    @BeforeEach
    void aliceCommentsAndVotes() throws Exception {
        renames = new SecurityEventsListener(rekeyUserComments, objectMapper, tx);
        commentId = comment(TestAuthConfig.VALID_TOKEN, "alice was here");
        // her own ballot, because the votes are keyed by the voter's address too and a ballot left
        // behind is both a vote she cannot change and one the erasure would not find
        vote(commentId, TestAuthConfig.VALID_TOKEN);
    }

    @Test
    @DisplayName("after a confirmed rename the comments and the ballots are the NEW address's — and only its")
    void the_rows_move_to_the_new_address() throws Exception {
        assertTrue(own(commentId, TestAuthConfig.VALID_TOKEN), "before the rename she is the author");

        renames.receive(RENAME, "cid-of-the-change");

        assertTrue(own(commentId, TestAuthConfig.RENAMED_TOKEN),
                "the comment is hers under the address she now has");
        assertFalse(own(commentId, TestAuthConfig.VALID_TOKEN),
                "and not under the one she left behind — that is the address the next registrant"
                        + " would present");
        assertEquals("UP", myVote(commentId, TestAuthConfig.RENAMED_TOKEN), "her ballot moved with her");
        assertEquals("none", myVote(commentId, TestAuthConfig.VALID_TOKEN),
                "and is gone from the freed address");
        // the capability, not just the flag: authorisation reads the same column
        mockMvc.perform(delete(THREAD + "/" + commentId)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(THREAD + "/" + commentId)
                        .header("Authorization", "Bearer " + TestAuthConfig.RENAMED_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a redelivered rename changes nothing — the second UPDATE matches no row")
    void a_redelivered_rename_is_a_no_op() throws Exception {
        renames.receive(RENAME, null);
        renames.receive(RENAME, null);

        assertTrue(own(commentId, TestAuthConfig.RENAMED_TOKEN), "still hers, exactly once");
        assertEquals("UP", myVote(commentId, TestAuthConfig.RENAMED_TOKEN), "and still one ballot");
        assertEquals(0, rekeyUserComments.execute(OLD_ADDRESS, NEW_ADDRESS),
                "a third delivery finds nothing left under the old address: the idempotence is the"
                        + " UPDATE's own, which is why there is no dedup table here");
    }

    @Test
    @DisplayName("a purge naming an address this service holds nothing under confirms a ZERO, not a success")
    void a_purge_that_reserved_nothing_says_so() throws Exception {
        renames.receive(RENAME, null);   // her comments now answer to the new address

        // ...and the saga is commanded for the old one: a deletion requested before the rename, or
        // an orchestrator that has not caught up. Nothing here can tell that from a member who never
        // commented, which is exactly why the confirmation reports instead of claims
        CapturedConfirmations confirmations = new CapturedConfirmations();
        new PurgeCommandsListener(markForErasure, restoreUserComments, purgeUserComments,
                confirmations, Observations.silent(), objectMapper, tx)
                .receive("{\"type\":\"PURGE_USER_CONTENT\",\"email\":\"" + OLD_ADDRESS + "\","
                        + "\"sagaId\":\"" + SAGA + "\"}", null);

        assertNotNull(confirmations.captured(), "the saga must still get an answer — withholding it"
                + " would fail the deletion of every member who never commented");
        JsonNode reserved = objectMapper.readTree(confirmations.captured().payload()).get("reserved");
        assertNotNull(reserved, "the confirmation has to SAY how much it reserved — without the"
                + " field an empty purge is spelled exactly like a real erasure");
        assertEquals(0, reserved.asInt(),
                "and the answer must not read as an erasure: nothing was reserved, so the"
                        + " confirmation says nothing was reserved");
        // the whole point of the count: the words really are still here
        assertTrue(own(commentId, TestAuthConfig.RENAMED_TOKEN),
                "the comment is still in the thread, under the address she actually uses");
    }

    /** Whether the viewer behind {@code token} is the author, as the listing reports it. */
    private boolean own(String id, String token) throws Exception {
        return entry(id, token).get("own").asBoolean();
    }

    /** The viewer's own ballot as the tally reports it — {@code "none"} when they have none. */
    private String myVote(String id, String token) throws Exception {
        JsonNode mine = entry(id, token).get("myVote");
        return mine == null || mine.isNull() ? "none" : mine.asText();
    }

    /**
     * One comment as the thread listing shows it to that viewer. Read by paging to the end rather
     * than trusting the first page: this suite shares one in-memory schema and one meme, so the
     * thread holds whatever every other class left in it.
     */
    private JsonNode entry(String id, String token) throws Exception {
        for (int page = 0; ; page++) {
            List<JsonNode> comments = page(page, token);
            for (JsonNode comment : comments) {
                if (id.equals(comment.get("id").asText())) {
                    return comment;
                }
            }
            if (comments.size() < 100) {
                throw new AssertionError("the comment is in no page of the thread: " + id);
            }
        }
    }

    private List<JsonNode> page(int page, String token) throws Exception {
        String body = mockMvc.perform(get(THREAD).param("page", String.valueOf(page))
                        .param("size", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> comments = new ArrayList<>();
        objectMapper.readTree(body).forEach(comments::add);   // the listing is a bare array
        return comments;
    }

    private void vote(String id, String token) throws Exception {
        mockMvc.perform(post(THREAD + "/" + id + "/votes")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());
    }

    private String comment(String token, String text) throws Exception {
        String body = mockMvc.perform(post(THREAD)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("text", text))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }
}
