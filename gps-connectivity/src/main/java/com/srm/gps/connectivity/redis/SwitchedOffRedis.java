package com.srm.gps.connectivity.redis;

import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisConnection;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Redis connection handed out while Redis is switched off (gps.redis.enabled=false): it talks to nothing.
 *
 * Every read answers "nothing there" (null, 0, false, an empty set/list/map/scan) and every write is dropped, so a page
 * or job that still asks Redis shows no live data instead of failing. The consumers that NEED Redis (the processing
 * modules and the live-location consumer) are not started at all while it is off, so no vehicle state is lost here.
 */
public final class SwitchedOffRedis {

    private static final String REDIS_PACKAGE = "org.springframework.data.redis.";

    private SwitchedOffRedis() {
    }

    public static RedisConnection connection() {
        return nothing(RedisConnection.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T nothing(Class<T> type) {
        return (T) Proxy.newProxyInstance(SwitchedOffRedis.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> answer(proxy, method, args));
    }

    private static Object answer(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "Redis (switched off)";
            default:
                return emptyOf(method.getReturnType());
        }
    }

    private static Object emptyOf(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type.isPrimitive()) {
            return type == float.class ? 0f : type == short.class ? (short) 0 : type == byte.class ? (byte) 0 : '\0';
        }
        if (Set.class.isAssignableFrom(type)) {
            return Collections.emptySet();
        }
        if (Map.class == type) {
            return Collections.emptyMap();
        }
        if (List.class.isAssignableFrom(type) || Collection.class == type) {
            return Collections.emptyList();
        }
        if (Stream.class.isAssignableFrom(type)) {
            return Stream.empty();
        }
        if (Iterator.class == type) {
            return Collections.emptyIterator();
        }
        if (Optional.class == type) {
            return Optional.empty();
        }
        if (GeoResults.class == type) {
            return new GeoResults<>(Collections.emptyList());
        }
        // the command groups (hashCommands(), keyCommands() ...) and a scan Cursor: switched off as well
        if (type.isInterface() && type.getName().startsWith(REDIS_PACKAGE)) {
            return nothing(type);
        }
        return null;
    }
}
