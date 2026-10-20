package com.srm.gps.configuration.message;

/** The kinds of vehicle events the stream consumers raise. */
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
    HARSH_BRAKING
}
