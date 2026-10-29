package com.srm.gps.connectivity.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The service's one Redis connection factory, pointing at a Redis that can change while the service runs
 * (Developer -> Connections: this laptop or the HP home server).
 *
 * Every StringRedisTemplate call asks this factory for a connection, so the call after switchTo() already goes to the
 * new Redis. The previous factory is closed 30 s later, once the calls in flight are done. Vehicle state is not moved:
 * the consumers build it again from the next readings, exactly as on a first start.
 *
 * Timeouts are short on purpose: a Redis across the Wi-Fi that stops answering (the HP asleep, the hotspot off) fails a
 * call within seconds instead of a minute; the consumers treat that as infrastructure and retry, Kafka keeps the data.
 */
public class SwitchableRedisConnectionFactory implements RedisConnectionFactory, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SwitchableRedisConnectionFactory.class);
    private static final long CLOSE_PREVIOUS_AFTER_SECONDS = 30;

    private record Active(RedisTarget target, LettuceConnectionFactory factory, Instant since) {
    }

    private final Duration commandTimeout;
    private final Duration connectTimeout;
    // one set of I/O threads for every factory this class opens, so a test or a switch does not start new ones
    private final ClientResources resources = DefaultClientResources.create();
    private volatile Active active;

    public SwitchableRedisConnectionFactory(RedisTarget initial, Duration commandTimeout, Duration connectTimeout) {
        this.commandTimeout = commandTimeout;
        this.connectTimeout = connectTimeout;
        this.active = new Active(initial, open(initial), Instant.now());
    }

    public RedisTarget target() {
        return active.target();
    }

    public Instant since() {
        return active.since();
    }

    /**
     * A factory of its own for one Redis, outside the service's traffic: to test a Redis before switching to it, then
     * to hand to {@link #switchTo}. Close it with destroy() if it is not used.
     */
    public LettuceConnectionFactory open(RedisTarget target) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(target.host(), target.port());
        standalone.setDatabase(target.database());
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientResources(resources)
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /**
     * From now on every new connection goes to {@code target}, through {@code opened} (from {@link #open}).
     * Returns the Redis used until now.
     */
    public synchronized RedisTarget switchTo(RedisTarget target, LettuceConnectionFactory opened) {
        Active previous = active;
        active = new Active(target, opened, Instant.now());
        CompletableFuture.delayedExecutor(CLOSE_PREVIOUS_AFTER_SECONDS, TimeUnit.SECONDS).execute(() -> close(previous));
        LOG.info("Redis switched from {} to {}", previous.target(), target);
        return previous.target();
    }

    @Override
    public RedisConnection getConnection() {
        return active.factory().getConnection();
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        return active.factory().getClusterConnection();
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        return active.factory().getSentinelConnection();
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return active.factory().getConvertPipelineAndTxResults();
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException problem) {
        return active.factory().translateExceptionIfPossible(problem);
    }

    @Override
    public void destroy() {
        close(active);
        resources.shutdown();
    }

    private static void close(Active retired) {
        try {
            retired.factory().destroy();
        } catch (RuntimeException problem) {
            LOG.warn("Could not close the connection to {}: {}", retired.target(), problem.getMessage());
        }
    }
}
