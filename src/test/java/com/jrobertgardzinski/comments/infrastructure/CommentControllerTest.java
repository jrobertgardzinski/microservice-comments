package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box web test on the real JDBC adapters (H2): a signed-in user comments on an existing
 * meme and votes on the comment (toggle included); anonymous writes are refused; reads are
 * public, with each viewer seeing their own vote.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class CommentControllerTest {

    private static final String AUTH = "Bearer " + TestAuthConfig.VALID_TOKEN;
    private static final String AUTH_BOB = "Bearer " + TestAuthConfig.SECOND_TOKEN;

    @Autowired
    MockMvc mockMvc;

    @Test
    void comments_and_votes_full_circle() throws Exception {
        String memeUri = "/memes/" + TestAuthConfig.EXISTING_MEME + "/comments";

        // anonymous write refused; comment on a ghost meme refused
        mockMvc.perform(post(memeUri).contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/memes/ghost/comments").header("Authorization", AUTH)
                        .contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isNotFound());

        // signed-in comment lands, signed by the confirmed identity
        String body = mockMvc.perform(post(memeUri).header("Authorization", AUTH)
                        .contentType("application/json").content("{\"text\":\"Lorem ipsum\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String commentId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asText();

        // another user votes; repeating retracts (toggle)
        mockMvc.perform(post(memeUri + "/" + commentId + "/votes").header("Authorization", AUTH_BOB)
                        .contentType("application/json").content("{\"direction\":\"UP\"}"))
                .andExpect(jsonPath("$.score").value(1))
                .andExpect(jsonPath("$.myVote").value("UP"));
        mockMvc.perform(post(memeUri + "/" + commentId + "/votes").header("Authorization", AUTH_BOB)
                        .contentType("application/json").content("{\"direction\":\"UP\"}"))
                .andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.myVote").isEmpty());
        mockMvc.perform(post(memeUri + "/" + commentId + "/votes").header("Authorization", AUTH_BOB)
                        .contentType("application/json").content("{\"direction\":\"UP\"}"))
                .andExpect(status().isOk());

        // public read shows the comment; bob sees his own vote, anonymous does not
        mockMvc.perform(get(memeUri))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice@example.com")))
                .andExpect(jsonPath("$[0].myVote").isEmpty());
        mockMvc.perform(get(memeUri).header("Authorization", AUTH_BOB))
                .andExpect(jsonPath("$[0].myVote").value("UP"))
                .andExpect(jsonPath("$[0].score").value(1));
    }
}
