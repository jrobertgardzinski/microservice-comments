package com.jrobertgardzinski.comments.infrastructure;

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

import java.net.URI;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gate and the router must read the same path. Spring matches {@code /memes/{memeId}/comments}
 * against the DECODED segments of the request path, so {@code /%6Demes/...} is dispatched to the
 * comment handler — and a gate that tests the raw URI for a "/memes" prefix waves that request
 * through without resolving anybody. What saved it until now was a binding accident (an absent
 * request attribute happens to fail the handler), not the guard the filter documents.
 */
@Epic("Infrastructure")
@Feature("Sign-in gate")
@Story("Percent-encoded paths")
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:comments_encoded_path;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class EncodedPathMeetsTheGateTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("an anonymous write on a percent-encoded path is refused by the gate, not by luck")
    void the_gate_sees_what_the_router_sees() throws Exception {
        // "%6D" is "m": the dispatcher decodes it and hands the request to CommentController
        URI encoded = URI.create("/%6Demes/" + TestAuthConfig.EXISTING_MEME + "/comments");

        mockMvc.perform(post(encoded).contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("SIGN_IN_REQUIRED"));
    }
}
