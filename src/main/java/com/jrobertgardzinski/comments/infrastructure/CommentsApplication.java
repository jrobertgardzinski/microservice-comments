package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The comments service's Spring Boot entry point. */
@SpringBootApplication
public class CommentsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CommentsApplication.class);
        // the guard lives on the production path only: tests boot through SpringBootTest and
        // never enter main, so they need no profile and no escape hatch
        application.addListeners(new ProfileGuard());
        application.run(args);
    }
}
