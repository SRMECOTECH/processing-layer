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

/** Marks a Spring bean of the Kafka Streams layer: Kafka flow, and gps.streams.enabled not false. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnlyWithKafkaStreams.KafkaStreamsEnabledCondition.class)
public @interface OnlyWithKafkaStreams {

    class KafkaStreamsEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return ProcessingFlowNames.KAFKA.equals(environment.getProperty(ProcessingFlowNames.PROPERTY, ProcessingFlowNames.KAFKA))
                    && environment.getProperty(StreamsNormalization.ENABLED_PROPERTY, Boolean.class, true);
        }
    }
}
