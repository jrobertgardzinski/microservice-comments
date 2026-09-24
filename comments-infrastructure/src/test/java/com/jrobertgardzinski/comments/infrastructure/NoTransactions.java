package com.jrobertgardzinski.comments.infrastructure;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link TransactionTemplate} whose transaction is nothing at all: it runs the callback and returns.
 *
 * <p>For the listener's UNIT tests, which are about what the listener does and in which order — purge
 * before confirm, no confirmation for a dropped command, nothing quoted into the log. Whether the
 * transaction really rolls back is a property of a real transaction manager and a real table, so it is
 * pinned where it can be true: {@code PurgeConfirmationOutboxTest} drives the same listener over the
 * Spring context's transaction manager and the Flyway schema.
 */
final class NoTransactions {

    private NoTransactions() {
    }

    static TransactionTemplate template() {
        return new TransactionTemplate(new PlatformTransactionManager() {

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }
}
