package com.srm.gps.connectivity.kafka;

import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** Moves every listener to the end of its topic partitions (used by the demo reset). */
@Component
@OnlyInKafkaFlow
public class KafkaBacklogSkipper {

    /**
     * Listeners created at start-up rather than declared as beans, such as one per processing module: the group
     * hands them over so the demo reset moves them too.
     */
    public interface SkippableListenerGroup {
        List<? extends SkippableListener> skippableListeners();
    }

    private final List<SkippableListener> listeners;
    private final ObjectProvider<SkippableListenerGroup> groups;

    public KafkaBacklogSkipper(List<SkippableListener> listeners, ObjectProvider<SkippableListenerGroup> groups) {
        this.listeners = listeners;
        this.groups = groups;
    }

    public int skipToEnd() {
        listeners.forEach(SkippableListener::seekToEnd);
        int moved = listeners.size();
        for (SkippableListenerGroup group : groups.orderedStream().toList()) {
            for (SkippableListener listener : group.skippableListeners()) {
                listener.seekToEnd();
                moved++;
            }
        }
        return moved;
    }
}
