package com.srm.gps.configuration.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts the platform's messages to and from JSON.
 * Every module uses this class, so all of them agree on exactly the same JSON format.
 * Dates are written as ISO-8601 text, for example "2026-07-13T00:25:17Z".
 */
public final class GpsMessageJson {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private GpsMessageJson() {
    }

    public static String toJson(Object message) {
        return JSON_MAPPER.writeValueAsString(message);
    }

    /**
     * Reads a message from JSON.
     *
     * @throws InvalidGpsMessageException when the text is not valid JSON for the requested type
     */
    public static <T> T fromJson(String json, Class<T> messageType) {
        try {
            return JSON_MAPPER.readValue(json, messageType);
        } catch (JacksonException exception) {
            throw new InvalidGpsMessageException(
                    "Could not read " + messageType.getSimpleName() + " from JSON: " + exception.getOriginalMessage(), exception);
        }
    }

    /** Thrown when a message cannot be read from JSON. */
    public static class InvalidGpsMessageException extends RuntimeException {
        public InvalidGpsMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
