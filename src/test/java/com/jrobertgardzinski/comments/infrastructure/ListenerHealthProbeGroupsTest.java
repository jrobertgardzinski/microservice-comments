package com.jrobertgardzinski.comments.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHICH probe the listener lamp hangs on — a configuration pin, built exactly like
 * {@link KafkaProducerClocksTest}: half 1 reads the SHIPPED file (this suite's own
 * {@code application.properties} on the test classpath shadows it, so a boot here would prove nothing
 * about what production runs), half 2 proves that those property names are really the ones Spring turns
 * into groups.
 *
 * <p>The decision being pinned: readiness, never liveness. A stopped Kafka listener cannot do this
 * service's share of an account deletion, so it must stop being handed work — but it is NOT a dead
 * process (HTTP keeps serving comment threads) and a restart would fix neither the broker nor the
 * database that stopped it. A liveness probe wired to it would crash-loop the pod through every
 * dependency outage, which is precisely the reasoning microservice-offboarding and
 * microservice-user-collections wrote down when they split {@code /alive} from {@code /health}. Getting
 * these two lines the wrong way round is a one-word mistake with a self-inflicted outage attached, and
 * this test is what fails instead.
 */
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class},
        // the same three lines the shipped file carries (half 1 is what pins THAT); passing them here
        // is how half 2 gets a context to ask, since the test classpath shadows the real file
        properties = {
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState,kafkaListeners",
                "management.endpoint.health.group.liveness.include=livenessState"})
class ListenerHealthProbeGroupsTest {

    /** The contributor name is the bean name in {@link SagaParticipantConfig}. */
    private static final String LAMP = "kafkaListeners";

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    private static final Path DEPLOYED_PROPERTIES = Path.of("src/main/resources/application.properties");

    @Autowired
    HealthEndpointGroups groups;

    @Test
    @DisplayName("the shipped configuration puts the listener lamp in readiness and NOT in liveness")
    void the_deployed_properties_put_the_lamp_in_readiness() throws IOException {
        Properties deployed = new Properties();
        try (InputStream file = Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }

        assertEquals("true", deployed.getProperty("management.endpoint.health.probes.enabled"),
                "the groups must exist off Kubernetes too — the compose stack is where this service"
                        + " actually runs, and there Spring auto-enables neither probe");
        assertEquals("readinessState,kafkaListeners",
                deployed.getProperty("management.endpoint.health.group.readiness.include"),
                "a participant whose listener loop has stopped must stop being handed work");
        assertEquals("livenessState",
                deployed.getProperty("management.endpoint.health.group.liveness.include"),
                "and must NOT be restarted for it: a dead listener is not a dead process, and a"
                        + " restart would fix neither the broker nor the database that stopped it");
    }

    @Test
    @DisplayName("and those property names really are the ones Spring builds the groups from")
    void the_property_names_reach_the_groups() {
        HealthEndpointGroup readiness = groups.get("readiness");
        HealthEndpointGroup liveness = groups.get("liveness");

        assertNotNull(readiness, "a misspelt group name would leave the lamp wired to nothing at all");
        assertNotNull(liveness);
        assertTrue(readiness.isMember(LAMP));
        assertTrue(readiness.isMember("readinessState"),
                "the application's own readiness state stays in the group it came from");
        assertFalse(liveness.isMember(LAMP));
        assertTrue(liveness.isMember("livenessState"));
    }
}
