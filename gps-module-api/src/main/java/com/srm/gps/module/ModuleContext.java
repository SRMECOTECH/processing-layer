package com.srm.gps.module;

import com.srm.gps.configuration.message.EventSeverity;
import com.srm.gps.configuration.message.VehicleEvent;
import com.srm.gps.configuration.message.VehicleEventType;

import java.time.Instant;
import java.util.Map;

/**
 * What the platform hands a module with each reading. Everything here is safe to call again for the same reading.
 */
public interface ModuleContext {

    /** The vehicle and company the device was fitted in AT THE TIME OF THE READING (master data, device_assignment). */
    VehicleRef vehicle();

    /** The module's own state for this device: one Redis hash, gps:state:{module id}:{imei}. */
    ModuleState state();

    /**
     * Records an event: PostgreSQL vehicle_event (upserted by IMEI + type + start, so the same episode twice is one
     * row), the vehicle's recent events in Redis and the gps.vehicle-events topic.
     */
    void emit(VehicleEvent event);

    /** A module setting: gps.modules.{module id}.{name}, or the default when it is not set. */
    String setting(String name, String defaultValue);

    /**
     * @param imei            the device
     * @param registrationNo  the vehicle, or null when the device is not mapped to one at that moment
     * @param tenantCode      the vehicle's owner company, or null
     */
    record VehicleRef(String imei, String registrationNo, String tenantCode) {

        public boolean mapped() {
            return registrationNo != null;
        }

        /** An event of this vehicle; {@code detectedBy} is the module id or its stage name. */
        public VehicleEvent event(VehicleEventType type, EventSeverity severity, VehicleEvent.Status status, Instant startedAt,
                                  Instant endedAt, double latitude, double longitude, String detectedBy,
                                  Map<String, Object> details) {
            return new VehicleEvent(imei, registrationNo, tenantCode, type, severity, status, startedAt, endedAt, latitude,
                    longitude, detectedBy, details, Instant.now());
        }
    }

    /** One Redis hash per module and device. */
    interface ModuleState {

        /** Every field, or an empty map when the module has no state for this device yet. */
        Map<String, String> read();

        /** Sets these fields (others are kept). */
        void write(Map<String, String> fields);

        void clear();
    }
}
