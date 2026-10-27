package com.srm.gps.connectivity.kafka;

import java.io.IOException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * Sorts a consumer's failure into one of two kinds, because they need opposite treatment:
 *
 *   infrastructure : PostgreSQL, Redis, Kafka or the network is unavailable. The record is fine - wait and retry
 *                    for as long as it takes; the partition pauses, nothing is skipped.
 *   poison         : anything else (a broken message, a database rule it breaks, a bug). Waiting cannot fix it -
 *                    after a few tries it goes to the dead-letter topic, so the partition moves on and the record
 *                    is kept for a fix and a replay.
 *
 * Matches by class name through the whole cause chain, so this module needs no JDBC, Redis or HTTP client classes.
 */
public final class FailureClassifier {

    private static final Set<String> INFRASTRUCTURE = Set.of(
            "org.springframework.dao.TransientDataAccessException",
            "org.springframework.dao.DataAccessResourceFailureException",
            "org.springframework.dao.RecoverableDataAccessException",
            "org.springframework.transaction.CannotCreateTransactionException",
            "org.springframework.web.client.ResourceAccessException",
            "org.apache.kafka.common.errors.RetriableException",
            "java.util.concurrent.TimeoutException");

    private FailureClassifier() {
    }

    public static boolean isInfrastructure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof IOException || cause instanceof SQLTransientException || cause instanceof SQLRecoverableException) {
                return true;
            }
            for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
                if (INFRASTRUCTURE.contains(type.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
