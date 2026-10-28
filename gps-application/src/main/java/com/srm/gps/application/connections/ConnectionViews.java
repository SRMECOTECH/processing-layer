package com.srm.gps.application.connections;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What /api/connections/* answers and accepts. */
public final class ConnectionViews {

    private ConnectionViews() {
    }

    /** GET /api/connections: where each connection points and whether it answers right now. */
    public record Overview(RedisView redis, ValhallaView valhalla, IngestionApiView ingestionApi, Instant checkedAt) {
    }

    /**
     * @param choice        local (this laptop) | home-server (the HP) | custom
     * @param saved         chosen in the console (connection_setting) rather than the default
     * @param since         when the service started using it
     * @param status        the active Redis checked now
     */
    public record RedisView(String url, String choice, boolean saved, Instant since, String localUrl, String homeServerUrl,
                            RedisCheck status) {
    }

    public record ValhallaView(String url, String defaultUrl, boolean saved, Instant savedAt, ValhallaCheck status) {
    }

    /** Read-only: the HP's ingestion API is not part of this service's pipeline. */
    public record IngestionApiView(String url, IngestionApiCheck status) {
    }

    /**
     * One Redis checked: ping, version, memory, what is in the chosen database.
     *
     * @param millis            best of three pings
     * @param otherProgramsKeys keys of the HP ingestion API found in this database (it is then taken)
     * @param warnings          reasons to think twice before using it
     * @param problem           why it could not be checked (reachable = false)
     */
    public record RedisCheck(String url, boolean reachable, Long millis, String version, Long usedMemoryBytes,
                             Long maxMemoryBytes, String evictionPolicy, Long keys, boolean hasPlatformConfiguration,
                             List<String> otherProgramsKeys, List<String> warnings, String problem) {

        static RedisCheck failed(String url, String problem) {
            return new RedisCheck(url, false, null, null, null, null, null, null, false, List.of(), List.of(), problem);
        }
    }

    /** @param sampleRouteKm a short drive in central Kolkata, routed to prove the map answers (test only) */
    public record ValhallaCheck(String url, boolean reachable, Long millis, String version, Instant tilesBuilt,
                                List<String> actions, Double sampleRouteKm, Double sampleRouteMinutes, Long sampleRouteMillis,
                                String problem) {

        static ValhallaCheck failed(String url, String problem) {
            return new ValhallaCheck(url, false, null, null, null, List.of(), null, null, null, problem);
        }
    }

    /** @param health GET /health as the API answers it, e.g. {"redis": true, "valhalla": true} */
    public record IngestionApiCheck(String url, boolean reachable, Long millis, Map<String, Object> health, String problem) {

        static IngestionApiCheck failed(String url, String problem) {
            return new IngestionApiCheck(url, false, null, Map.of(), problem);
        }
    }

    /** PUT /api/connections/redis: use another Redis; copy the configuration (speed rules) across unless false. */
    public record RedisChange(String url, Boolean copyConfiguration) {
    }

    /** POST /api/connections/{redis|valhalla}/test, PUT /api/connections/valhalla. */
    public record UrlChange(String url) {
    }

    /** @param notes anything that did not go as planned (the new Redis' warnings are in redis.status) */
    public record RedisSwitch(String from, String to, int configurationKeysCopied, List<String> notes, RedisView redis) {
    }
}
