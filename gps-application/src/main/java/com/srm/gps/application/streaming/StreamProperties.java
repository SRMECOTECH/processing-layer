package com.srm.gps.application.streaming;

import com.srm.gps.processing.ignition.IgnitionSettings;
import com.srm.gps.processing.state.VehicleStateProcessor;
import com.srm.gps.processing.stoppage.StoppageSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gps.stream.* in application.yml: tuning of the four stream consumers. Every value is optional;
 * a missing one falls back to the algorithm's default.
 */
@ConfigurationProperties("gps.stream")
public record StreamProperties(Long historyLength, Stoppage stoppage, Ignition ignition, VehicleState vehicleState,
                               Enrichment enrichment) {

    public long historyLengthOrDefault() {
        return historyLength != null ? historyLength : 1000;
    }

    public StoppageSettings stoppageSettings() {
        StoppageSettings d = StoppageSettings.defaults();
        Stoppage s = stoppage != null ? stoppage : new Stoppage(null, null, null, null, null, null);
        return new StoppageSettings(
                s.radiusMeters() != null ? s.radiusMeters() : d.radiusMeters(),
                s.minDurationSeconds() != null ? s.minDurationSeconds() : d.minDurationSeconds(),
                s.stationarySpeedKmph() != null ? s.stationarySpeedKmph() : d.stationarySpeedKmph(),
                s.exitConfirmReadings() != null ? s.exitConfirmReadings() : d.exitConfirmReadings(),
                s.maxGapSeconds() != null ? s.maxGapSeconds() : d.maxGapSeconds(),
                s.mergeGapSeconds() != null ? s.mergeGapSeconds() : d.mergeGapSeconds());
    }

    public IgnitionSettings ignitionSettings() {
        IgnitionSettings d = IgnitionSettings.defaults();
        Ignition i = ignition != null ? ignition : new Ignition(null, null, null, null);
        return new IgnitionSettings(
                i.virtualOffAfterSeconds() != null ? i.virtualOffAfterSeconds() : d.virtualOffAfterSeconds(),
                i.idleSpeedKmph() != null ? i.idleSpeedKmph() : d.idleSpeedKmph(),
                i.idleAlertSeconds() != null ? i.idleAlertSeconds() : d.idleAlertSeconds(),
                d.moveSpeedKmph(), d.moveDistanceMeters(), d.deviceDebounceReadings(),
                i.virtualDebounceReadings() != null ? i.virtualDebounceReadings() : d.virtualDebounceReadings(),
                d.maxCountedGapSeconds());
    }

    public VehicleStateProcessor.Settings vehicleStateSettings() {
        VehicleStateProcessor.Settings d = VehicleStateProcessor.Settings.defaults();
        VehicleState v = vehicleState != null ? vehicleState : new VehicleState(null, null);
        return new VehicleStateProcessor.Settings(
                v.gpsSigmaMeters() != null ? v.gpsSigmaMeters() : d.gpsSigmaMeters(),
                v.accelerationSigma() != null ? v.accelerationSigma() : d.accelerationSigma(),
                d.harshAccelerationMps2(), d.harshBrakingMps2(), d.harshMaxIntervalSeconds(), d.maxCountedGapSeconds(),
                d.kalmanResetGapSeconds(), d.headingMinMoveMeters(), d.reoriginMeters());
    }

    public Enrichment enrichmentOrDefault() {
        return enrichment != null ? enrichment : new Enrichment(true, null, null, null, null);
    }

    public record Stoppage(Double radiusMeters, Long minDurationSeconds, Double stationarySpeedKmph,
                           Integer exitConfirmReadings, Long maxGapSeconds, Long mergeGapSeconds) {
    }

    public record Ignition(Long virtualOffAfterSeconds, Double idleSpeedKmph, Long idleAlertSeconds, Integer virtualDebounceReadings) {
    }

    public record VehicleState(Double gpsSigmaMeters, Double accelerationSigma) {
    }

    /**
     * The stoppage context agents.
     *
     * @param weatherEnabled       ask Open-Meteo (free, no key) for the weather at the stop; coordinates are rounded
     *                             to 0.1 degree (about 11 km) before they leave the service
     * @param weatherUrl           Open-Meteo forecast endpoint
     * @param tomtomApiKey         optional: with a key, TomTom traffic flow is asked as well as the fleet itself
     * @param trafficRadiusMeters  how far around the stop other vehicles count for fleet-sourced traffic
     * @param timeoutMillis        per external call
     */
    public record Enrichment(Boolean weatherEnabled, String weatherUrl, String tomtomApiKey, Double trafficRadiusMeters,
                             Long timeoutMillis) {

        public boolean weatherOn() {
            return weatherEnabled == null || weatherEnabled;
        }

        public String weatherEndpoint() {
            return weatherUrl != null && !weatherUrl.isBlank() ? weatherUrl : "https://api.open-meteo.com/v1/forecast";
        }

        public double trafficRadius() {
            return trafficRadiusMeters != null ? trafficRadiusMeters : 1000;
        }

        public long timeout() {
            return timeoutMillis != null ? timeoutMillis : 3000;
        }

        public boolean tomtomOn() {
            return tomtomApiKey != null && !tomtomApiKey.isBlank();
        }
    }
}
