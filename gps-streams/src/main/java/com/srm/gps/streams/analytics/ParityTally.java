package com.srm.gps.streams.analytics;

import java.time.Instant;

/**
 * Shadow mode, per vehicle: how many readings matched, differed, or were only produced by one side, plus the last
 * verdict that was not a match (to see WHAT differed). Vehicles {@link #merge merge} into the fleet total.
 */
public record ParityTally(
        long matched,
        long mismatched,
        long warmup,
        long onlyListener,
        long onlyStreams,
        Instant lastRecordedAt,
        ParityVerdict lastDifference) {

    public static ParityTally empty() {
        return new ParityTally(0, 0, 0, 0, 0, null, null);
    }

    public ParityTally add(ParityVerdict verdict) {
        ParityVerdict.Outcome outcome = verdict.outcome();
        Instant last = lastRecordedAt == null || verdict.recordedAt().isAfter(lastRecordedAt) ? verdict.recordedAt() : lastRecordedAt;
        return new ParityTally(
                matched + (outcome == ParityVerdict.Outcome.MATCH ? 1 : 0),
                mismatched + (outcome == ParityVerdict.Outcome.MISMATCH ? 1 : 0),
                warmup + (outcome == ParityVerdict.Outcome.WARMUP ? 1 : 0),
                onlyListener + (outcome == ParityVerdict.Outcome.ONLY_LISTENER ? 1 : 0),
                onlyStreams + (outcome == ParityVerdict.Outcome.ONLY_STREAMS ? 1 : 0),
                last,
                outcome == ParityVerdict.Outcome.MATCH ? lastDifference : verdict);
    }

    public ParityTally merge(ParityTally other) {
        Instant last = lastRecordedAt == null ? other.lastRecordedAt
                : other.lastRecordedAt == null || lastRecordedAt.isAfter(other.lastRecordedAt) ? lastRecordedAt : other.lastRecordedAt;
        return new ParityTally(matched + other.matched, mismatched + other.mismatched, warmup + other.warmup,
                onlyListener + other.onlyListener, onlyStreams + other.onlyStreams, last, null);
    }

    public long compared() {
        return matched + mismatched + warmup + onlyListener + onlyStreams;
    }
}
