package com.jrobertgardzinski.comments.infrastructure;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this service connects to when the deployment says nothing — asserted against the file that
 * goes into the image, because nothing else can see it: {@code src/test/resources/application.properties}
 * REPLACES the shipped one on the test classpath (see the note in that file), so every test in this
 * module runs on the H2 URL it supplies and none of them ever reads the default that ships.
 *
 * <p>The default that shipped until now was {@code jdbc:postgresql://localhost:5433/comments} with
 * {@code postgres}/{@code secret}: host port 5433 is microservice-security's Postgres — comments'
 * own is 5435 — and those are the identity cluster's live credentials. A start without {@code DB_URL}
 * (a bare {@code java -jar}, {@code spring-boot:run}, an IDE configuration whose env file had not
 * been loaded yet) therefore authenticated against another service's database server; it failed only
 * because no {@code comments} database happened to exist on that instance, and adminer is wired
 * straight at it. A default is for the DB-less start — which is why pom.xml keeps H2 at runtime
 * scope — and a default naming a live server somewhere else is not one.
 */
@Epic("Infrastructure")
@Feature("Persistence")
@Story("Shipped datasource default")
class ShippedDatasourceDefaultTest {

    /** Relative to the repo directory, which is what surefire makes the working directory. */
    private static final Path DEPLOYED_PROPERTIES =
            Path.of("src/main/resources/application.properties");

    @Test
    @DisplayName("without DB_URL the service falls back to its own in-memory H2, not to somebody's server")
    void the_shipped_default_reaches_no_database_server() throws Exception {
        Properties deployed = new Properties();
        try (InputStream file = Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }
        String url = defaultOf(deployed.getProperty("spring.datasource.url"), "DB_URL");

        assertTrue(url.startsWith("jdbc:h2:mem:"),
                "the shipped fallback is " + url + ". A default that names a database SERVER is a"
                        + " connection this service opens whenever a deployment forgets DB_URL, and"
                        + " the one that shipped named the identity cluster's Postgres (5433) with"
                        + " its credentials. The fallback exists for the DB-less start; H2 is what"
                        + " a DB-less start means.");
    }

    @Test
    @DisplayName("and carries no other service's credentials with it")
    void the_shipped_default_carries_no_live_credentials() throws Exception {
        Properties deployed = new Properties();
        try (InputStream file = Files.newInputStream(DEPLOYED_PROPERTIES)) {
            deployed.load(file);
        }

        assertTrue("sa".equals(defaultOf(deployed.getProperty("spring.datasource.username"), "DB_USER")),
                "the H2 fallback's user, not a Postgres account somebody can reach");
        assertTrue(defaultOf(deployed.getProperty("spring.datasource.password"), "DB_PASSWORD").isEmpty(),
                "a password in a shipped default is a password for a server this file should not name");
    }

    /** The {@code ${NAME:default}} half of a property — what a start that sets nothing gets. */
    private static String defaultOf(String property, String variable) {
        String prefix = "${" + variable + ":";
        assertTrue(property != null && property.startsWith(prefix) && property.endsWith("}"),
                variable + " must stay overridable: " + property);
        return property.substring(prefix.length(), property.length() - 1);
    }
}
