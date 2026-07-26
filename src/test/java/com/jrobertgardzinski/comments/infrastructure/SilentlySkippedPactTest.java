package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guard on the guards: a contract test that never runs is indistinguishable from one that
 * passes, and it took a workspace split for the portal to learn that the hard way —
 * {@code SecurityOutcomePactProviderTest} in microservice-offboarding looked for its pact among the
 * portal siblings, where it had not been for weeks, and quietly skipped on every single run. Its
 * green tick meant "the file was not there", which is the exact opposite of what a reader assumes a
 * green contract test means.
 *
 * <p>Both provider pact tests in this repo therefore keep the workspace's skip-not-fail convention
 * (a solo checkout of one service must still build) and this class draws the line the convention
 * needs: <b>skipping is allowed only when the neighbouring repository is genuinely absent.</b> If the
 * neighbour IS checked out and the pact the provider test loads is not where that test looks, the
 * silence is a stale path, not a lone checkout — and it fails here instead of hiding.
 *
 * <p>This class carries no {@code @EnabledIf} of its own on purpose: it must run in every layout, so
 * that the one thing it can prove — "nothing here is being skipped for the wrong reason" — is proven
 * on every run.
 */
class SilentlySkippedPactTest {

    /** Relative to the REPO directory, which is what surefire makes the working directory. */
    private static final String NEIGHBOURS_ROOT = "..";

    @Test
    @DisplayName("the cascade pact is where CommentsDeletedPactProviderTest looks for it")
    void the_cascade_pact_is_not_silently_skipped() {
        assertPactPresentWheneverConsumerIsCheckedOut("microservice-user-collections",
                CommentsDeletedPactProviderTest.PACT_FOLDER,
                CommentsDeletedPactProviderTest.PACT_FILE);
    }

    @Test
    @DisplayName("the saga pact is where PurgeConfirmationPactProviderTest looks for it")
    void the_saga_pact_is_not_silently_skipped() {
        // the same check for the older of the two contracts — it is the one that was found skipping
        assertPactPresentWheneverConsumerIsCheckedOut("microservice-offboarding",
                "../microservice-offboarding/pacts",
                "microservice-offboarding-microservice-comments.json");
    }

    private static void assertPactPresentWheneverConsumerIsCheckedOut(String consumerRepo,
                                                                     String pactFolder,
                                                                     String pactFile) {
        // a solo checkout of this service is a legitimate layout, and the only one allowed to skip
        assumeTrue(Files.isDirectory(Path.of(NEIGHBOURS_ROOT, consumerRepo)),
                consumerRepo + " is not checked out next to this repo — the one case in which a"
                        + " provider pact test may legitimately skip");

        assertTrue(Files.isRegularFile(Path.of(pactFolder, pactFile)),
                consumerRepo + " IS checked out, so its pact must be readable at " + pactFolder
                        + "/" + pactFile + ". It is not — which means the provider test skips"
                        + " without verifying anything, and reports that as success. Either the"
                        + " path drifted (as it did after the workspace split) or the consumer"
                        + " stopped recording the pact.");
    }
}
