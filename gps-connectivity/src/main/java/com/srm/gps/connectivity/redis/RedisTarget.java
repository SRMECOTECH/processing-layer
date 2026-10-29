package com.srm.gps.connectivity.redis;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where a Redis is: host, port and database number, written as redis://host:port/database.
 *
 * The database number keeps two programs apart on one Redis server. On the HP home server the ingestion API (FastAPI)
 * writes database 0 (gps:points, last:*, track:*, devices), so this service uses database 1 there.
 */
public record RedisTarget(String host, int port, int database) {

    public static final int DEFAULT_PORT = 6379;

    public RedisTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("The Redis host is missing");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("The Redis port must be 1-65535, not " + port);
        }
        if (database < 0 || database > 15) {
            throw new IllegalArgumentException("The Redis database must be 0-15, not " + database);
        }
        host = host.trim();
    }

    /** Reads redis://host:port/database; the port defaults to 6379 and the database to 0. */
    public static RedisTarget parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("The Redis address is missing");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException problem) {
            throw new IllegalArgumentException("Not a Redis address: " + url);
        }
        if (!"redis".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Write the Redis address as redis://host:port/database, not " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Redis with a password is not supported here");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+|/+$", "");
        int database;
        try {
            database = path.isEmpty() ? 0 : Integer.parseInt(path);
        } catch (NumberFormatException problem) {
            throw new IllegalArgumentException("The Redis database must be a number, not '" + path + "'");
        }
        return new RedisTarget(uri.getHost(), uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort(), database);
    }

    public String url() {
        return "redis://" + host + ":" + port + "/" + database;
    }

    @Override
    public String toString() {
        return url();
    }
}
