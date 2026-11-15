package com.srm.gps.application.modules;

import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every processing module on the class path (each {@link GpsModule} bean), and which of them are switched on:
 * gps.modules.{id}.enabled, on unless set to false. The Kafka flow gives each enabled module its own consumer group
 * ({@link ModuleKafkaListeners}); the direct flow calls them one after the other in {@link ModuleDescriptor#order()}.
 */
@Component
public class ModuleRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistry.class);

    private final List<GpsModule> modules;
    private final Environment environment;

    public ModuleRegistry(List<GpsModule> modules, Environment environment) {
        this.modules = modules.stream().sorted(Comparator.comparingInt(m -> m.descriptor().order())).toList();
        this.environment = environment;
        Set<String> ids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (GpsModule module : this.modules) {
            ModuleDescriptor d = module.descriptor();
            // two modules on one group would split the readings between them instead of each seeing all of them
            if (!ids.add(d.id()) || !groups.add(d.consumerGroup())) {
                throw new IllegalStateException("Two processing modules share the id or consumer group of " + d.id() + " (" + d.consumerGroup() + ")");
            }
        }
        LOG.info("Processing modules: {}", this.modules.stream()
                .map(m -> m.descriptor().id() + (enabled(m) ? "" : " (off)")).toList());
    }

    /** Every module, switched on or not, in order. */
    public List<GpsModule> all() {
        return modules;
    }

    public List<GpsModule> enabled() {
        return modules.stream().filter(this::enabled).toList();
    }

    public Optional<GpsModule> find(String id) {
        return modules.stream().filter(m -> m.descriptor().id().equals(id)).findFirst();
    }

    public boolean enabled(GpsModule module) {
        return environment.getProperty("gps.modules." + module.descriptor().id() + ".enabled", Boolean.class, true);
    }
}
