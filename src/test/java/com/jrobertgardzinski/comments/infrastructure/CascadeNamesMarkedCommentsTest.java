package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jrobertgardzinski.comments.application.DeleteThread;
import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where the two features meet: an account-deletion saga has MARKED a comment, and then the meme it
 * hangs under is deleted. The thread delete destroys the marked row along with the rest — it is one
 * {@code DELETE ... WHERE meme_id = ?} over the base table — so the cascade has to name it in what
 * it reports. Named through the ACTIVE view, the row would be destroyed silently and
 * microservice-user-collections would keep a reference to a comment that no longer exists, which is
 * the one thing the announcement exists to prevent.
 */
@Epic("Saga")
@Feature("Meme-deleted cascade")
@Story("A marked comment goes with the thread")
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
// its own database: this test drops a whole thread, which is not something the tests sharing
// jdbc:h2:mem:comments can survive being run before
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:comments_marked_cascade;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
class CascadeNamesMarkedCommentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String THREAD = "/memes/" + TestAuthConfig.EXISTING_MEME + "/comments";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MarkUserCommentsForErasure markForErasure;

    @Autowired
    DeleteThread deleteThread;

    @Test
    @DisplayName("the cascade reports the comment a running saga had marked — it destroyed it too")
    void a_marked_comment_is_reported_as_dropped() throws Exception {
        String leaversComment = comment(TestAuthConfig.VALID_TOKEN, "the leaver's remark");
        String somebodyElsesComment = comment(TestAuthConfig.SECOND_TOKEN, "bob's reply");
        markForErasure.execute(TestAuthConfig.SIGNED_IN_USER);

        List<String> dropped = deleteThread.execute(TestAuthConfig.EXISTING_MEME);

        assertTrue(dropped.contains(somebodyElsesComment),
                "the fixture's live comment must be reported, or this test proves nothing");
        assertTrue(dropped.contains(leaversComment),
                "the marked comment was destroyed by the thread delete, so the cascade must name "
                        + "it: collections holds a reference to it and has no other way of learning "
                        + "that it is dead");
    }

    private String comment(String token, String text) throws Exception {
        String body = mockMvc.perform(post(THREAD).header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(java.util.Map.of("text", text))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asText();
    }
}
