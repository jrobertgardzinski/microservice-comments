package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two URLs Kubernetes actually calls — asserted over HTTP, which nothing did until now.
 *
 * <p>{@link ListenerHealthProbeGroupsTest} pins WHICH group the listener lamp belongs to, and it does
 * that thoroughly: the shipped property file on one side, Spring's resolved
 * {@code HealthEndpointGroups} on the other. What it never touches is the address. The manifests in
 * {@code portal/k8s/base/comments.yaml} name {@code /actuator/health/readiness} and
 * {@code /actuator/health/liveness}, and a group can be configured perfectly while that path answers
 * 404 — because the path depends on things the group knows nothing about:
 * {@code management.endpoints.web.exposure.include}, the actuator base path, and whether the running
 * framework still serves groups as path segments at all.
 *
 * <p><strong>Why it was written on 2026-07-29.</strong> Spring Boot 4 moved the health API out of
 * {@code org.springframework.boot.actuate.health} and split actuator into modules. The group
 * CONFIGURATION survived that untouched and the existing test proved it. The URL was simply assumed,
 * on both sides of the migration — and the probes are the whole reason the lamp exists. With k3s next,
 * an assumed probe URL is the kind of thing that is discovered by a pod that never goes ready.
 *
 * <p>Deliberately asserts the group's own field rather than only the status code: a 200 from a
 * <em>differently</em> shaped body would still fail the probe's purpose, and 404 with a permissive
 * matcher reads the same as success.
 *
 * <p><strong>And the half that was missing.</strong> Until 2026-07-29 this class handed itself
 * {@code management.endpoints.web.exposure.include=health,prometheus} as a context property, with a
 * comment saying "as shipped". Nothing read the shipped file, so "as shipped" was an assertion about
 * a string in this file: delete {@code health} from
 * {@code src/main/resources/application.properties} and every test here stayed green while both
 * probe URLs answered 404 in the cluster and the pod never went Ready — the exact class of failure
 * the javadoc above says this test closes. The context properties are gone (the test classpath's
 * copy of the management block supplies them now) and {@link #the_shipped_configuration_exposes_the_probes}
 * pins the file that goes into the image, in the house's two-halves shape — see
 * {@link ListenerHealthProbeGroupsTest}.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class})
@AutoConfigureMockMvc
class ProbeUrlsTest {

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    private static final java.nio.file.Path DEPLOYED_PROPERTIES =
            java.nio.file.Path.of("src/main/resources/application.properties");

    @Autowired
    MockMvc http;

    @Test
    @DisplayName("the SHIPPED properties expose health, and the lamp's details with it")
    void the_shipped_configuration_exposes_the_probes() throws Exception {
        java.util.Properties deployed = new java.util.Properties();
        try (java.io.InputStream file = java.nio.file.Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }

        String exposed = deployed.getProperty("management.endpoints.web.exposure.include", "");
        org.junit.jupiter.api.Assertions.assertTrue(
                java.util.Arrays.asList(exposed.split(",")).contains("health"),
                "the image ships exposure.include=" + exposed + ". Without `health` on that list"
                        + " /actuator/health/readiness and /liveness — the two URLs comments.yaml"
                        + " probes — answer 404, the startupProbe never passes, and the pod is never"
                        + " Ready. The tests below cannot see it: they run against a context, not"
                        + " against the file that goes into the image.");

        org.junit.jupiter.api.Assertions.assertEquals("always",
                deployed.getProperty("management.endpoint.health.show-details"),
                "SagaListenersHealth builds container ids, states and ages, and distinguishes 'the"
                        + " thread died' from 'the poll stalled' — Boot's default of never turns"
                        + " every answer into a bare {\"status\":\"DOWN\"}, so that diagnosis exists"
                        + " in no environment where the lamp runs. The details are PII-free by"
                        + " construction, which is what makes always safe here.");
    }

    @Test
    @DisplayName("/actuator/health/readiness answers, and the listener lamp is in what it answers")
    void the_readiness_url_from_the_manifest_serves_the_lamp() throws Exception {
        http.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.kafkaListeners").exists());
    }

    @Test
    @DisplayName("/actuator/health/liveness answers, and the lamp is deliberately NOT in it")
    void the_liveness_url_from_the_manifest_excludes_the_lamp() throws Exception {
        http.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.kafkaListeners").doesNotExist());
    }

    @Test
    @DisplayName("and the bare /actuator/health the compose stack probes still answers too")
    void the_url_the_compose_healthcheck_uses_answers() throws Exception {
        http.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
