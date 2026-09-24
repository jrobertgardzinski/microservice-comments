package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /memes/{memeId}/comments/{commentId}} addresses a comment IN a thread, and the thread is
 * part of the address rather than decoration: a request that names the wrong meme names no comment.
 * Authority is not the issue — the author is still the author whichever URL they use — but a server
 * that confirms "DELETED /memes/A/comments/x" while the comment hung under meme B has told every
 * cache, log and audit downstream about a conversation that never happened.
 */
@Epic("Infrastructure")
@Feature("Comments HTTP API")
@Story("A comment is addressed through its own thread")
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:comments_address;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class CommentAddressIncludesItsThreadTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String THREAD = "/memes/" + TestAuthConfig.EXISTING_MEME + "/comments";
    private static final String ANOTHER_MEME = "/memes/some-other-meme/comments";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("the author cannot delete their comment through another meme's URL")
    void deleting_through_the_wrong_thread_finds_nothing() throws Exception {
        String commentId = comment("the author will try the wrong URL");

        mockMvc.perform(delete(ANOTHER_MEME + "/" + commentId)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN))
                .andExpect(status().isNotFound());

        assertNotNull(inTheThread(commentId),
                "the comment hangs under another meme than the URL named, so nothing there was "
                        + "the caller's to delete — it must still be in its own thread");
    }

    @Test
    @DisplayName("a moderator cannot hide a comment through another meme's URL")
    void hiding_through_the_wrong_thread_finds_nothing() throws Exception {
        String commentId = comment("a moderator will try the wrong URL");

        mockMvc.perform(put(ANOTHER_MEME + "/" + commentId + "/hidden")
                        .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN)
                        .contentType("application/json").content("{\"hidden\":true}"))
                .andExpect(status().isNotFound());

        assertFalse(inTheThread(commentId).path("hidden").asBoolean(false),
                "a hide confirmed against a URL that names the wrong thread would be a moderation "
                        + "record of a conversation that never happened");
    }

    private String comment(String text) throws Exception {
        String body = mockMvc.perform(post(THREAD)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asText();
    }

    /** The listed entry with this id, or null — the tests share one thread, so they ask by id. */
    private JsonNode inTheThread(String commentId) throws Exception {
        String body = mockMvc.perform(get(THREAD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode entry : JSON.readTree(body)) {
            if (commentId.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        return null;
    }
}
