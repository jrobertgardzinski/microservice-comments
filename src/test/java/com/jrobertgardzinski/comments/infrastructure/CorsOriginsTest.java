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
            // exactly what k8s/base/comments.yaml sets
            properties = "comments.ui-origin=http://localhost:8083,http://memes.portal.localhost:9080")
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
