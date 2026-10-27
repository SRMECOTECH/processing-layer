package com.srm.gps.route.engine;

import com.srm.gps.configuration.json.GpsMessageJson;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to the routing engine (GraphHopper) over its HTTP API - the only class that knows its address.
 *
 *   GET  /info    which map, which profiles, which version
 *   POST /route   one route (or alternatives) for a profile and an algorithm
 *
 * Later for deviation: POST /match (snap a GPS trace to the roads) and /isochrone (where can a truck be in 30 min).
 */
@Component
public class GraphHopperClient {

    private final RoutingEngineProperties settings;
    private final HttpClient http;

    public GraphHopperClient(RoutingEngineProperties settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.connectTimeoutMillis())).build();
    }

    public String url() {
        return settings.url();
    }

    public String defaultProfile() {
        return settings.defaultProfile();
    }

    /** The engine's /info: version, bounding box of the map, profiles, import and data dates. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> info() {
        HttpRequest request = request("/info").GET().build();
        return GpsMessageJson.fromJson(send(request), LinkedHashMap.class);
    }

    public RouteResult route(RouteQuery query) {
        String body = GpsMessageJson.toJson(GraphHopperMessages.routeRequest(query));
        HttpRequest request = request("/route")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return GraphHopperMessages.readRoute(query, send(request));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(settings.url() + path))
                .timeout(Duration.ofMillis(settings.requestTimeoutMillis()))
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new RoutingEngineException.Unavailable("Routing engine at " + settings.url() + " did not answer ("
                    + unreachable.getClass().getSimpleName() + "). It may still be importing the map: "
                    + "docker compose logs graphhopper");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RoutingEngineException.Unavailable("Interrupted while waiting for the routing engine");
        }
        if (response.statusCode() == 200) {
            return response.body();
        }
        String message = GraphHopperMessages.errorMessage(response.body());
        if (response.statusCode() == 503) {
            throw new RoutingEngineException.Unavailable("Routing engine is not ready: " + message);
        }
        throw new RoutingEngineException("Routing engine HTTP " + response.statusCode() + ": " + message);
    }
}
