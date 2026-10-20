package com.srm.gps.application.streaming;

import com.srm.gps.application.events.VehicleEventRecorder;
import com.srm.gps.configuration.RedisKeyNames;
import com.srm.gps.configuration.json.GpsMessageJson;
import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.VehicleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The "agents" that explain a stoppage (engine on, standing): is it traffic, is it weather, or neither?
 *
 *   Traffic agent - first the fleet itself: other vehicles within trafficRadius of the stop (Redis GEOSEARCH on
 *                   gps:live:positions) that reported recently and have the engine running (parked ones are ignored:
 *                   a yard full of parked trucks is not a jam); if most of them are standing or crawling too, the
 *                   road is congested. With a TomTom key it also asks TomTom's traffic flow (current vs free-flow speed).
 *   Weather agent - Open-Meteo current weather (free, no key) for the 0.1-degree cell of the stop, cached in Redis
 *                   for 10 minutes; thunderstorm, heavy rain or snow, fog, strong wind or real precipitation = severe.
 *
 * Verdict: JUSTIFIED_TRAFFIC, JUSTIFIED_WEATHER, JUSTIFIED_TRAFFIC_AND_WEATHER, or UNEXPLAINED (raised to HIGH).
 * Runs on its own small thread pool so a slow API never holds up the Kafka consumer; when the pool is full,
 * the oldest pending check is dropped (that stoppage simply keeps verdict PENDING).
 */
@Component
public class StoppageContextAgents {

    private static final Logger LOG = LoggerFactory.getLogger(StoppageContextAgents.class);
    private static final Duration WEATHER_CACHE = Duration.ofMinutes(10);
    private static final double CRAWLING_KMPH = 10;
    private static final int MIN_NEIGHBOURS = 3;
    private static final double CONGESTED_SHARE = 0.6;
    private static final Duration NEIGHBOUR_FRESHNESS = Duration.ofMinutes(10);
    private static final Set<Integer> SEVERE_WEATHER_CODES = Set.of(45, 48, 63, 65, 66, 67, 71, 73, 75, 77, 81, 82, 85, 86, 95, 96, 99);

    private final StringRedisTemplate redis;
    private final VehicleEventRecorder recorder;
    private final StreamProperties.Enrichment settings;
    private final HttpClient http;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200), runnable -> new Thread(runnable, "stoppage-context-agent"),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public StoppageContextAgents(StringRedisTemplate redis, VehicleEventRecorder recorder, StreamProperties properties) {
        this.redis = redis;
        this.recorder = recorder;
        this.settings = properties.enrichmentOrDefault();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.timeout())).build();
    }

    /** Investigates a stoppage in the background and adds the verdict to its event. */
    public void investigate(VehicleEvent stoppage) {
        pool.execute(() -> {
            try {
                Map<String, Object> traffic = traffic(stoppage);
                Map<String, Object> weather = settings.weatherOn() ? weather(stoppage.latitude(), stoppage.longitude()) : Map.of("checked", false);
                boolean congested = Boolean.TRUE.equals(traffic.get("congested"));
                boolean severe = Boolean.TRUE.equals(weather.get("severe"));
                String verdict = congested && severe ? "JUSTIFIED_TRAFFIC_AND_WEATHER"
                        : congested ? "JUSTIFIED_TRAFFIC" : severe ? "JUSTIFIED_WEATHER" : "UNEXPLAINED";

                Map<String, Object> context = new LinkedHashMap<>();
                context.put("contextVerdict", verdict);
                context.put("traffic", traffic);
                context.put("weather", weather);
                context.put("contextCheckedAt", Instant.now().toString());
                recorder.mergeDetails(stoppage.eventKey(), context, verdict.startsWith("JUSTIFIED") ? EventSeverity.LOW : EventSeverity.HIGH);
                LOG.info("Stoppage of {} at {}: {}", stoppage.vehicleRegistrationNo() != null ? stoppage.vehicleRegistrationNo()
                        : stoppage.imei(), stoppage.startedAt(), verdict);
            } catch (RuntimeException problem) {
                LOG.warn("Could not check the context of stoppage {}: {}", stoppage.eventKey(), problem.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- traffic

    private Map<String, Object> traffic(VehicleEvent stoppage) {
        Map<String, Object> fleet = fleetTraffic(stoppage);
        if (!settings.tomtomOn()) {
            return fleet;
        }
        Map<String, Object> combined = new LinkedHashMap<>(fleet);
        Map<String, Object> tomtom = tomtomTraffic(stoppage.latitude(), stoppage.longitude());
        combined.put("tomtom", tomtom);
        combined.put("congested", Boolean.TRUE.equals(fleet.get("congested")) || Boolean.TRUE.equals(tomtom.get("congested")));
        return combined;
    }

    private Map<String, Object> fleetTraffic(VehicleEvent stoppage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "FLEET");
        result.put("radiusMeters", settings.trafficRadius());
        GeoResults<RedisGeoCommands.GeoLocation<String>> nearby = redis.opsForGeo().search(RedisKeyNames.LIVE_POSITIONS_GEO,
                GeoReference.fromCoordinate(stoppage.longitude(), stoppage.latitude()),
                new Distance(settings.trafficRadius(), RedisGeoCommands.DistanceUnit.METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(26));
        List<Integer> speeds = new ArrayList<>();
        int parked = 0;
        if (nearby != null) {
            for (var hit : nearby.getContent()) {
                String imei = hit.getContent().getName();
                if (imei.equals(stoppage.imei())) {
                    continue;
                }
                // A yard full of parked trucks is not a traffic jam: only vehicles with the engine running count.
                Object neighbourIgnition = redis.opsForHash().get(RedisKeyNames.ignitionState(imei), "ignitionOn");
                if (neighbourIgnition != null && "false".equals(neighbourIgnition.toString())) {
                    parked++;
                    continue;
                }
                Map<Object, Object> live = redis.opsForHash().entries(RedisKeyNames.liveVehicle(imei));
                Object speed = live.get("speedKmph");
                Object recordedAt = live.get("recordedAt");
                if (speed == null || recordedAt == null) {
                    continue;
                }
                Instant seen = Instant.parse(recordedAt.toString());
                if (Duration.between(seen, stoppage.startedAt()).abs().compareTo(NEIGHBOUR_FRESHNESS) <= 0) {
                    speeds.add(Integer.parseInt(speed.toString()));
                }
            }
        }
        long slow = speeds.stream().filter(s -> s <= CRAWLING_KMPH).count();
        result.put("parkedNearbyIgnored", parked);
        result.put("nearbyVehicles", speeds.size());
        result.put("slowVehicles", slow);
        result.put("medianSpeedKmph", median(speeds));
        result.put("congested", speeds.size() >= MIN_NEIGHBOURS && slow >= CONGESTED_SHARE * speeds.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tomtomTraffic(double latitude, double longitude) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "TOMTOM");
        try {
            String url = String.format(Locale.ROOT,
                    "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/10/json?point=%.5f,%.5f&unit=KMPH&key=%s",
                    latitude, longitude, settings.tomtomApiKey());
            Map<String, Object> body = getJson(url);
            Map<String, Object> flow = (Map<String, Object>) body.get("flowSegmentData");
            double current = ((Number) flow.get("currentSpeed")).doubleValue();
            double free = ((Number) flow.get("freeFlowSpeed")).doubleValue();
            result.put("currentSpeedKmph", current);
            result.put("freeFlowSpeedKmph", free);
            result.put("congested", free > 0 && current / free < 0.5);
        } catch (Exception problem) {
            result.put("error", problem.getMessage());
        }
        return result;
    }

    // ---------------------------------------------------------------- weather

    @SuppressWarnings("unchecked")
    private Map<String, Object> weather(double latitude, double longitude) {
        double cellLat = Math.round(latitude * 10) / 10.0;
        double cellLon = Math.round(longitude * 10) / 10.0;
        String cacheKey = RedisKeyNames.weather(cellLat + ":" + cellLon);
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            Map<String, Object> fromCache = new LinkedHashMap<>(GpsMessageJson.fromJson(cached, LinkedHashMap.class));
            fromCache.put("cached", true);
            return fromCache;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "OPEN_METEO");
        result.put("cell", cellLat + "," + cellLon);
        try {
            String url = String.format(Locale.ROOT,
                    "%s?latitude=%.1f&longitude=%.1f&current=temperature_2m,precipitation,rain,weather_code,wind_speed_10m",
                    settings.weatherEndpoint(), cellLat, cellLon);
            Map<String, Object> current = (Map<String, Object>) getJson(url).get("current");
            int code = ((Number) current.get("weather_code")).intValue();
            double precipitation = number(current.get("precipitation"));
            double wind = number(current.get("wind_speed_10m"));
            result.put("weatherCode", code);
            result.put("description", describe(code));
            result.put("temperatureC", number(current.get("temperature_2m")));
            result.put("precipitationMm", precipitation);
            result.put("windKmph", wind);
            boolean severe = SEVERE_WEATHER_CODES.contains(code) || precipitation >= 2.0 || wind >= 50;
            result.put("severe", severe);
            redis.opsForValue().set(cacheKey, GpsMessageJson.toJson(result), WEATHER_CACHE);
        } catch (Exception problem) {
            result.put("error", problem.getMessage());
            result.put("severe", false);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(settings.timeout()))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return GpsMessageJson.fromJson(response.body(), LinkedHashMap.class);
    }

    /** WMO weather interpretation codes, as Open-Meteo returns them. */
    static String describe(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1, 2, 3 -> "Partly cloudy";
            case 45, 48 -> "Fog";
            case 51, 53, 55, 56, 57 -> "Drizzle";
            case 61, 63, 66 -> "Rain";
            case 65, 67 -> "Heavy rain";
            case 71, 73, 75, 77 -> "Snow";
            case 80, 81 -> "Rain showers";
            case 82 -> "Violent rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Code " + code;
        };
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static Integer median(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    @PreDestroy
    void shutDown() {
        pool.shutdownNow();
    }
}
