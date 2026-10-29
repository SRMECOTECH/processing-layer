package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.journal.DurableKafkaSender;
import org.springframework.stereotype.Component;

/**
 * Producer 4: vehicle events (over-speed, stoppage, ignition ...) to gps.vehicle-events, key = IMEI.
 * Downstream services (dispatch, notifications) subscribe here instead of polling the database.
 * Journaled first, like the device frames: an event is never lost because Kafka was briefly away.
 */
@Component
@OnlyInKafkaFlow
public class VehicleEventPublisher {

    private final DurableKafkaSender sender;

    public VehicleEventPublisher(DurableKafkaSender sender) {
        this.sender = sender;
    }

    public void publish(VehicleEvent event) {
        sender.send(KafkaTopicNames.VEHICLE_EVENTS, event.imei(), GpsMessageJson.toJson(event));
    }
}
