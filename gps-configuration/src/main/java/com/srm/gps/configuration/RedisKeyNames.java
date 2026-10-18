package com.srm.gps.configuration;

/**
 * The single list of Redis keys. Open RedisInsight (http://localhost:8001) and look for "gps:".
 *
 *   gps:live:vehicle:{imei}          hash    latest position of one device (vehicle, lat, lon, speed, status, times)
 *   gps:live:positions               geo set every device's latest point - ready for "who is near X?" and geofencing
 *   gps:engine:stage:{stage}         hash    load of one producer/consumer: total, per second, failures, last message
 *   gps:engine:stage:{stage}:by-imei hash    messages handled per IMEI by that producer/consumer
 */
public final class RedisKeyNames {

    public static final String LIVE_POSITIONS_GEO = "gps:live:positions";

    private static final String LIVE_VEHICLE_PREFIX = "gps:live:vehicle:";
    private static final String ENGINE_STAGE_PREFIX = "gps:engine:stage:";

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
}
