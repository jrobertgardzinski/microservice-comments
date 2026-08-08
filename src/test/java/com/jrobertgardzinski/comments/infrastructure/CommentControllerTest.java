package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
// Its OWN database, and the reason is a real red build rather than tidiness. Every test in this
// module shared `jdbc:h2:mem:comments`, and the account-deletion tests that run in the same JVM
// purge a leaver's votes — bob's among them. Which of them runs first is decided by surefire's
// default runOrder, i.e. by the FILESYSTEM: locally this class ran before them and was green,
// on the CI runner it ran after them and lost bob's vote ("$[0].myVote expected:<UP> but was
// <null>"), deterministically, on every run. A round-trip test of one controller has no business
// depending on that, so it stops sharing.
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:comments_controller;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
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

        // public read shows the comment with the author MASKED (the full e-mail is PII and never
        // leaves the service in a listing); bob sees his own vote, anonymous does not
        mockMvc.perform(get(memeUri))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("a***@example.com")))
                .andExpect(content().string(not(containsString("alice@example.com"))))
                .andExpect(jsonPath("$[0].myVote").isEmpty());
        mockMvc.perform(get(memeUri).header("Authorization", AUTH_BOB))
                .andExpect(jsonPath("$[0].myVote").value("UP"))
                .andExpect(jsonPath("$[0].score").value(1));

        // the author cannot recognise herself by the masked e-mail any more — "own" answers that
        // (the gallery UI shows its delete button from this flag, not from an author comparison)
        mockMvc.perform(get(memeUri).header("Authorization", AUTH))
                .andExpect(jsonPath("$[0].own").value(true));
        mockMvc.perform(get(memeUri).header("Authorization", AUTH_BOB))
                .andExpect(jsonPath("$[0].own").value(false));
        mockMvc.perform(get(memeUri))
                .andExpect(jsonPath("$[0].own").value(false));

        // a hide request without the flag is malformed — refused, not defaulted to "reveal"
        mockMvc.perform(put(memeUri + "/" + commentId + "/hidden").header("Authorization", AUTH)
                        .contentType("application/json").content("{\"hidden\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("MISSING_HIDDEN"));
    }
}
