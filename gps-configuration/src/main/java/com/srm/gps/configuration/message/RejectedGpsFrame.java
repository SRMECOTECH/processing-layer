package com.srm.gps.configuration.message;

import java.time.Instant;

/**
 * A frame the processing refused.
 *
 * @param imei             the device's IMEI if the frame had one, otherwise null (for example: not valid JSON)
 * @param originalPayload  the frame exactly as it arrived, so the problem can be reproduced
 * @param rejectionReason  a plain-English reason, for example "Latitude is outside -90..90"
 * @param rejectedAt       the moment the frame was refused
 */
public record RejectedGpsFrame(
        String imei,
        String originalPayload,
        String rejectionReason,
        Instant rejectedAt) {
}
