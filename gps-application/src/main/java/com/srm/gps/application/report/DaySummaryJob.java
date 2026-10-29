package com.srm.gps.application.report;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the daily summaries current: today and yesterday every few minutes (late readings of yesterday still count),
 * every day that still has positions once after the start, and again when the economical speed changes.
 * A run reads one day of positions per day summarized, so its cost does not grow with the months kept.
 */
@Component
public class DaySummaryJob {

    private static final Logger LOG = LoggerFactory.getLogger(DaySummaryJob.class);

    private final DaySummarizer summarizer;
    private final int backfillDays;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "report-backfill");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean backfilled;

    public DaySummaryJob(DaySummarizer summarizer, ReportProperties properties) {
        this.summarizer = summarizer;
        this.backfillDays = properties.backfillDaysOrDefault();
    }

    @Scheduled(fixedDelayString = "${gps.reports.refresh-every:5m}", initialDelayString = "${gps.reports.first-refresh-after:40s}")
    public void refreshRecentDays() {
        LocalDate today = summarizer.today();
        try {
            int vehicles = summarizer.summarize(today);
            summarizer.summarize(today.minusDays(1));
            LOG.debug("Report summaries refreshed: {} vehicles today", vehicles);
            if (!backfilled) {
                backfilled = true;
                recomputeAllSoon();
            }
        } catch (RuntimeException problem) {
            LOG.warn("Could not refresh the report summaries: {}", problem.getMessage());
        }
    }

    /** Rebuilds every day that still has positions, in the background (after a change of the economical speed). */
    public void recomputeAllSoon() {
        background.execute(() -> {
            try {
                int days = summarizer.summarizeAll(backfillDays);
                LOG.info("Report summaries rebuilt for {} days", days);
            } catch (RuntimeException problem) {
                LOG.warn("Could not rebuild the report summaries: {}", problem.getMessage());
            }
        });
    }

    @PreDestroy
    void shutDown() {
        background.shutdownNow();
    }
}
