package com.jrobertgardzinski.comments.application;

import com.jrobertgardzinski.comments.domain.Observation;

/**
 * Where this service's {@link Observation}s go — the one port between "the code has noticed
 * something" and whatever is watching this month.
 *
 * <p>The narrowest interface that can carry every fact: a caller states WHAT happened and nothing
 * about how it should be counted, named, labelled or alerted on. Those are the watching tool's
 * decisions, and they are exactly what changes when the tool does.
 *
 * <p>Removing the adapter must leave this service working — an unobserved service is a service
 * nobody is watching, not a broken one.
 */
public interface Observations {

    void record(Observation observation);
}
