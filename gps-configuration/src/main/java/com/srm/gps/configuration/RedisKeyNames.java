package com.srm.gps.configuration;

import java.util.List;

/**
 * The single list of Redis keys. Open RedisInsight (http://localhost:8001) and look for "gps:".
 *
 *   gps:live:vehicle:{imei}          hash    latest position of one device (vehicle, lat, lon, speed, status, times)
 *   gps:live:positions               geo set every device's latest point - ready for "who is near X?" and geofencing
 *   gps:engine:stage:{stage}         hash    load of one producer/consumer: total, per second, failures, last message
 *   gps:engine:stage:{stage}:by-imei hash    messages handled per IMEI by that producer/consumer
 *
 *   gps:state:vehicle:{imei}         hash    vehicle state: position, heading, acceleration, odometer, Kalman filter ...
 *   gps:history:{imei}               stream  recent readings of one vehicle (capped), the vehicle's historical context
 *   gps:state:speed:{imei}           hash    speed-violation state: phase, episode start, ping count, max, verification
 *   gps:state:ignition:{imei}        hash    ignition state: on/off, source, engine seconds, idling
 *   gps:state:stoppage:{imei}        hash    stoppage state: phase, anchor, centroid, start, classification
 *   gps:events:{imei}                list    the vehicle's latest events (newest first, capped)
 *
 *   gps:config:speed-rule:{scope}    hash    speed rule; scope = default | tenant:{code} | vehicle:{registration}
 *   gps:config:speed-rules           set     every scope that has a rule
 *   gps:context:weather:{cell}       string  cached weather around a 0.1 degree cell (expires)
 *
 * The Redis can be shared (the HP home server's, Developer -> Connections), so nothing here matches other programs'
 * keys there: the HP ingestion API writes gps:points, last:*, track:* and devices.
 */
public final class RedisKeyNames {

    public static final String LIVE_POSITIONS_GEO = "gps:live:positions";
    public static final String SPEED_RULE_SCOPES = "gps:config:speed-rules";
    public static final String DEFAULT_SPEED_RULE_SCOPE = "default";

    /**
     * Everything telemetry-related the platform writes; the demo reset deletes these. Configuration (gps:config:*) is
     * kept, and so is any key another program keeps in the same Redis (the HP ingestion API's gps:points).
     */
    public static final List<String> TELEMETRY_PATTERNS =
            List.of("gps:live:*", "gps:engine:*", "gps:state:*", "gps:history:*", "gps:events:*", "gps:context:*");
    public static final String CONFIG_PATTERN = "gps:config:*";

    private static final String LIVE_VEHICLE_PREFIX = "gps:live:vehicle:";
    private static final String ENGINE_STAGE_PREFIX = "gps:engine:stage:";
    private static final String VEHICLE_STATE_PREFIX = "gps:state:vehicle:";
    private static final String HISTORY_PREFIX = "gps:history:";
    private static final String SPEED_STATE_PREFIX = "gps:state:speed:";
    private static final String IGNITION_STATE_PREFIX = "gps:state:ignition:";
    private static final String STOPPAGE_STATE_PREFIX = "gps:state:stoppage:";
    private static final String EVENTS_PREFIX = "gps:events:";
    private static final String SPEED_RULE_PREFIX = "gps:config:speed-rule:";
    private static final String WEATHER_PREFIX = "gps:context:weather:";

    private RedisKeyNames() {
    }

    public static String liveVehicle(String imei) {
        return LIVE_VEHICLE_PREFIX + imei;
    }

    public static String engineStage(PipelineStage stage) {
        return ENGINE_STAGE_PREFIX + stage.shortName();
    }

    public static String engineStageByImei(PipelineStage stage) {
        return engineStage(stage) + ":by-imei";
    }

    public static String vehicleState(String imei) {
        return VEHICLE_STATE_PREFIX + imei;
    }

    public static String history(String imei) {
        return HISTORY_PREFIX + imei;
    }

    public static String speedState(String imei) {
        return SPEED_STATE_PREFIX + imei;
    }

    public static String ignitionState(String imei) {
        return IGNITION_STATE_PREFIX + imei;
    }

    public static String stoppageState(String imei) {
        return STOPPAGE_STATE_PREFIX + imei;
    }

    public static String events(String imei) {
        return EVENTS_PREFIX + imei;
    }

    /** Prefix for scanning all speed-violation states. */
    public static String speedStatePattern() {
        return SPEED_STATE_PREFIX + "*";
    }

    public static String speedRule(String scope) {
        return SPEED_RULE_PREFIX + scope;
    }

    public static String tenantScope(String tenantCode) {
        return "tenant:" + tenantCode;
    }

    public static String vehicleScope(String vehicleRegistrationNo) {
        return "vehicle:" + vehicleRegistrationNo;
    }

    public static String weather(String cell) {
        return WEATHER_PREFIX + cell;
    }
}
