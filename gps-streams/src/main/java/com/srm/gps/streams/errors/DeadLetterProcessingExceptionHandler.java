package com.srm.gps.streams.errors;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.ErrorHandlerContext;
import org.apache.kafka.streams.errors.ProcessingExceptionHandler;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * What a Kafka Streams app does when a processor throws (processing.exception.handler, KIP-1033):
 *
 *   {@link StateUnavailableException} somewhere in the cause -> FAIL: the stream thread is replaced and the record is
 *                                                                 processed again from the last commit (nothing skipped)
 *   anything else                                             -> the SOURCE record (raw bytes, as read) goes to the
 *                                                                 dead-letter topic and processing resumes
 *
 * The dead letter is written by the task's own producer, so under exactly-once it is part of the same transaction as
 * the offset that moves past the record: either the record is parked AND skipped, or neither. Headers follow Kafka
 * Streams' own dead-letter format (__streams.errors.*), so both of its writers look the same to the archive.
 */
public class DeadLetterProcessingExceptionHandler implements ProcessingExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterProcessingExceptionHandler.class);
    private static final int STACKTRACE_LIMIT = 8_000;

    private String deadLetterTopic;

    @Override
    public void configure(Map<String, ?> configs) {
        Object topic = configs.get(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG);
        deadLetterTopic = topic == null ? null : topic.toString();
    }

    @Override
    public Response handleError(ErrorHandlerContext context, Record<?, ?> record, Exception exception) {
        if (StateUnavailableException.isCause(exception)) {
            LOG.warn("Processor {} (task {}, {}-{}@{}) needs something that is not reachable; retrying from the last commit: {}",
                    context.processorNodeId(), context.taskId(), context.topic(), context.partition(), context.offset(), exception.toString());
            return Response.fail();
        }
        if (deadLetterTopic == null) {
            LOG.error("Processor {} failed on {}-{}@{} and no dead-letter topic is configured: stopping instead of skipping",
                    context.processorNodeId(), context.topic(), context.partition(), context.offset(), exception);
            return Response.fail();
        }
        LOG.error("Processor {} failed on {}-{}@{}: parked in {} and continuing",
                context.processorNodeId(), context.topic(), context.partition(), context.offset(), deadLetterTopic, exception);
        return Response.resume(List.of(deadLetter(deadLetterTopic, context, exception)));
    }

    /** The source record as it was read, with where it came from and why it failed. */
    static ProducerRecord<byte[], byte[]> deadLetter(String topic, ErrorHandlerContext context, Exception exception) {
        Headers headers = new RecordHeaders();
        headers.add("__streams.errors.exception", utf8(exception.getClass().getName()));
        headers.add("__streams.errors.message", utf8(String.valueOf(exception.getMessage())));
        headers.add("__streams.errors.stacktrace", utf8(stacktrace(exception)));
        headers.add("__streams.errors.topic", utf8(context.topic()));
        headers.add("__streams.errors.partition", utf8(String.valueOf(context.partition())));
        headers.add("__streams.errors.offset", utf8(String.valueOf(context.offset())));
        headers.add("__streams.errors.processor", utf8(context.processorNodeId()));
        return new ProducerRecord<>(topic, null, context.timestamp(), context.sourceRawKey(), context.sourceRawValue(), headers);
    }

    private static byte[] utf8(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }

    private static String stacktrace(Exception exception) {
        StringWriter text = new StringWriter();
        exception.printStackTrace(new PrintWriter(text));
        String trace = text.toString();
        return trace.length() > STACKTRACE_LIMIT ? trace.substring(0, STACKTRACE_LIMIT) : trace;
    }
}
