package com.jrobertgardzinski.comments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrobertgardzinski.outbox.OutboxEvent;

/**
 * The confirmation channel with the outbox taken out, and NOTHING else faked: it builds the event
 * exactly as production does — {@link PurgeConfirmations#confirmationOf} — and keeps it instead of
 * writing a row.
 *
 * <p>That distinction is the whole point for the pact test. The contract is about the PAYLOAD the
 * orchestrator parses, so the payload must come from the real builder driven by the real listener; a
 * hand-written JSON string in a test would verify the test's own idea of the envelope. What the pact
 * has no opinion about is where the bytes are stored on the way out, which is the only thing missing
 * here (and is covered by {@code PurgeConfirmationOutboxTest} against the real table).
 */
class CapturedConfirmations extends PurgeConfirmations {

    private OutboxEvent captured;

    CapturedConfirmations() {
        super(null, new ObjectMapper());
    }

    @Override
    void confirm(String sagaId, String leaver) {
        captured = confirmationOf(sagaId, leaver);
    }

    /** The confirmation the listener announced, or {@code null} if it announced none. */
    OutboxEvent captured() {
        return captured;
    }
}
