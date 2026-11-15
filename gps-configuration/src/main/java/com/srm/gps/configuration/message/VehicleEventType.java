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
    SHIPMENT_DELAYED,
    /** Inside a geofence long enough to be a visit (stamped at the first reading inside). */
    GEOFENCE_ENTER,
    /** Out of a geofence after a visit (stamped at the last reading inside), with the dwell. */
    GEOFENCE_EXIT,
    /** Through a geofence without stopping long enough to be a visit, or over it between two readings. */
    GEOFENCE_PASS,
    /** Inside a geofence longer than its dwell limit (episode: closed when the vehicle leaves). */
    GEOFENCE_DWELL,
    /** Off the planned route on a clearly slower way (episode: closed when it is back on the route). */
    ROUTE_DEVIATION,
    /** Off the planned route on a road about as good: the re-plan became the plan (information). */
    ROUTE_ALTERNATE,
    /** Driving back along the route, or away from the destination (episode). */
    WRONG_DIRECTION,
    /** The live ETA moved 30 min or more from the last one announced. */
    ETA_CHANGED,
    /** The truck reached the plan's destination: promised against actual. */
    DESTINATION_REACHED,
    /** At a planned stop (2 min inside), stamped at the first reading inside. */
    WAYPOINT_ARRIVED,
    /** Left a planned stop, stamped at the last reading inside, with the stay. */
    WAYPOINT_DEPARTED,
    /** A planned stop never visited: the truck went past it or reached the destination. */
    WAYPOINT_MISSED
}
