package com.srm.gps.application.flow.kafka;

import com.srm.gps.application.flow.RawGpsFrameSender;
import com.srm.gps.configuration.ProcessingFlowNames;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.kafka.RawGpsFramePublisher;
import org.springframework.stereotype.Component;

/** Kafka flow, step 0: puts the raw frame on "gps.raw-device-frames". Everything else happens in the consumers. */
@Component
@OnlyInKafkaFlow
public class KafkaRawGpsFrameSender implements RawGpsFrameSender {

    private final RawGpsFramePublisher rawGpsFramePublisher;

    public KafkaRawGpsFrameSender(RawGpsFramePublisher rawGpsFramePublisher) {
        this.rawGpsFramePublisher = rawGpsFramePublisher;
    }

    @Override
    public void send(RawGpsFrame frame) {
        rawGpsFramePublisher.publish(frame);
    }

    @Override
    public String flowName() {
        return ProcessingFlowNames.KAFKA;
    }
}
