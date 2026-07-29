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
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class},
        properties = {
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState,kafkaListeners",
                "management.endpoint.health.group.liveness.include=livenessState",
                // as shipped; the test classpath's own properties file shadows the real one
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.endpoint.health.show-details=always"})
@AutoConfigureMockMvc
class ProbeUrlsTest {

    @Autowired
    MockMvc http;

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
