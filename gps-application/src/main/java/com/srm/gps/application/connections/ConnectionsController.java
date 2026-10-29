package com.srm.gps.application.connections;

import com.srm.gps.application.connections.ConnectionViews.IngestionApiCheck;
import com.srm.gps.application.connections.ConnectionViews.Overview;
import com.srm.gps.application.connections.ConnectionViews.RedisChange;
import com.srm.gps.application.connections.ConnectionViews.RedisCheck;
import com.srm.gps.application.connections.ConnectionViews.RedisSwitch;
import com.srm.gps.application.connections.ConnectionViews.UrlChange;
import com.srm.gps.application.connections.ConnectionViews.ValhallaCheck;
import com.srm.gps.application.connections.ConnectionViews.ValhallaView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The outside connections (Developer -> Connections in the console).
 *
 *   GET  /api/connections                      where Redis, Valhalla and the ingestion API are, each checked now
 *   POST /api/connections/redis/test           {"url": "redis://192.168.137.1:6379/1"}  check a Redis without using it
 *   PUT  /api/connections/redis                {"url": ..., "copyConfiguration": true}  use it, at once, no restart
 *   POST /api/connections/valhalla/test        {"url": "http://192.168.137.1:8002"}     status + a short test route
 *   PUT  /api/connections/valhalla             {"url": ...}                              save the routing engine's address
 *   POST /api/connections/ingestion-api/test   the HP ingestion API's /health (read-only, its address is not changed here)
 *
 * A bad address answers 400 and a refused switch 409, both with {"error": "..."}.
 */
@RestController
public class ConnectionsController {

    private final ConnectionsService connections;

    public ConnectionsController(ConnectionsService connections) {
        this.connections = connections;
    }

    @GetMapping("/api/connections")
    public Overview overview() {
        return connections.overview();
    }

    @PostMapping("/api/connections/redis/test")
    public RedisCheck testRedis(@RequestBody UrlChange body) {
        return connections.testRedis(body.url());
    }

    @PutMapping("/api/connections/redis")
    public RedisSwitch switchRedis(@RequestBody RedisChange body) {
        return connections.switchRedis(body.url(), body.copyConfiguration() == null || body.copyConfiguration());
    }

    @PostMapping("/api/connections/valhalla/test")
    public ValhallaCheck testValhalla(@RequestBody UrlChange body) {
        return connections.testValhalla(body.url());
    }

    @PutMapping("/api/connections/valhalla")
    public ValhallaView saveValhalla(@RequestBody UrlChange body) {
        return connections.saveValhalla(body.url());
    }

    @PostMapping("/api/connections/ingestion-api/test")
    public IngestionApiCheck testIngestionApi() {
        return connections.testIngestionApi();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badAddress(IllegalArgumentException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> refused(IllegalStateException problem) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", problem.getMessage()));
    }
}
