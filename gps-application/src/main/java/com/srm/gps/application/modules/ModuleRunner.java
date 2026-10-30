package com.srm.gps.application.modules;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.application.streaming.VehicleContext;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import com.srm.gps.connectivity.redis.RedisStateStore;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleContext;
import com.srm.gps.module.ModuleDescriptor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Hands one reading to one module with its {@link ModuleContext}, and counts it: under the module's PipelineStage
 * when it has one (engine dashboard, logs), otherwise under its id. Used by the Kafka listeners and the direct flow.
 */
@Component
public class ModuleRunner {

    private final VehicleContext vehicles;
    private final RedisStateStore redis;
    private final VehicleEventRecorder recorder;
    private final PipelineMetrics metrics;
    private final Environment environment;

    public ModuleRunner(VehicleContext vehicles, RedisStateStore redis, VehicleEventRecorder recorder, PipelineMetrics metrics,
                        Environment environment) {
        this.vehicles = vehicles;
        this.redis = redis;
        this.recorder = recorder;
        this.metrics = metrics;
        this.environment = environment;
    }

    /** @param partition the Kafka partition, or -1 in the direct flow */
    public void run(GpsModule module, ProcessedGpsPosition position, int partition) {
        ModuleDescriptor d = module.descriptor();
        try {
            module.onPosition(position, new Context(d, position));
        } catch (RuntimeException failure) {
            if (d.stage() != null) {
                metrics.recordFailure(d.stage());
            }
            metrics.recordModuleFailure(d.id());
            throw failure;
        }
        if (d.stage() != null) {
            metrics.recordMessage(d.stage(), position.imei(), partition);
        }
        metrics.recordModuleMessage(d.id(), position.imei(), partition);
    }

    /** The Redis hash of a module's state for one device. */
    public static String stateKey(String moduleId, String imei) {
        return "gps:state:" + moduleId + ":" + imei;
    }

    private final class Context implements ModuleContext {
        private final ModuleDescriptor descriptor;
        private final ProcessedGpsPosition position;
        private VehicleRef vehicle;

        Context(ModuleDescriptor descriptor, ProcessedGpsPosition position) {
            this.descriptor = descriptor;
            this.position = position;
        }

        @Override
        public VehicleRef vehicle() {
            if (vehicle == null) {
                VehicleContext.Vehicle v = vehicles.of(position);
                vehicle = new VehicleRef(v.imei(), v.registrationNo(), v.tenantCode());
            }
            return vehicle;
        }

        @Override
        public ModuleState state() {
            String key = stateKey(descriptor.id(), position.imei());
            return new ModuleState() {
                @Override
                public Map<String, String> read() {
                    return redis.readHash(key);
                }

                @Override
                public void write(Map<String, String> fields) {
                    redis.writeHash(key, fields);
                }

                @Override
                public void clear() {
                    redis.template().delete(key);
                }
            };
        }

        @Override
        public void emit(VehicleEvent event) {
            recorder.record(event);
        }

        @Override
        public String setting(String name, String defaultValue) {
            return environment.getProperty("gps.modules." + descriptor.id() + "." + name, defaultValue);
        }
    }
}
