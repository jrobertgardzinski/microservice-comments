package com.jrobertgardzinski.comments.infrastructure;

import com.jrobertgardzinski.observation.Observations;
import com.jrobertgardzinski.comments.domain.Observation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What this service does when nothing is watching: it goes on working.
 *
 * <p>The boundary made real rather than described. A service with no watcher is not a broken
 * service, so the port must have an answer even when the adapter is gone — and the day the
 * observability adapter becomes its own module, removing it from the assembly has to leave the
 * sagas passing rather than fail the context on a missing dependency.
 *
 * <p>It states nothing anywhere on purpose, not even a log line: a "nobody is listening" warning
 * once a minute is itself a watcher, and a noisy one.
 */
@Configuration
class SilentObservations {

    @Bean
    @ConditionalOnMissingBean(Observations.class)
    Observations<Observation> silence() {
        return Observations.silent();
    }
}
