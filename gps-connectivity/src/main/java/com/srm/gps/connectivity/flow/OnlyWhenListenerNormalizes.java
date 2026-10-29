package com.srm.gps.connectivity.flow;

import com.srm.gps.configuration.ProcessingFlowNames;
import com.srm.gps.configuration.StreamsNormalization;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the @KafkaListener normalizer (raw frame -> processed position): it exists in the Kafka flow unless
 * Kafka Streams owns normalization (gps.streams.normalization=owner). One owner per responsibility, never two -
 * they share the consumer group, so the second one could not even join.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnlyWhenListenerNormalizes.ListenerNormalizesCondition.class)
public @interface OnlyWhenListenerNormalizes {

    class ListenerNormalizesCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            boolean kafkaFlow = ProcessingFlowNames.KAFKA.equals(
                    environment.getProperty(ProcessingFlowNames.PROPERTY, ProcessingFlowNames.KAFKA));
            boolean streamsEnabled = environment.getProperty(StreamsNormalization.ENABLED_PROPERTY, Boolean.class, true);
            return kafkaFlow && StreamsNormalization.listenerNormalizes(streamsEnabled,
                    environment.getProperty(StreamsNormalization.PROPERTY));
        }
    }
}
