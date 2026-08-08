package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dirty-read test over the real web boundary: once an account-deletion saga has marked a
 * leaver's comments, <strong>no public read of this service returns them</strong> — and the
 * compensation puts the conversation back exactly as it was, text, author and votes included.
 *
 * <p>Someone else's comment on the same meme must be untouched throughout: the mark is per author,
 * and a thread is other people's speech as much as the leaver's. That assertion is the one that
 * would catch a filter written per-thread instead of per-row.
 *
 * <p>{@code CommentReadFilterTest} is the static half of the same promise (no query may name the
 * base table); this is the behavioural half.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class MarkedCommentIsInvisibleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String THREAD = "/memes/" + TestAuthConfig.EXISTING_MEME + "/comments";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MarkUserCommentsForErasure markForErasure;

    @Autowired
    RestoreUserComments restoreUserComments;

    private String leaversComment;
    private String somebodyElsesComment;

    @BeforeEach
    void twoPeopleTalk() throws Exception {
        leaversComment = comment(TestAuthConfig.VALID_TOKEN, "the leaver's remark");
        somebodyElsesComment = comment(TestAuthConfig.SECOND_TOKEN, "bob's reply");
        // a vote on the leaver's comment: the mark must not retract it, or the compensation would
        // give the text back without the conversation that formed around it
        mockMvc.perform(post(THREAD + "/" + leaversComment + "/votes")
                        .header("Authorization", "Bearer " + TestAuthConfig.SECOND_TOKEN)
                        .contentType("application/json").content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a marked comment leaves the thread; everybody else's stays")
    void the_thread_loses_only_the_leavers_comment() throws Exception {
        assertTrue(threadIds().contains(leaversComment), "the fixture starts from a visible comment");

        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER);

        assertFalse(threadIds().contains(leaversComment), "the leaver's comment is gone from the thread");
        assertTrue(threadIds().contains(somebodyElsesComment),
                "the mark is per author: a thread is other people's speech too");
    }

    @Test
    @DisplayName("the compensation gives the comment back with its text, author and score")
    void restoring_gives_the_conversation_back() throws Exception {
        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER);

        restoreUserComments.execute(TestAuthConfig.SIGNED_IN_USER);

        JsonNode restored = thread().stream()
                .filter(comment -> leaversComment.equals(comment.get("id").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("the comment did not come back"));
        assertEquals("the leaver's remark", restored.get("text").asText());
        assertEquals(1, restored.get("score").asInt(),
                "the mark never touched the votes, so there is nothing to rebuild");
    }

    private List<String> threadIds() throws Exception {
        List<String> ids = new ArrayList<>();
        thread().forEach(comment -> ids.add(comment.get("id").asText()));
        return ids;
    }

    private List<JsonNode> thread() throws Exception {
        String body = mockMvc.perform(get(THREAD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> comments = new ArrayList<>();
        JSON.readTree(body).forEach(comments::add);   // the listing is a bare array
        return comments;
    }

    private String comment(String token, String text) throws Exception {
        String body = mockMvc.perform(post(THREAD)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(java.util.Map.of("text", text))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asText();
    }
}
