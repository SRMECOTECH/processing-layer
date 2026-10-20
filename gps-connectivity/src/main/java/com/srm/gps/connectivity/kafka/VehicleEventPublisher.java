package com.srm.gps.connectivity.kafka;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.PipelineStage;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Producer 4: vehicle events (over-speed, stoppage, ignition ...) to gps.vehicle-events, key = IMEI.
 * Downstream services (dispatch, notifications) subscribe here instead of polling the database.
 */
@Component
@OnlyInKafkaFlow
public class VehicleEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineMetrics metrics;

    public VehicleEventPublisher(KafkaTemplate<String, String> kafkaTemplate, PipelineMetrics metrics) {
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
    }

    public void publish(VehicleEvent event) {
        kafkaTemplate.send(KafkaTopicNames.VEHICLE_EVENTS, event.imei(), GpsMessageJson.toJson(event))
                .whenComplete((sent, failure) -> {
                    if (failure == null) {
                        metrics.recordMessage(PipelineStage.VEHICLE_EVENT_PRODUCER, event.imei(), sent.getRecordMetadata().partition());
                    } else {
                        metrics.recordFailure(PipelineStage.VEHICLE_EVENT_PRODUCER);
                        LOG.warn("Could not publish {} of device {}: {}", event.type(), event.imei(), failure.getMessage());
                    }
                });
    }
}
