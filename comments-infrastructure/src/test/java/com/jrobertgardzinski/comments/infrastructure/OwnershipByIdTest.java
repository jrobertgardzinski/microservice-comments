package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorship over HTTP once the token carries an id: the id outranks the address in both
 * directions, and a token without one still gets the address rule.
 */
@Epic("Infrastructure")
@Feature("Ownership by id")
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "comments.rate-limit.per-minute=0",
        "spring.datasource.url=jdbc:h2:mem:comments_ownership;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
class OwnershipByIdTest {

    private static final String THREAD = "/memes/" + TestAuthConfig.EXISTING_MEME + "/comments";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("the same id under a new address still owns the comment; the same address under another id does not")
    void the_id_outranks_the_address() throws Exception {
        String body = mockMvc.perform(post(THREAD)
                        .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                        .contentType("application/json").content("{\"text\":\"mine\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String commentId = objectMapper.readTree(body).get("id").asText();

        assertTrue(own(commentId, TestAuthConfig.RENAMED_TOKEN), "same id, new address");
        assertFalse(own(commentId, TestAuthConfig.IMPOSTOR_TOKEN), "same address, another id");

        mockMvc.perform(delete(THREAD + "/" + commentId)
                        .header("Authorization", "Bearer " + TestAuthConfig.IMPOSTOR_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(THREAD + "/" + commentId)
                        .header("Authorization", "Bearer " + TestAuthConfig.RENAMED_TOKEN))
                .andExpect(status().isOk());
    }

    private boolean own(String commentId, String token) throws Exception {
        String body = mockMvc.perform(get(THREAD).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode entry : objectMapper.readTree(body)) {
            if (entry.get("id").asText().equals(commentId)) {
                return entry.get("own").asBoolean();
            }
        }
        throw new AssertionError("comment " + commentId + " is not in the thread");
    }
}
