package com.srm.gps.processing;

import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.RejectedGpsFrame;

/** What the movement step produced for one frame: exactly one of the two. */
public sealed interface ProcessingResult {

    /** The frame was good: here is the processed position. */
    record Accepted(ProcessedGpsPosition position) implements ProcessingResult {
    }

    /** The frame was refused: here is the original frame and the reason. */
    record Rejected(RejectedGpsFrame rejection) implements ProcessingResult {
    }
}
