package com.srm.gps.application.connections;

import com.srm.gps.application.connections.ConnectionViews.IngestionApiCheck;
import com.srm.gps.application.connections.ConnectionViews.IngestionApiView;
import com.srm.gps.application.connections.ConnectionViews.Overview;
import com.srm.gps.application.connections.ConnectionViews.RedisCheck;
import com.srm.gps.application.connections.ConnectionViews.RedisSwitch;
import com.srm.gps.application.connections.ConnectionViews.RedisView;
import com.srm.gps.application.connections.ConnectionViews.ValhallaCheck;
import com.srm.gps.application.connections.ConnectionViews.ValhallaView;
import com.srm.gps.application.streaming.SpeedRuleStore;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.connectivity.http.JsonHttp;
import com.srm.gps.connectivity.ingestionapi.IngestionApiClient;
import com.srm.gps.connectivity.redis.RedisTarget;
import com.srm.gps.connectivity.redis.SwitchableRedisConnectionFactory;
import com.srm.gps.connectivity.valhalla.ValhallaClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The outside connections, checked and changed from the console (Developer -> Connections):
 *   Redis          this laptop or the HP home server, switched while the service runs
 *   Valhalla       the routing engine's address (route deviation will call it)
 *   ingestion API  the HP's FastAPI: shown and checked only, never changed here
 */
@Service
public class ConnectionsService {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionsService.class);

    /** A short drive in central Kolkata, the same as the HP's check_server.py. */
    private static final List<ValhallaClient.Location> SAMPLE_ROUTE =
            List.of(new ValhallaClient.Location(22.5726, 88.3639), new ValhallaClient.Location(22.5448, 88.3426));
    /** Keys the HP ingestion API keeps in its Redis database: finding one means that database is taken. */
    private static final List<String> INGESTION_API_KEYS = List.of("gps:points", "devices");
    private static final long SMALL_REDIS_BYTES = 512L * 1024 * 1024;
    private static final long SLOW_REDIS_MILLIS = 20;
    private static final long CHECK_SECONDS = 8;

    private final SwitchableRedisConnectionFactory redis;
    private final RedisTarget localRedis;
    private final ConnectionSettingStore settings;
    private final ConnectionsProperties properties;
    private final ValhallaClient valhalla;
    private final IngestionApiClient ingestionApi;
    private final SpeedRuleStore speedRules;
    private final ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor();
    private volatile String valhallaUrl;

    public ConnectionsService(SwitchableRedisConnectionFactory redis, RedisTarget localRedisTarget, ConnectionSettingStore settings,
                              ConnectionsProperties properties, ValhallaClient valhalla, IngestionApiClient ingestionApi,
                              SpeedRuleStore speedRules) {
        this.redis = redis;
        this.localRedis = localRedisTarget;
        this.settings = settings;
        this.properties = properties;
        this.valhalla = valhalla;
        this.ingestionApi = ingestionApi;
        this.speedRules = speedRules;
        this.valhallaUrl = settings.find(ConnectionSettingStore.VALHALLA).map(ConnectionSettingStore.Saved::url)
                .orElse(properties.valhallaUrlOrDefault());
    }

    /** The Valhalla address in use: what the console saved, otherwise gps.connections.valhalla-url. */
    public String valhallaUrl() {
        return valhallaUrl;
    }

    /** Every connection with a check made now (the three in parallel, a few seconds at most). */
    public Overview overview() {
        CompletableFuture<RedisView> redisView = inBackground(this::redisView, () -> redisView(RedisCheck.failed(redis.target().url(), noAnswer())));
        CompletableFuture<ValhallaView> valhallaView = inBackground(() -> valhallaView(valhallaStatus(valhallaUrl)),
                () -> valhallaView(ValhallaCheck.failed(valhallaUrl, noAnswer())));
        String ingestionUrl = properties.ingestionApiUrlOrDefault();
        CompletableFuture<IngestionApiView> ingestionView = inBackground(() -> new IngestionApiView(ingestionUrl, testIngestionApi()),
                () -> new IngestionApiView(ingestionUrl, IngestionApiCheck.failed(ingestionUrl, noAnswer())));
        return new Overview(redisView.join(), valhallaView.join(), ingestionView.join(), Instant.now());
    }

    // ---- Redis ----

    /** Checks a Redis without using it: ping, version, memory, eviction policy and what its database holds. */
    public RedisCheck testRedis(String url) {
        RedisTarget target = RedisTarget.parse(url);
        if (target.equals(redis.target())) {
            return check(target, redis);
        }
        LettuceConnectionFactory opened = redis.open(target);
        try {
            return check(target, opened);
        } finally {
            opened.destroy();
        }
    }

    /**
     * Moves the whole service to another Redis, at once and without a restart. Refused when that Redis does not answer
     * or its database belongs to the HP ingestion API. The configuration (speed rules, gps:config:*) is copied across
     * first, without overwriting what is there; vehicle state starts again from the next readings.
     */
    public synchronized RedisSwitch switchRedis(String url, boolean copyConfiguration) {
        RedisTarget target = RedisTarget.parse(url);
        RedisTarget current = redis.target();
        if (target.equals(current)) {
            throw new IllegalArgumentException("The service already uses " + target);
        }
        LettuceConnectionFactory opened = redis.open(target);
        boolean handedOver = false;
        try {
            RedisCheck check = check(target, opened);
            if (!check.reachable()) {
                throw new IllegalStateException("Not switched: " + target + " does not answer. " + check.problem());
            }
            if (!check.otherProgramsKeys().isEmpty()) {
                throw new IllegalStateException("Not switched: database " + target.database() + " holds the HP ingestion API's data ("
                        + String.join(", ", check.otherProgramsKeys()) + "). Choose another database number, for example /1.");
            }
            List<String> notes = new ArrayList<>();
            int copied = 0;
            if (copyConfiguration) {
                try {
                    copied = copyConfiguration(opened);
                } catch (RuntimeException problem) {
                    notes.add("The configuration was not copied (" + describe(problem) + "): the default speed rule is created there.");
                }
            }
            if (target.equals(localRedis)) {
                settings.remove(ConnectionSettingStore.REDIS);
            } else {
                settings.save(ConnectionSettingStore.REDIS, target.url());
            }
            redis.switchTo(target, opened);
            handedOver = true;
            speedRules.createDefaultRuleIfMissing();
            LOG.info("Redis is now {} (was {}); {} configuration keys copied", target, current, copied);
            return new RedisSwitch(current.url(), target.url(), copied, notes, redisView());
        } finally {
            if (!handedOver) {
                opened.destroy();
            }
        }
    }

    private RedisView redisView() {
        return redisView(check(redis.target(), redis));
    }

    private RedisView redisView(RedisCheck status) {
        RedisTarget target = redis.target();
        String homeServer = properties.homeServerRedisUrlOrDefault();
        String choice = target.equals(localRedis) ? "local" : target.url().equals(RedisTarget.parse(homeServer).url()) ? "home-server" : "custom";
        boolean saved = settings.find(ConnectionSettingStore.REDIS).isPresent();
        return new RedisView(target.url(), choice, saved, redis.since(), localRedis.url(), homeServer, status);
    }

    private RedisCheck check(RedisTarget target, RedisConnectionFactory factory) {
        try (RedisConnection connection = factory.getConnection()) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                connection.ping();
                best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
            }
            Properties server = connection.serverCommands().info("server");
            Properties memory = connection.serverCommands().info("memory");
            Long keys = connection.serverCommands().dbSize();
            List<String> otherPrograms = INGESTION_API_KEYS.stream()
                    .filter(key -> Boolean.TRUE.equals(connection.keyCommands().exists(bytes(key)))).toList();
            boolean hasConfiguration = Boolean.TRUE.equals(connection.keyCommands().exists(bytes(RedisKeyNames.SPEED_RULE_SCOPES)));

            String policy = memory == null ? null : memory.getProperty("maxmemory_policy");
            Long used = number(memory, "used_memory");
            Long max = number(memory, "maxmemory");
            List<String> warnings = new ArrayList<>();
            if (policy != null && !"noeviction".equals(policy)) {
                warnings.add("When it is full, this Redis deletes keys on its own (maxmemory-policy " + policy
                        + "): vehicle state and speed rules can disappear. This laptop's Redis never does (noeviction).");
            }
            if (max != null && max > 0 && max < SMALL_REDIS_BYTES) {
                warnings.add("Only " + megabytes(max) + " MB in all (" + megabytes(used) + " MB used now), shared with every program on that server.");
            }
            if (!otherPrograms.isEmpty()) {
                warnings.add("Database " + target.database() + " holds the HP ingestion API's data (" + String.join(", ", otherPrograms)
                        + "). Use another database number, for example /1.");
            }
            if (best > SLOW_REDIS_MILLIS) {
                warnings.add("Each call takes about " + best + " ms across the network, and the consumers make several per reading.");
            }
            return new RedisCheck(target.url(), true, best, server == null ? null : server.getProperty("redis_version"), used, max,
                    policy, keys, hasConfiguration, otherPrograms, warnings, null);
        } catch (RuntimeException problem) {
            return RedisCheck.failed(target.url(), describe(problem));
        }
    }

    /** Copies gps:config:* from the Redis in use: missing keys whole, sets merged (the list of speed-rule scopes). */
    private int copyConfiguration(RedisConnectionFactory to) {
        int copied = 0;
        try (RedisConnection source = redis.getConnection(); RedisConnection target = to.getConnection();
             Cursor<byte[]> keys = source.keyCommands().scan(ScanOptions.scanOptions().match(RedisKeyNames.CONFIG_PATTERN).count(500).build())) {
            while (keys.hasNext()) {
                byte[] key = keys.next();
                if (source.keyCommands().type(key) == DataType.SET) {
                    Set<byte[]> members = source.setCommands().sMembers(key);
                    if (members != null && !members.isEmpty()) {
                        target.setCommands().sAdd(key, members.toArray(byte[][]::new));
                        copied++;
                    }
                } else if (!Boolean.TRUE.equals(target.keyCommands().exists(key))) {
                    byte[] dump = source.keyCommands().dump(key);
                    if (dump != null) {
                        target.keyCommands().restore(key, 0, dump);
                        copied++;
                    }
                }
            }
        }
        return copied;
    }

    // ---- Valhalla ----

    /** /status, then a short route through Kolkata: proves the service reaches the engine and the map answers. */
    public ValhallaCheck testValhalla(String url) {
        String base = JsonHttp.baseUrl(url);
        ValhallaCheck status = valhallaStatus(base);
        if (!status.reachable()) {
            return status;
        }
        try {
            ValhallaClient.Route route = valhalla.route(base, SAMPLE_ROUTE, "auto");
            return new ValhallaCheck(base, true, status.millis(), status.version(), status.tilesBuilt(), status.actions(),
                    route.lengthKm(), Math.round(route.timeSeconds() / 6.0) / 10.0, route.millis(), null);
        } catch (Exception problem) {
            return new ValhallaCheck(base, true, status.millis(), status.version(), status.tilesBuilt(), status.actions(),
                    null, null, null, "It answers, but the test route failed: " + describe(problem));
        }
    }

    /** Saves the Valhalla address; the default address removes the saved one. Used from the next call on. */
    public ValhallaView saveValhalla(String url) {
        String base = JsonHttp.baseUrl(url);
        if (base.equals(JsonHttp.baseUrl(properties.valhallaUrlOrDefault()))) {
            settings.remove(ConnectionSettingStore.VALHALLA);
        } else {
            settings.save(ConnectionSettingStore.VALHALLA, base);
        }
        valhallaUrl = base;
        LOG.info("Valhalla is now {}", base);
        return valhallaView(valhallaStatus(base));
    }

    private ValhallaCheck valhallaStatus(String url) {
        try {
            ValhallaClient.Status status = valhalla.status(url);
            return new ValhallaCheck(url, true, status.millis(), status.version(), status.tilesBuilt(), status.actions(),
                    null, null, null, null);
        } catch (Exception problem) {
            return ValhallaCheck.failed(url, describe(problem));
        }
    }

    private ValhallaView valhallaView(ValhallaCheck status) {
        Optional<ConnectionSettingStore.Saved> saved = settings.find(ConnectionSettingStore.VALHALLA);
        return new ValhallaView(valhallaUrl, properties.valhallaUrlOrDefault(), saved.isPresent(),
                saved.map(ConnectionSettingStore.Saved::updatedAt).orElse(null), status);
    }

    // ---- ingestion API (read-only) ----

    public IngestionApiCheck testIngestionApi() {
        String url = properties.ingestionApiUrlOrDefault();
        try {
            IngestionApiClient.Health health = ingestionApi.health(url);
            return new IngestionApiCheck(url, true, health.millis(), health.answer(), null);
        } catch (Exception problem) {
            return IngestionApiCheck.failed(url, describe(problem));
        }
    }

    // ---- helpers ----

    private <T> CompletableFuture<T> inBackground(Supplier<T> work, Supplier<T> whenTooSlow) {
        return CompletableFuture.supplyAsync(work, checks).completeOnTimeout(null, CHECK_SECONDS, TimeUnit.SECONDS)
                .thenApply(result -> result != null ? result : whenTooSlow.get());
    }

    private static String noAnswer() {
        return "No answer within " + CHECK_SECONDS + " s";
    }

    /** The cause in words a person can act on. */
    static String describe(Throwable problem) {
        Throwable root = problem;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        if (root instanceof ConnectException) {
            return "Nothing answers at that address (" + message + "): wrong host or port, the server is off, or this laptop is not on its network.";
        }
        if (root instanceof HttpTimeoutException) {
            return "No answer in time (" + message + "): the server may be asleep or overloaded.";
        }
        String lower = message.toLowerCase();
        if (lower.contains("no bytes") || lower.contains("connection reset") || lower.contains("closed")) {
            return "The port accepted the connection but closed it without answering: the program behind it is down"
                    + " (on the HP: WSL, Docker or the container). " + message;
        }
        return message;
    }

    private static byte[] bytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static Long number(Properties properties, String name) {
        try {
            return properties == null || properties.getProperty(name) == null ? null : Long.parseLong(properties.getProperty(name).trim());
        } catch (NumberFormatException problem) {
            return null;
        }
    }

    private static long megabytes(Long bytes) {
        return bytes == null ? 0 : Math.round(bytes / 1024.0 / 1024.0);
    }

    @PreDestroy
    void shutDown() {
        checks.shutdownNow();
    }
}
