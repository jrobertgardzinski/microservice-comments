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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A page number nobody could mean is still a page number a client can send. Multiplied by the page
 * size it overflows an int, and a negative OFFSET is not a small window — it is a statement the
 * database refuses, which reaches the caller of a PUBLIC read as a bare 500. The honest answer to
 * "give me page thirty million" is an empty page.
 */
@Epic("Infrastructure")
@Feature("Comments HTTP API")
@Story("Paging limits")
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:comments_paging;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class OutOfRangePageTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("a page far beyond the thread is an empty page, not a 500")
    void an_absurd_page_lists_nothing() throws Exception {
        // 30000000 * 100 overflows to -1_294_967_296
        mockMvc.perform(get("/memes/" + TestAuthConfig.EXISTING_MEME + "/comments")
                        .param("page", "30000000").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
