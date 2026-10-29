package com.srm.gps.connectivity.ingestionapi;

import com.srm.gps.connectivity.http.JsonHttp;

import java.io.IOException;
import java.util.Map;

/**
 * The HP home server's ingestion API (FastAPI, default http://192.168.137.1:8000, Swagger at /docs). Devices post
 * points to it (POST /ingest, /ingest/batch) and it keeps them in the HP's Redis (gps:points, last:*, track:*).
 * It is not part of this service's pipeline - our simulator writes to Kafka - so only its health is read here.
 */
public class IngestionApiClient {

    /** @param answer GET /health as the API returns it, e.g. {"redis": true, "valhalla": true} */
    public record Health(Map<String, Object> answer, long millis) {
    }

    private final JsonHttp http;

    public IngestionApiClient(JsonHttp http) {
        this.http = http;
    }

    public Health health(String baseUrl) throws IOException, InterruptedException {
        JsonHttp.Answer answer = http.get(JsonHttp.baseUrl(baseUrl) + "/health");
        return new Health(answer.body(), answer.millis());
    }
}
