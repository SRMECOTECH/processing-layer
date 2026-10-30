package com.srm.gps.configuration.message;

/** The kinds of vehicle events the stream consumers and processing modules raise. */
public enum VehicleEventType {
    /** Speed over the entity's limit for the rule's duration and ping count (episode). */
    OVERSPEED,
    /** The speedometer and the GPS (distance over time) disagree for several readings in a row. */
    SPEED_SENSOR_MISMATCH,
    /** Standing still with the engine on (episode), checked against traffic and weather. */
    STOPPAGE,
    /** Standing still with the engine off: neutralised, no alarm (episode). */
    PARKED,
    /** Engine running while standing longer than the idling limit (episode). */
    IDLING,
    IGNITION_ON,
    IGNITION_OFF,
    HARSH_ACCELERATION,
    HARSH_BRAKING,
    /** A shipment's vehicle cleared the origin (plant) geofence: the real departure, after the gate-out. */
    SHIPMENT_DEPARTED,
    /** A shipment's vehicle entered the destination geofence. */
    SHIPMENT_ARRIVED,
    /** A shipment still on the road after its ETA (episode: closed when it arrives). */
    SHIPMENT_DELAYED
}
