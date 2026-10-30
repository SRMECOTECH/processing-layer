package com.srm.gps.application.modules;

import com.srm.gps.configuration.KafkaConsumerGroupNames;
import com.srm.gps.configuration.KafkaTopicNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.ProcessedGpsPosition;
import com.srm.gps.connectivity.flow.OnlyInKafkaFlow;
import com.srm.gps.connectivity.kafka.KafkaBacklogSkipper.SkippableListenerGroup;
import com.srm.gps.connectivity.kafka.SkippableListener;
import com.srm.gps.connectivity.monitoring.PipelineLogContext;
import com.srm.gps.module.GpsModule;
import com.srm.gps.module.ModuleDescriptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * One Kafka listener per enabled processing module, created at start-up from the module's descriptor - what a
 * hand-written @KafkaListener class per module used to be:
 *
 *   topic gps.processed-positions, group = the module's consumer group, one consumer per partition
 *
 * They are ordinary listener containers of the Spring Kafka registry (id "module-{id}"), built by the same container
 * factory as every other listener, so they share its error handling: retry for ever while PostgreSQL / Redis / Kafka
 * are down (the offset is not committed and the reading waits in Kafka), dead letters for a reading that cannot be
 * handled. The demo reset pauses them and moves them to the end like the others, and {@link ModuleReplayService}
 * stops one, moves its bookmark back and starts it again.
 */
@Configuration
@OnlyInKafkaFlow
public class ModuleKafkaListeners implements KafkaListenerConfigurer, SkippableListenerGroup {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleKafkaListeners.class);

    private final ModuleRegistry registry;
    private final ModuleRunner runner;
    private final List<ModuleListener> listeners = new ArrayList<>();

    public ModuleKafkaListeners(ModuleRegistry registry, ModuleRunner runner) {
        this.registry = registry;
        this.runner = runner;
    }

    /** The listener container id of a module in the Spring Kafka registry. */
    public static String containerId(String moduleId) {
        return "module-" + moduleId;
    }

    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        DefaultMessageHandlerMethodFactory handlerMethods = new DefaultMessageHandlerMethodFactory();
        handlerMethods.afterPropertiesSet();
        for (GpsModule module : registry.enabled()) {
            ModuleDescriptor d = module.descriptor();
            ModuleListener listener = new ModuleListener(module, runner);
            MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
            endpoint.setId(containerId(d.id()));
            endpoint.setGroupId(d.consumerGroup());
            endpoint.setTopics(KafkaTopicNames.PROCESSED_POSITIONS);
            endpoint.setConcurrency(Integer.valueOf(KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION));
            endpoint.setBean(listener);
            endpoint.setMethod(ModuleListener.METHOD);
            endpoint.setMessageHandlerMethodFactory(handlerMethods);
            registrar.registerEndpoint(endpoint);
            listeners.add(listener);
            LOG.info("Module {} reads {} as consumer group {}", d.id(), KafkaTopicNames.PROCESSED_POSITIONS, d.consumerGroup());
        }
    }

    @Override
    public List<? extends SkippableListener> skippableListeners() {
        return List.copyOf(listeners);
    }

    /**
     * The listener of one module. Readings are keyed by IMEI, so one vehicle's readings come from one partition,
     * one at a time and in order.
     */
    public static final class ModuleListener extends SkippableListener {

        static final java.lang.reflect.Method METHOD;

        static {
            try {
                METHOD = ModuleListener.class.getMethod("onRecord", ConsumerRecord.class);
            } catch (NoSuchMethodException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private final GpsModule module;
        private final ModuleRunner runner;
        private final String stageName;

        ModuleListener(GpsModule module, ModuleRunner runner) {
            this.module = module;
            this.runner = runner;
            ModuleDescriptor d = module.descriptor();
            this.stageName = d.stage() != null ? d.stage().shortName() : d.id();
        }

        public void onRecord(ConsumerRecord<String, String> record) {
            try (PipelineLogContext ignored = PipelineLogContext.forRecord(stageName, record)) {
                runner.run(module, GpsMessageJson.fromJson(record.value(), ProcessedGpsPosition.class), record.partition());
            } catch (RuntimeException failure) {
                // Kafka retries the reading with a back-off and only logs when it gives up, so say why here.
                LOG.warn("Module {} failed, will be retried: {}", module.descriptor().id(), failure.toString(), failure);
                throw failure;
            }
        }
    }
}
