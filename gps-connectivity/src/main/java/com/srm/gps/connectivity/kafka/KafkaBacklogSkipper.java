package com.srm.gps.connectivity.kafka;

import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import org.springframework.stereotype.Component;

import java.util.List;

/** Moves every listener to the end of its topic partitions (used by the demo reset). */
@Component
@OnlyInKafkaFlow
public class KafkaBacklogSkipper {

    private final List<SkippableListener> listeners;

    public KafkaBacklogSkipper(List<SkippableListener> listeners) {
        this.listeners = listeners;
    }

    public int skipToEnd() {
        listeners.forEach(SkippableListener::seekToEnd);
        return listeners.size();
    }
}
