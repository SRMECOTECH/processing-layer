package com.srm.gps.application.rejection;

import com.srm.gps.application.archive.RejectedFrameArchive;
import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.connectivity.kafka.RejectedGpsFrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps every rejected frame: archived in PostgreSQL (rejected_frame, kept beyond Kafka's month) and written to the
 * service log with the reason, so bad devices are easy to spot.
 * Kafka flow: called by consumer group "gps-rejected-frame-logger". Direct flow: called directly.
 * The archive comes first: if PostgreSQL is down the consumer retries, so a rejection is never only in a log line.
 */
@Service
public class RejectedGpsFrameLoggingService implements RejectedGpsFrameHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RejectedGpsFrameLoggingService.class);

    private final RejectedFrameArchive archive;

    public RejectedGpsFrameLoggingService(RejectedFrameArchive archive) {
        this.archive = archive;
    }

    @Override
    public void handle(RejectedGpsFrame rejection) {
        archive.store(rejection);
        LOG.warn("Frame rejected: {} | frame: {}", rejection.rejectionReason(), rejection.originalPayload());
    }
}
