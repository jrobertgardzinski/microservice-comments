package com.jrobertgardzinski.comments.infrastructure.cucumber;

import com.jrobertgardzinski.comments.infrastructure.TestAuthConfig;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP glue for {@code comment-thread.feature}: two signed-in users (the stub gate's well-known
 * tokens) discuss under the one meme the stub directory vouches for. Each scenario works its own
 * meme id where isolation matters; the MEME_DELETED cascade is announced straight to the
 * listener — the broker's job, not the contract under test.
 */
public class CommentThreadSteps {

    @LocalServerPort
    int port;

    @Autowired
    Consumer<String> memesEventsAnnouncer;

    private String memeId;
    private String commentId;
    private Response lastResponse;

    @Before
    public void aCleanSlate() {
        // scenarios share one app and one H2; the cascade is also the perfect thread reset
        memesEventsAnnouncer.accept(
                "{\"type\":\"MEME_DELETED\",\"memeId\":\"" + TestAuthConfig.EXISTING_MEME + "\"}");
    }

    @Given("a signed-in user")
    public void aSignedInUser() {
        memeId = TestAuthConfig.EXISTING_MEME;
    }

    @When("she comments {string} under the known meme")
    public void sheComments(String text) {
        lastResponse = comment(TestAuthConfig.VALID_TOKEN, TestAuthConfig.EXISTING_MEME, text);
        commentId = lastResponse.statusCode() == 201 ? lastResponse.jsonPath().getString("id") : null;
    }

    @Given("her comment {string} under the known meme")
    public void herComment(String text) {
        sheComments(text);
        assertEquals(201, lastResponse.statusCode());
    }

    @When("someone comments anonymously under the known meme")
    public void anonymousComment() {
        lastResponse = RestAssured.given().port(port)
                .contentType("application/json")
                .body("{\"text\":\"anon\"}")
                .post("/memes/{memeId}/comments", TestAuthConfig.EXISTING_MEME);
    }

    @When("she comments {string} under a meme nobody has seen")
    public void commentsUnderUnknownMeme(String text) {
        lastResponse = comment(TestAuthConfig.VALID_TOKEN, "ghost-" + UUID.randomUUID(), text);
    }

    @When("{int} users up-vote that comment")
    public void usersUpVote(int count) {
        List<String> tokens = List.of(TestAuthConfig.VALID_TOKEN, TestAuthConfig.SECOND_TOKEN);
        for (int i = 0; i < count; i++) {
            assertEquals(200, vote(tokens.get(i)).statusCode());
        }
    }

    @When("the same second user up-votes it again")
    public void secondUserVotesAgain() {
        assertEquals(200, vote(TestAuthConfig.SECOND_TOKEN).statusCode(),
                "repeating the same vote is a retraction, not an error");
    }

    @When("the meme service announces the meme was deleted")
    public void memeDeletedAnnounced() {
        memesEventsAnnouncer.accept(
                "{\"type\":\"MEME_DELETED\",\"memeId\":\"" + TestAuthConfig.EXISTING_MEME + "\"}");
    }

    @Then("the thread of the known meme shows {int} comment by her")
    public void threadShowsComments(int expected) {
        List<String> authors = thread().jsonPath().getList("author", String.class);
        assertEquals(expected, authors.size());
        assertTrue(authors.stream().allMatch(TestAuthConfig.SIGNED_IN_USER::equals),
                "the author is the confirmed identity, not a request field");
    }

    @Then("the comment is refused as sign-in required")
    public void refusedAsSignInRequired() {
        assertEquals(401, lastResponse.statusCode());
    }

    @Then("the comment is refused because the meme is unknown")
    public void refusedAsUnknownMeme() {
        assertEquals(404, lastResponse.statusCode());
    }

    @Then("the thread shows that comment with a score of {int}")
    public void threadShowsScore(int expected) {
        Integer score = thread().jsonPath().getInt(
                "find { it.id == '" + commentId + "' }.score");
        assertEquals(expected, score);
    }

    @Then("the thread of the known meme is empty")
    public void threadIsEmpty() {
        assertEquals(0, thread().jsonPath().getList("id").size(),
                "the cascade took the whole thread with the meme");
    }

    private Response lastPage;

    @io.cucumber.java.en.Given("{int} comments under the known meme")
    public void manyComments(int count) {
        for (int i = 0; i < count; i++) {
            assertEquals(201, comment(TestAuthConfig.VALID_TOKEN, TestAuthConfig.EXISTING_MEME,
                    "comment " + i).statusCode());
        }
    }

    @When("she reads page {int} of size {int} of the thread")
    public void readsPage(int page, int size) {
        lastPage = RestAssured.given().port(port)
                .queryParam("page", page).queryParam("size", size)
                .get("/memes/{memeId}/comments", TestAuthConfig.EXISTING_MEME);
    }

    @Then("{int} comments are returned")
    public void commentsReturned(int expected) {
        assertEquals(expected, lastPage.jsonPath().getList("id").size());
    }

    @Then("{int} comment is returned")
    public void commentReturned(int expected) {
        assertEquals(expected, lastPage.jsonPath().getList("id").size());
    }

    @When("she posts a comment of {int} characters under the known meme")
    public void postsComment(int length) {
        lastResponse = comment(TestAuthConfig.VALID_TOKEN, TestAuthConfig.EXISTING_MEME,
                "x".repeat(length));
    }

    @Then("the comment is refused as too long")
    public void refusedAsTooLong() {
        assertEquals(400, lastResponse.statusCode());
        assertEquals("COMMENT_TOO_LONG", lastResponse.jsonPath().getString("status"));
    }

    @Then("the comment is accepted")
    public void commentAccepted() {
        assertEquals(201, lastResponse.statusCode());
    }

    private Response comment(String token, String meme, String text) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"text\":\"" + text + "\"}")
                .post("/memes/{memeId}/comments", meme);
    }

    private Response vote(String token) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"direction\":\"UP\"}")
                .post("/memes/{memeId}/comments/{commentId}/votes", memeId, commentId);
    }

    private Response thread() {
        return RestAssured.given().port(port)
                .get("/memes/{memeId}/comments", TestAuthConfig.EXISTING_MEME);
    }
}
