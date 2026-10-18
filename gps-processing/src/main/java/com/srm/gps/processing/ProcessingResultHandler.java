package com.srm.gps.processing;

import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RejectedGpsFrame;

/**
 * Where the result of processing one frame goes. Each flow decides:
 *   Kafka flow  : publish to "gps.processed-positions" / "gps.rejected-frames"
 *   Direct flow : store in PostgreSQL / write to the log
 *
 * Throw an exception if the result could not be delivered; the frame is then not remembered and can be retried.
 */
public interface ProcessingResultHandler {

    void onAccepted(ProcessedGpsPosition position);

    void onRejected(RejectedGpsFrame rejection);
}
