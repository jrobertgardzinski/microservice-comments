package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses a production-path start without a declared deployment profile. The bare
 * application.properties holds only the service's invariants; everything environment-shaped is
 * the deployment's to declare — so a start that names no profile is a start nobody decided, and
 * the most common way it happens is a FORGOTTEN profile, which is exactly when strictness
 * matters (default-deny).
 *
 * <p>Registered in {@code main()} and ONLY there — wiring, not classpath sniffing: tests boot
 * the context through SpringBootTest and never enter main, so the guard sits exactly on the one
 * path (java -jar, the container) where an undeclared start can happen.
 */
final class ProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Set<String> DEPLOYMENT_PROFILES = Set.of("dev", "test", "prod");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String[] active = event.getEnvironment().getActiveProfiles();
        List<String> declared = Arrays.stream(active)
                .filter(DEPLOYMENT_PROFILES::contains)
                .toList();
        if (declared.isEmpty())
            throw new IllegalStateException("no deployment profile declared (active: "
                    + Arrays.toString(active) + ") - start with SPRING_PROFILES_ACTIVE=dev|prod");
        if (declared.contains("dev") && declared.contains("prod"))
            throw new IllegalStateException("both 'dev' and 'prod' are active ("
                    + Arrays.toString(active) + ") - an ambiguous start is worse than a refused one");
    }
}
