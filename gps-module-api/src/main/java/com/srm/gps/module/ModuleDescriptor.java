package com.srm.gps.module;

import com.srm.gps.configuration.PipelineStage;

import java.util.List;

/**
 * What the platform needs to know to run a module and show it in the registry.
 *
 * @param id             short lower-case name: the switch is gps.modules.{id}.enabled, the API is /api/modules/{id}
 * @param name           shown in the console ("Speed violation")
 * @param summary        one sentence on what it finds
 * @param consumerGroup  the module's Kafka consumer group: its bookmark on gps.processed-positions. Never rename it
 *                       once it has run - a new name starts again from the oldest reading Kafka holds
 * @param stage          the platform stage whose load counters it feeds (logs, engine dashboard), or null for a
 *                       module plugged in from outside: its load is then counted under its id
 * @param order          position in the direct flow, where modules run one after the other (lower first)
 * @param emits          the event types or records it produces
 * @param statePatterns  Redis key patterns of the module's state ("gps:state:speed:*"), deleted before a replay
 */
public record ModuleDescriptor(
        String id,
        String name,
        String summary,
        String consumerGroup,
        PipelineStage stage,
        int order,
        List<String> emits,
        List<String> statePatterns) {

    public ModuleDescriptor {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("A module id is lower-case letters, digits and '-': " + id);
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("Module " + id + " needs a consumer group");
        }
        emits = emits == null ? List.of() : List.copyOf(emits);
        statePatterns = statePatterns == null ? List.of() : List.copyOf(statePatterns);
        for (String pattern : statePatterns) {
            // a replay deletes these: never let a module wipe another module's (or the configuration's) keys
            if (!pattern.startsWith("gps:state:") && !pattern.startsWith("gps:history:")) {
                throw new IllegalArgumentException("Module " + id + " may only own gps:state:* and gps:history:* keys, not " + pattern);
            }
        }
    }
}
