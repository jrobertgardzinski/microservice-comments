package com.jrobertgardzinski.comments.infrastructure.cucumber;

import com.jrobertgardzinski.comments.infrastructure.CommentsApplication;
import com.jrobertgardzinski.comments.infrastructure.TestAuthConfig;
import com.jrobertgardzinski.comments.infrastructure.VoteStoreOutageConfig;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the Spring application on a random port for Cucumber scenarios; step-defs share this
 * context. {@link TestAuthConfig} stubs the security gate and the meme directory, so "signed in"
 * means presenting its well-known test token and "the known meme" is the one it vouches for.
 * {@link VoteStoreOutageConfig} puts a breaker in front of the vote store so a scenario can
 * stage its outage.
 */
@CucumberContextConfiguration
@SpringBootTest(classes = {CommentsApplication.class, TestAuthConfig.class, VoteStoreOutageConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberSpringConfiguration {
}
