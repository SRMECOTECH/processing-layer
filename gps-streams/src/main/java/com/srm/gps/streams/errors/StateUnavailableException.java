package com.srm.gps.streams.errors;

/**
 * Something a processor needs from outside Kafka (the database fallback for a device's previous reading) cannot be
 * reached right now. The record is fine: it must be retried, never parked - {@link DeadLetterProcessingExceptionHandler}
 * fails the task for it, the thread is replaced and the record is processed again from the last commit.
 */
public class StateUnavailableException extends RuntimeException {

    public StateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public static boolean isCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof StateUnavailableException) {
                return true;
            }
        }
        return false;
    }
}
