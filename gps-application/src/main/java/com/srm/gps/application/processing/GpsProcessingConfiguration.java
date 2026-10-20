package com.srm.gps.application.processing;

import com.srm.gps.processing.GpsFrameProcessor;
import com.srm.gps.processing.step.DistanceSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns the plain-Java processing module into a Spring bean.
 * Both flows use this one processor, so a frame is processed exactly the same way in either flow.
 */
@Configuration
public class GpsProcessingConfiguration {

    @Bean
    public GpsFrameProcessor gpsFrameProcessor(DatabaseBackedPreviousReadingStore previousReadingStore, DistanceProperties distance) {
        return new GpsFrameProcessor(previousReadingStore, distance.toSettings());
    }

    /** gps.distance.* in application.yml: Traccar's coordinates filter and max-speed filter. */
    @ConfigurationProperties("gps.distance")
    public record DistanceProperties(Double jitterMeters, Integer jitterMaxSpeedKmph, Double maxImpliedSpeedKmph, Double minJumpMeters) {

        DistanceSettings toSettings() {
            DistanceSettings d = DistanceSettings.defaults();
            return new DistanceSettings(
                    jitterMeters != null ? jitterMeters : d.jitterMeters(),
                    jitterMaxSpeedKmph != null ? jitterMaxSpeedKmph : d.jitterMaxSpeedKmph(),
                    maxImpliedSpeedKmph != null ? maxImpliedSpeedKmph : d.maxImpliedSpeedKmph(),
                    minJumpMeters != null ? minJumpMeters : d.minJumpMeters());
        }
    }
}
