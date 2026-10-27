package com.srm.gps.streams.runtime;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.streams.CloseOptions;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.StreamsNotStartedException;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One Kafka Streams application inside the service: builds its topology, starts it, closes it, and resets it.
 * Plain Java - the service decides when (Spring lifecycle, demo reset).
 *
 * Failure handling, as the architecture guide describes it, made explicit:
 *   - a stream thread that throws is REPLACED (the task restarts from its last commit, state restored from the
 *     changelog if needed) instead of stopping the app;
 *   - state restoration is tracked per store and partition ({@link RestoreTracker});
 *   - on close it can LEAVE the consumer group, so a successor (another instance, or the listener taking the job
 *     back) gets the partitions at once instead of after the session timeout.
 */
public final class StreamsApp {

    private static final Logger LOG = LoggerFactory.getLogger(StreamsApp.class);
    private static final int STATE_CHANGES_KEPT = 20;

    /** A state transition of the app (CREATED -> REBALANCING -> RUNNING ...). */
    public record StateChange(Instant at, String from, String to) {
    }

    private final String role;
    private final Properties properties;
    private final Supplier<Topology> topologySupplier;
    private final boolean leaveGroupOnClose;
    private final RestoreTracker restores = new RestoreTracker();
    private final Deque<StateChange> stateChanges = new ArrayDeque<>();
    private final AtomicInteger replacedThreads = new AtomicInteger();

    private volatile KafkaStreams streams;
    private volatile Topology topology;
    private volatile Instant startedAt;
    private volatile String lastFailure;

    public StreamsApp(String role, Properties properties, Supplier<Topology> topologySupplier, boolean leaveGroupOnClose) {
        this.role = role;
        this.properties = properties;
        this.topologySupplier = topologySupplier;
        this.leaveGroupOnClose = leaveGroupOnClose;
    }

    public synchronized void start() {
        start(false);
    }

    private void start(boolean wipeLocalState) {
        if (streams != null) {
            return;
        }
        topology = topologySupplier.get();
        KafkaStreams created = new KafkaStreams(topology, properties);
        if (wipeLocalState) {
            created.cleanUp();   // allowed before start(): deletes this app's local state directory
        }
        created.setStateListener((now, before) -> {
            synchronized (stateChanges) {
                stateChanges.addFirst(new StateChange(Instant.now(), before.name(), now.name()));
                while (stateChanges.size() > STATE_CHANGES_KEPT) {
                    stateChanges.removeLast();
                }
            }
            LOG.info("Kafka Streams {} ({}): {} -> {}", role, applicationId(), before, now);
        });
        created.setUncaughtExceptionHandler(failure -> {
            replacedThreads.incrementAndGet();
            lastFailure = Instant.now() + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            LOG.error("Kafka Streams {}: a stream thread failed and is replaced; its tasks resume from the last commit", role, failure);
            return StreamThreadExceptionResponse.REPLACE_THREAD;
        });
        created.setGlobalStateRestoreListener(restores);
        created.start();
        streams = created;
        startedAt = Instant.now();
    }

    public synchronized void stop(Duration timeout) {
        close(timeout, leaveGroupOnClose);
    }

    /**
     * Starts the app from nothing, as the Kafka Streams application reset tool would: close it (leaving the group),
     * delete its consumer group (offsets and the stream time stored with them) and its internal changelog and
     * repartition topics, wipe the local state directory, then start again - stores are rebuilt from the inputs.
     * Works on a running app and on a stopped one.
     *
     * @return what could not be done, empty when everything worked
     */
    public synchronized List<String> reset(Admin admin, Duration timeout) {
        List<String> notes = new ArrayList<>();
        close(timeout, true);
        String applicationId = applicationId();
        try {
            admin.deleteConsumerGroups(List.of(applicationId)).all().get(10, TimeUnit.SECONDS);
        } catch (Exception problem) {
            if (!(rootCause(problem) instanceof GroupIdNotFoundException)) {
                notes.add(role + ": consumer group " + applicationId + " not deleted: " + rootCause(problem).getMessage());
            }
        }
        try {
            Set<String> internal = admin.listTopics().names().get(10, TimeUnit.SECONDS).stream()
                    .filter(topic -> topic.startsWith(applicationId + "-"))
                    .filter(topic -> topic.endsWith("-changelog") || topic.endsWith("-repartition"))
                    .collect(Collectors.toSet());
            if (!internal.isEmpty()) {
                admin.deleteTopics(internal).all().get(30, TimeUnit.SECONDS);
                waitUntilGone(admin, internal, Duration.ofSeconds(15));
            }
        } catch (Exception problem) {
            notes.add(role + ": internal topics not deleted: " + rootCause(problem).getMessage());
        }
        restores.clear();
        replacedThreads.set(0);
        lastFailure = null;
        try {
            start(true);
        } catch (RuntimeException problem) {
            notes.add(role + ": not restarted: " + problem.getMessage());
        }
        return notes;
    }

    private void close(Duration timeout, boolean leaveGroup) {
        KafkaStreams closing = streams;
        if (closing == null) {
            return;
        }
        boolean closed = closing.close(CloseOptions.timeout(timeout).withGroupMembershipOperation(
                leaveGroup ? CloseOptions.GroupMembershipOperation.LEAVE_GROUP : CloseOptions.GroupMembershipOperation.REMAIN_IN_GROUP));
        if (!closed) {
            LOG.warn("Kafka Streams {} did not close within {}", role, timeout);
        }
        streams = null;
    }

    public String role() {
        return role;
    }

    public String applicationId() {
        return properties.getProperty(StreamsConfig.APPLICATION_ID_CONFIG);
    }

    public Properties properties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    public Optional<KafkaStreams> kafkaStreams() {
        return Optional.ofNullable(streams);
    }

    /** NOT_STARTED before the first start and after a stop. */
    public String state() {
        KafkaStreams current = streams;
        return current == null ? "NOT_STARTED" : current.state().name();
    }

    public Topology topology() {
        return topology != null ? topology : topologySupplier.get();
    }

    public RestoreTracker restores() {
        return restores;
    }

    public List<StateChange> stateChanges() {
        synchronized (stateChanges) {
            return List.copyOf(stateChanges);
        }
    }

    public int replacedThreads() {
        return replacedThreads.get();
    }

    public String lastFailure() {
        return lastFailure;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /**
     * A local store for Interactive Queries.
     *
     * @throws org.apache.kafka.streams.errors.InvalidStateStoreException while the app is not running, rebalancing,
     *                                                                    or the store is still being restored
     */
    public <T> T store(String storeName, QueryableStoreType<T> type) {
        KafkaStreams current = streams;
        if (current == null) {
            throw new StreamsNotStartedException("Kafka Streams " + role + " is not running");
        }
        return current.store(StoreQueryParameters.fromNameAndType(storeName, type));
    }

    private static void waitUntilGone(Admin admin, Set<String> topics, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Set<String> remaining = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            if (topics.stream().noneMatch(remaining::contains)) {
                return;
            }
            Thread.sleep(250);
        }
    }

    private static Throwable rootCause(Throwable problem) {
        Throwable cause = problem;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
