package com.srm.gps.connectivity.kafka;

/** Whoever keeps the dead letters implements this (the archive in PostgreSQL). It must be idempotent. */
public interface DeadLetterHandler {

    void handle(DeadLetter deadLetter);
}
