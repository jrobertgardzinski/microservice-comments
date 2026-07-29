package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which origins the gallery's browser may reach the comment threads from.
 *
 * <p>The gallery is served by microservice-memes on its own origin and asks THIS service for the
 * thread under a meme, so every one of those calls is cross-origin and gated by the preflight below.
 * Until 2026-07-29 the allowed origin was a single value defaulting to compose's
 * {@code http://localhost:8083}, and nothing overrode it in {@code portal/k8s/base/comments.yaml} —
 * so in a cluster, where the gallery lives at an ingress host name, every thread request would have
 * been refused BY THE BROWSER. That refusal never reaches this service: no request arrives, no log
 * line is written, the pod stays Ready, and the page simply shows no comments.
 *
 * <p><strong>The code was never the defect, and finding that out is why this test is worded the way
 * it is.</strong> The first attempt at a fix here split the property on commas by hand, on the
 * assumption that {@code allowedOrigins} took one value. Removing that split again left the test
 * green: Spring already accepts a comma-separated list, so the service could always have been
 * pointed at a cluster. The only thing missing was the manifest saying so — which is a far easier
 * mistake to make, and a far quieter one, than a bug in a bean.
 *
 * <p>microservice-security met the identical failure the same day. The reason this is a test rather
 * than a note in a README: the failure leaves no evidence on the side that could report it, so the
 * guard has to sit where the configuration is.
 */
class CorsOriginsTest {

    private static final String GALLERY = "http://localhost:8083";
    private static final String INGRESS = "http://memes.portal.localhost:9080";

    /** Any mapping under /memes/** — the preflight is judged on the path, not the resource. */
    private static final String THREAD = "/memes/any-meme/comments";

    /** The list {@link WhenDeployed} boots with — a superset of what any one deployment sets. */
    static final String DEPLOYED_LIST = GALLERY + "," + INGRESS;

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    private static final java.nio.file.Path MANIFEST = java.nio.file.Path.of("../k8s/base/comments.yaml");

    /**
     * Half one: the manifest is READ, rather than quoted from memory.
     *
     * <p>{@link WhenDeployed} used to introduce its property list as "exactly what
     * k8s/base/comments.yaml sets" — while the manifest set a single origin and the test drove two.
     * Harmless as it stood, and that is the trouble with it: the sentence claimed a guard nobody
     * had written, so the drift it was there to catch could grow without anything going red. The
     * failure it is FOR is not a value drifting, it is {@code UI_ORIGIN} disappearing — the obvious
     * casualty of moving this container's env into a ConfigMap — and the result is the traceless
     * one this whole class exists for: the browser refuses, no request reaches the service, no log
     * line is written, the pod stays Ready, and the page shows no comments.
     */
    @Test
    @DisplayName("every origin the k8s manifest sets is an origin the deployed-list case proves works")
    void the_manifest_sets_origins_this_test_actually_exercises() throws java.io.IOException {
        java.util.List<String> manifestOrigins = uiOriginFrom(java.nio.file.Files.readAllLines(MANIFEST));

        org.junit.jupiter.api.Assertions.assertFalse(manifestOrigins.isEmpty(),
                MANIFEST + " sets no UI_ORIGIN. In a cluster the gallery is served from the ingress,"
                        + " so the default (compose's " + GALLERY + ") refuses every thread request"
                        + " — in the browser, where this service can neither see it nor log it.");

        java.util.List<String> proven = java.util.List.of(DEPLOYED_LIST.split(","));
        org.junit.jupiter.api.Assertions.assertTrue(proven.containsAll(manifestOrigins),
                "the manifest sets " + manifestOrigins + ", and only " + proven + " is exercised"
                        + " below. An origin nothing drives a preflight for is an origin nobody has"
                        + " checked this service will answer to.");
    }

    /** The {@code value:} of the {@code UI_ORIGIN} env entry, split on commas. */
    private static java.util.List<String> uiOriginFrom(java.util.List<String> manifest) {
        for (int line = 0; line < manifest.size(); line++) {
            if (!manifest.get(line).trim().equals("- name: UI_ORIGIN")) {
                continue;
            }
            for (int next = line + 1; next < manifest.size(); next++) {
                String candidate = manifest.get(next).trim();
                if (candidate.startsWith("value:")) {
                    return java.util.Arrays.stream(candidate.substring("value:".length()).trim()
                                    .replaceAll("^[\"']|[\"']$", "").split(","))
                            .map(String::trim).filter(origin -> !origin.isEmpty()).toList();
                }
                if (candidate.startsWith("- name:")) {
                    break;   // the entry has no literal value (a secretKeyRef, a configMapKeyRef)
                }
            }
        }
        return java.util.List.of();
    }

    @Nested
    @SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
    @AutoConfigureMockMvc
    @DisplayName("with nothing configured")
    class ByDefault {

        @Autowired
        MockMvc http;

        @Test
        @DisplayName("compose's gallery origin is allowed, so a local run is unaffected")
        void composes_origin_is_allowed() throws Exception {
            http.perform(options(THREAD)
                            .header("Origin", GALLERY)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", GALLERY));
        }

        @Test
        @DisplayName("and an ingress origin is NOT — which is exactly what broke the cluster")
        void an_unconfigured_origin_is_refused() throws Exception {
            http.perform(options(THREAD)
                            .header("Origin", INGRESS)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Nested
    @SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class},
            // a superset of what any one deployment sets; the test above is what ties this list to
            // k8s/base/comments.yaml, by reading the manifest rather than describing it
            properties = "comments.ui-origin=" + DEPLOYED_LIST)
    @AutoConfigureMockMvc
    @DisplayName("with a deployment's list")
    class WhenDeployed {

        @Autowired
        MockMvc http;

        @Test
        @DisplayName("every origin in the comma-separated list is allowed, not merely the first")
        void the_whole_list_is_honoured() throws Exception {
            http.perform(options(THREAD)
                            .header("Origin", INGRESS)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", INGRESS));

            // the second entry must not cost the first: compose and the cluster share one image
            http.perform(options(THREAD)
                            .header("Origin", GALLERY)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", GALLERY));
        }
    }
}
