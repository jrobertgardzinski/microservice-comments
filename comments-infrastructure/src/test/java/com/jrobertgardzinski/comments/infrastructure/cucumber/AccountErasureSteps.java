package com.jrobertgardzinski.comments.infrastructure.cucumber;

import com.jrobertgardzinski.comments.application.MarkUserCommentsForErasure;
import com.jrobertgardzinski.comments.application.PurgeUserComments;
import com.jrobertgardzinski.comments.application.RestoreUserComments;
import com.jrobertgardzinski.purge.PurgeRule;
import com.jrobertgardzinski.comments.infrastructure.TestAuthConfig;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Glue for {@code account-erasure.feature}: the promise this service makes about a leaver's words.
 *
 * <p>It drives the USE CASES and not the listener on purpose. What arrives over the broker, in what
 * envelope, answered by which confirmation, is a contract with one particular neighbour and is
 * pinned by the pact tests. The promise is older and smaller than that: set aside, give back, or
 * make final. A spec that asserted confirmations would be describing the courier.
 *
 * <p>The THREAD is read over HTTP, because "nobody can tell the difference from gone" is a
 * statement about what a reader sees, and a reader uses the API.
 */
public class AccountErasureSteps {

    @LocalServerPort
    int port;

    @Autowired
    MarkUserCommentsForErasure setAside;

    @Autowired
    PurgeUserComments makeFinal;

    @Autowired
    RestoreUserComments takeBack;

    // Cucumber matches on the text, not the keyword, so this one serves both the
    // scenario that sets it aside and the scenario that starts from it.
    @Given("the USER's things are set aside")
    public void theUsersThingsAreSetAside() {
        setAside.execute(TestAuthConfig.SIGNED_IN_USER);
    }

    @When("the decision is taken back")
    public void theDecisionIsTakenBack() {
        takeBack.execute(TestAuthConfig.SIGNED_IN_USER);
    }

    /** No rule stated means the deployment's own — which keeps the conversation and drops the name. */
    @When("the decision is made final")
    public void theDecisionIsMadeFinal() {
        makeFinal.execute(TestAuthConfig.SIGNED_IN_USER, Optional.empty());
    }

    @When("the decision is made final, erasing the words themselves")
    public void theDecisionIsMadeFinalErasingTheWords() {
        makeFinal.execute(TestAuthConfig.SIGNED_IN_USER, Optional.of(new PurgeRule.Delete()));
    }

    @Then("the THREAD of the known MEME shows {int} COMMENT signed {string}")
    public void theThreadShowsCommentsSigned(int expected, String signature) {
        List<String> authors = RestAssured.given().port(port)
                .get("/memes/" + TestAuthConfig.EXISTING_MEME + "/comments")
                .jsonPath().getList("author", String.class);

        assertEquals(expected, authors.size());
        assertTrue(authors.stream().allMatch(signature::equals),
                "the words stay and the name goes — a reader sees " + authors);
    }
}
