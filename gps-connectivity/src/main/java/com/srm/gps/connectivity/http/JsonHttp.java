package com.srm.gps.connectivity.http;

import com.srm.gps.configuration.json.GpsMessageJson;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON over HTTP to the programs on the HP home server (Valhalla, the ingestion API): one call, its answer and how
 * long it took. Short timeouts: the server sits behind a Wi-Fi hotspot and may be asleep.
 */
public final class JsonHttp {

    /** @param millis round trip, request sent to answer read */
    public record Answer(Map<String, Object> body, long millis) {
    }

    private final HttpClient http;
    private final Duration timeout;

    public JsonHttp(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Checks an http(s)://host:port address and drops a trailing slash.
     *
     * @throws IllegalArgumentException when it is not one
     */
    public static String baseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("The address is missing");
        }
        String trimmed = url.trim().replaceAll("/+$", "");
        try {
            URI uri = new URI(trimmed);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("Write the address as http://host:port, not " + url);
            }
        } catch (URISyntaxException problem) {
            throw new IllegalArgumentException("Not an address: " + url);
        }
        return trimmed;
    }

    public Answer get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    public Answer post(String url, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GpsMessageJson.toJson(body))));
    }

    @SuppressWarnings("unchecked")
    private Answer send(HttpRequest.Builder request) throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request.timeout(timeout).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }
        return new Answer(GpsMessageJson.fromJson(response.body(), LinkedHashMap.class), millis);
    }

    private static String shorten(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
