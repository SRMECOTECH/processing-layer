package com.srm.gps.route.engine;

/** The routing engine refused a request (no road near a point, no connection, unknown profile ...). */
public class RoutingEngineException extends RuntimeException {

    public RoutingEngineException(String message) {
        super(message);
    }

    /** The engine could not be reached or did not answer in time - also while it is still importing the map. */
    public static class Unavailable extends RoutingEngineException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
