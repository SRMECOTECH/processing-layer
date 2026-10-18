package com.srm.gps.application.rejection;

import com.srm.gps.configuration.message.RejectedGpsFrame;
import com.srm.gps.connectivity.kafka.RejectedGpsFrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes every rejected frame into the service log, with the reason, so bad devices are easy to spot.
 * Kafka flow: called by consumer group "gps-rejected-frame-logger". Direct flow: called directly.
 */
@Service
public class RejectedGpsFrameLoggingService implements RejectedGpsFrameHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RejectedGpsFrameLoggingService.class);

    @Override
    public void handle(RejectedGpsFrame rejection) {
        LOG.warn("Frame rejected: {} | frame: {}", rejection.rejectionReason(), rejection.originalPayload());
    }
}
