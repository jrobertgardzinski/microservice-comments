package com.jrobertgardzinski.comments.infrastructure.cucumber;

import com.jrobertgardzinski.comments.infrastructure.TestAuthConfig;
import com.jrobertgardzinski.comments.infrastructure.VoteStoreOutageConfig;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Autowired
    VoteStoreOutageConfig.OutageProneCommentVotes voteStore;

    @Autowired
    TestAuthConfig.RecordedCommentEvents cascadeAnnouncements;

    private String memeId;
    private String commentId;
    private String commentText;
    private Response lastResponse;

    @Before
    public void aCleanSlate() {
        // a previous scenario may have left the vote store "down"; heal it before the cascade
        voteStore.outage(false);
        // scenarios share one app and one H2; the cascade is also the perfect thread reset
        memesEventsAnnouncer.accept(
                "{\"type\":\"MEME_DELETED\",\"memeId\":\"" + TestAuthConfig.EXISTING_MEME + "\"}");
        // ...which means the reset itself may have announced the previous scenario's comments
        cascadeAnnouncements.forget();
    }

    @Given("a signed-in user")
    public void aSignedInUser() {
        memeId = TestAuthConfig.EXISTING_MEME;
    }

    @When("she comments {string} under the known meme")
    public void sheComments(String text) {
        lastResponse = comment(TestAuthConfig.VALID_TOKEN, TestAuthConfig.EXISTING_MEME, text);
        commentId = lastResponse.statusCode() == 201 ? lastResponse.jsonPath().getString("id") : null;
        commentText = text;
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
        // the listing masks the author's e-mail: first character + *** + domain
        String masked = TestAuthConfig.SIGNED_IN_USER.charAt(0) + "***"
                + TestAuthConfig.SIGNED_IN_USER.substring(TestAuthConfig.SIGNED_IN_USER.indexOf('@'));
        assertTrue(authors.stream().allMatch(masked::equals),
                "the author is the confirmed identity (masked for the public listing),"
                        + " not a request field");
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

    @Then("the collections service is told which comments went")
    public void collectionsIsToldWhichCommentsWent() {
        // the essence of the choreography: memes knows only that the meme is gone — which comments
        // hung under it is knowledge only this service has, so only this service can pass it on
        assertEquals(1, cascadeAnnouncements.announcements().size(),
                "one dropped thread, one announcement");
        var announced = cascadeAnnouncements.announcements().get(0);
        assertEquals(TestAuthConfig.EXISTING_MEME, announced.memeId());
        assertEquals(List.of(commentId), announced.commentIds(),
                "the announcement names every comment that went, by id");
    }

    @Then("nothing is announced to the collections service")
    public void nothingIsAnnounced() {
        assertTrue(cascadeAnnouncements.announcements().isEmpty(),
                "COMMENTS_DELETED belongs to the MEME_DELETED cascade alone, and only when it "
                        + "actually took comments — a single deletion by its author is nobody "
                        + "else's business, and an empty list is pure noise");
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

    @When("she deletes that comment")
    public void sheDeletes() {
        lastResponse = deleteComment(TestAuthConfig.VALID_TOKEN);
        assertEquals(200, lastResponse.statusCode());
    }

    @When("{string} tries to delete that comment")
    public void strangerDeletes(String who) {
        lastResponse = deleteComment(TestAuthConfig.SECOND_TOKEN);
    }

    @When("a moderator deletes that comment")
    public void moderatorDeletes() {
        lastResponse = deleteComment(TestAuthConfig.MODERATOR_TOKEN);
        assertEquals(200, lastResponse.statusCode());
        assertEquals("MODERATOR", lastResponse.jsonPath().getString("by"));
    }

    @Then("the deletion is refused as not-theirs")
    public void deletionRefused() {
        assertEquals(403, lastResponse.statusCode());
        assertEquals("NOT_YOURS", lastResponse.jsonPath().getString("status"));
    }

    @When("a moderator hides that comment")
    public void moderatorHides() {
        lastResponse = setHidden(TestAuthConfig.MODERATOR_TOKEN, true);
        assertEquals(200, lastResponse.statusCode());
        assertEquals("HIDDEN", lastResponse.jsonPath().getString("status"));
    }

    @When("a moderator reveals that comment")
    public void moderatorReveals() {
        lastResponse = setHidden(TestAuthConfig.MODERATOR_TOKEN, false);
        assertEquals(200, lastResponse.statusCode());
        assertEquals("REVEALED", lastResponse.jsonPath().getString("status"));
    }

    @When("{string} tries to hide that comment")
    public void strangerHides(String who) {
        lastResponse = setHidden(TestAuthConfig.SECOND_TOKEN, true);
    }

    @Then("a reader sees that comment as hidden without its text")
    public void readerSeesTombstone() {
        var comment = threadEntry(null, commentId);
        assertEquals(Boolean.TRUE, comment.get("hidden"), "a hidden comment must be flagged");
        assertNull(comment.get("text"), "a reader must not see a hidden comment's text");
    }

    @Then("the author still sees that comment's text, marked hidden")
    public void authorSeesOwnHidden() {
        var comment = threadEntry(TestAuthConfig.VALID_TOKEN, commentId);
        assertEquals(Boolean.TRUE, comment.get("hidden"));
        assertEquals("Kontrowersyjne", comment.get("text"), "the author still sees their own words");
    }

    @Then("a reader sees that comment's text again")
    public void readerSeesTextAgain() {
        var comment = threadEntry(null, commentId);
        assertNull(comment.get("hidden"), "a revealed comment carries no hidden flag");
        assertEquals("Kontrowersyjne", comment.get("text"));
    }

    @Then("the hiding is refused as not-a-moderator")
    public void hidingRefused() {
        assertEquals(403, lastResponse.statusCode());
        assertEquals("NOT_A_MODERATOR", lastResponse.jsonPath().getString("status"));
    }

    @When("a moderator asks about that comment without deciding hidden or not")
    public void moderatorSendsNoDecision() {
        lastResponse = RestAssured.given().port(port)
                .header("Authorization", "Bearer " + TestAuthConfig.MODERATOR_TOKEN)
                .contentType("application/json")
                .body("{}")
                .put("/memes/{memeId}/comments/{commentId}/hidden",
                        TestAuthConfig.EXISTING_MEME, commentId);
    }

    @Then("the request is refused as undecided")
    public void refusedAsUndecided() {
        assertEquals(400, lastResponse.statusCode(),
                "no decision is a malformed request, not a quiet reveal");
        assertEquals("MISSING_HIDDEN", lastResponse.jsonPath().getString("status"));
    }

    @Then("a reader still sees that comment's text")
    public void readerStillSeesText() {
        var comment = threadEntry(null, commentId);
        assertNull(comment.get("hidden"), "an undecided request must not touch the comment");
        assertEquals(commentText, comment.get("text"));
    }

    @Then("a reader learns who wrote it only as a masked name")
    public void readerSeesOnlyMaskedName() {
        String masked = TestAuthConfig.SIGNED_IN_USER.charAt(0) + "***"
                + TestAuthConfig.SIGNED_IN_USER.substring(TestAuthConfig.SIGNED_IN_USER.indexOf('@'));
        assertEquals(masked, threadEntry(null, commentId).get("author"),
                "the public listing signs a comment with a masked name");
    }

    @Then("her full address appears nowhere in the listing")
    public void fullAddressAppearsNowhere() {
        assertFalse(thread().asString().contains(TestAuthConfig.SIGNED_IN_USER),
                "the full e-mail address must never leave the service in a listing");
    }

    @Then("the author still recognises that comment as their own")
    public void authorRecognisesOwn() {
        assertEquals(Boolean.TRUE, threadEntry(TestAuthConfig.VALID_TOKEN, commentId).get("own"),
                "behind the masked name, the author still recognises their own words");
    }

    @Then("another signed-in user sees it as someone else's")
    public void strangerDoesNotClaimIt() {
        assertEquals(Boolean.FALSE, threadEntry(TestAuthConfig.SECOND_TOKEN, commentId).get("own"),
                "a stranger's comment is not marked as the viewer's own");
    }

    @When("the vote count becomes unavailable")
    public void voteCountBecomesUnavailable() {
        voteStore.outage(true);
    }

    @When("the vote count is back")
    public void voteCountIsBack() {
        voteStore.outage(false);
    }

    @Then("the thread still shows that comment, its result unknown")
    public void threadStillListsWithUnknownScore() {
        var comment = threadEntry(null, commentId);
        assertEquals(commentText, comment.get("text"),
                "votes are a side dish — the thread still lists without them");
        assertNull(comment.get("score"), "with the count gone the result is unknown, not zero");
        assertNull(comment.get("myVote"));
    }

    private Response setHidden(String token, boolean hidden) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"hidden\":" + hidden + "}")
                .put("/memes/{memeId}/comments/{commentId}/hidden", TestAuthConfig.EXISTING_MEME, commentId);
    }

    /** One comment's JSON from the thread, read through the eyes of {@code token} (null = anonymous). */
    private java.util.Map<String, Object> threadEntry(String token, String id) {
        var request = RestAssured.given().port(port);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.get("/memes/{memeId}/comments", TestAuthConfig.EXISTING_MEME)
                .jsonPath().getMap("find { it.id == '" + id + "' }");
    }

    private Response deleteComment(String token) {
        return RestAssured.given().port(port)
                .header("Authorization", "Bearer " + token)
                .delete("/memes/{memeId}/comments/{commentId}", TestAuthConfig.EXISTING_MEME, commentId);
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
