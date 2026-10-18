package com.srm.gps.application.processing;

import com.srm.gps.processing.GpsFrameProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Turns the plain-Java processing module into a Spring bean.
 * Both flows use this one processor, so a frame is processed exactly the same way in either flow.
 */
@Configuration
public class GpsProcessingConfiguration {

    @Bean
    public GpsFrameProcessor gpsFrameProcessor(DatabaseBackedPreviousReadingStore previousReadingStore) {
        return new GpsFrameProcessor(previousReadingStore);
    }
}
