package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 429 an author meets when they comment too fast — which had no test at all, at any level.
 *
 * <p>The gap was a consequence rather than an oversight: the cucumber suite exercised the limiter
 * constantly — 18 of its 20 tokens, all inside one two-second minute window — without ever
 * asserting on it. The path looked covered while the only thing being proven was that the
 * scenarios stayed under the ceiling, with two tokens of margin before a suite about comment
 * threads would start failing on a limiter nobody was testing. That suite now disables the limiter
 * outright, which moves the burden of proving the refusal here, where it can be stated exactly:
 * the status, the Retry-After a client needs in order to obey, and the machine-readable status a
 * UI branches on.
 *
 * <p>MockMvc rather than a real socket, deliberately: this drives the whole servlet chain — the
 * sign-in filter, the controller, the JSON — while staying a unit of THIS service, with no port,
 * no waiting and nothing to be flaky about.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class}, properties = {
        // the property rather than a @Primary bean, so the refusal travels the real wiring, dial
        // included, and this test would notice if that wiring were ever bypassed
        "comments.rate-limit.per-minute=1",
        // ITS OWN database. The shared test URL carries DB_CLOSE_DELAY=-1, so the in-memory schema
        // outlives any single context and every @SpringBootTest in this module reads the same rows.
        // The comments written below therefore landed at the head of the known meme's thread and
        // broke a neighbouring test that asserts on $[0] — which CI caught and a local run did not,
        // because the order happened to differ. A test that must write should write somewhere of
        // its own rather than ask the rest of the suite to tolerate it.
        "spring.datasource.url=jdbc:h2:mem:comments-rate-limit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
@AutoConfigureMockMvc
class CommentRateLimitHttpTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("the second comment in the window is refused with 429, Retry-After and a named status")
    void commenting_too_fast_is_refused_in_the_protocol_the_client_reads() throws Exception {
        mvc.perform(comment("first, and within the allowance"))
                .andExpect(status().isCreated());

        mvc.perform(comment("second, over the ceiling"))
                .andExpect(status().isTooManyRequests())
                // a client told to slow down also has to be told for how long
                .andExpect(header().string("Retry-After", "60"))
                // machine-readable on purpose: a UI branches on this, not on the prose beside it
                .andExpect(jsonPath("$.status").value("RATE_LIMITED"));
    }

    private static org.springframework.test.web.servlet.RequestBuilder comment(String text) {
        return post("/memes/{meme}/comments", TestAuthConfig.EXISTING_MEME)
                .header("Authorization", "Bearer " + TestAuthConfig.VALID_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}");
    }
}
