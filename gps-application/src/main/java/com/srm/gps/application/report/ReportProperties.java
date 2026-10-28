package com.srm.gps.application.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * gps.reports.* in application.yml. Every value is optional.
 *
 * @param zone            where a report's day starts and ends (default Asia/Kolkata: midnight to midnight IST)
 * @param maxGapSeconds   a silence longer than this before a MOVING reading counts as neither driving nor standing
 *                        (default 600 s: the device was off or out of coverage)
 * @param maxStoppedGapSeconds the same before a STOPPED reading (default 3600 s: a parked tracker reports rarely)
 * @param backfillDays    at start, days back to summarize when they still have positions (default 62)
 */
@ConfigurationProperties("gps.reports")
public record ReportProperties(String zone, Long maxGapSeconds, Long maxStoppedGapSeconds, Integer backfillDays) {

    public ZoneId zoneOrDefault() {
        return ZoneId.of(zone != null && !zone.isBlank() ? zone : "Asia/Kolkata");
    }

    public long maxGapSecondsOrDefault() {
        return maxGapSeconds != null ? maxGapSeconds : 600;
    }

    public long maxStoppedGapSecondsOrDefault() {
        return maxStoppedGapSeconds != null ? maxStoppedGapSeconds : 3600;
    }

    public int backfillDaysOrDefault() {
        return backfillDays != null ? backfillDays : 62;
    }
}
