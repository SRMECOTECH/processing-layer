package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.RawGpsFrame;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.journal.DurableKafkaSender;
import org.springframework.stereotype.Component;

/**
 * Producer 1: sends raw GPS frames to Kafka, exactly as a device would.
 * The IMEI is the message key, so all frames of one device go to the same partition and stay in order.
 *
 * Today the simulator calls this; later the real device parser will publish to the same topic.
 * The frame is in the ingest journal on disk before this returns, and stays there until Kafka has it - Kafka down,
 * the service stopped mid-send: the frame is sent again, never lost. The caller does not wait for Kafka.
 */
@Component
@OnlyInKafkaFlow
public class RawGpsFramePublisher {

    private final DurableKafkaSender sender;

    public RawGpsFramePublisher(DurableKafkaSender sender) {
        this.sender = sender;
    }

    public void publish(RawGpsFrame frame) {
        sender.send(KafkaTopicNames.RAW_DEVICE_FRAMES, frame.imei(), GpsMessageJson.toJson(frame));
    }
}
