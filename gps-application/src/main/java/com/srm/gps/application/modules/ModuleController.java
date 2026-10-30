package com.srm.gps.application.modules;

import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.connectivity.kafka.KafkaTopologyInspector;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.connectivity.monitoring.StageSnapshot;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugged-in processing modules (Developer -> Module registry):
 *
 *   GET  /api/modules                          every module: switched on?, running?, consumer group, load, lag
 *   POST /api/modules/{id}/replay?from=ISO     hand the module every reading since that moment again (Kafka flow)
 */
@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    /**
     * @param flowMode        "kafka": own consumer group; "direct": called in order inside the direct flow
     * @param running         its consumers are running (Kafka flow); in the direct flow, whether it is switched on
     * @param lag             readings in Kafka the module has not handled yet, or null when Kafka could not be asked
     */
    public record ModuleView(String id, String name, String summary, String consumerGroup, String stage, int order,
                             List<String> emits, List<String> statePatterns, boolean enabled, boolean running,
                             String flowMode, String reads, double messagesPerSecond, long totalMessages, long failures,
                             Instant lastMessageAt, Long lag) {
    }

    private final ModuleRegistry modules;
    private final PipelineMetrics metrics;
    private final ObjectProvider<ModuleReplayService> replay;
    private final ObjectProvider<KafkaTopologyInspector> inspector;

    public ModuleController(ModuleRegistry modules, PipelineMetrics metrics, ObjectProvider<ModuleReplayService> replay,
                            ObjectProvider<KafkaTopologyInspector> inspector) {
        this.modules = modules;
        this.metrics = metrics;
        this.replay = replay;
        this.inspector = inspector;
    }

    @GetMapping
    public List<ModuleView> list() {
        ModuleReplayService kafka = replay.getIfAvailable();
        Map<String, Long> lagByGroup = lag();
        return modules.all().stream().map(module -> {
            ModuleDescriptor d = module.descriptor();
            boolean enabled = modules.enabled(module);
            StageSnapshot load = d.stage() != null ? metrics.snapshot(d.stage()) : metrics.moduleSnapshot(d.id());
            return new ModuleView(d.id(), d.name(), d.summary(), d.consumerGroup(), d.stage() == null ? null : d.stage().name(),
                    d.order(), d.emits(), d.statePatterns(), enabled, kafka != null ? kafka.running(d.id()) : enabled,
                    kafka != null ? "kafka" : "direct", KafkaTopicNames.PROCESSED_POSITIONS, load.messagesPerSecond(),
                    load.totalMessages(), load.failures(), load.lastMessageAt(), lagByGroup.get(d.consumerGroup()));
        }).toList();
    }

    @PostMapping("/{id}/replay")
    public ModuleReplayService.ReplayReport replay(@PathVariable String id, @RequestParam Instant from) throws Exception {
        ModuleReplayService kafka = replay.getIfAvailable();
        if (kafka == null) {
            throw new IllegalStateException("Replay needs the Kafka flow: the direct flow keeps no readings to replay");
        }
        return kafka.replay(id, from);
    }

    private Map<String, Long> lag() {
        KafkaTopologyInspector kafka = inspector.getIfAvailable();
        Map<String, Long> lag = new HashMap<>();
        if (kafka == null) {
            return lag;
        }
        Map<String, String> topicOfGroup = new HashMap<>();
        for (GpsModule module : modules.enabled()) {
            topicOfGroup.put(module.descriptor().consumerGroup(), KafkaTopicNames.PROCESSED_POSITIONS);
        }
        try {
            kafka.describeGroups(topicOfGroup).forEach(group -> lag.put(group.groupId(), group.totalLag()));
        } catch (Exception kafkaNotAnswering) {
            // the list still shows every module; only the lag column stays empty
        }
        return lag;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> notPossible(IllegalStateException problem) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", problem.getMessage()));
    }
}
