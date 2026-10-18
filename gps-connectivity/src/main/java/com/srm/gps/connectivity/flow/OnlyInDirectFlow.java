package com.srm.gps.connectivity.flow;

import com.srm.gps.configuration.ProcessingFlowNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a Spring bean that exists only when gps.processing-flow=direct (no Kafka). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(name = ProcessingFlowNames.PROPERTY, havingValue = ProcessingFlowNames.DIRECT)
public @interface OnlyInDirectFlow {
}
