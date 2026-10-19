package com.srm.gps.processing.trip;

/**
 * The device_assignment a reading belongs to: which vehicle (and company) the device was fitted in at that moment.
 * A trip never spans two assignments, so a change closes the open trip.
 *
 * @param assignmentId          id of the device_assignment row
 * @param tenantCode            owner company of the vehicle (null for a market vehicle)
 * @param vehicleRegistrationNo the vehicle's number plate
 */
public record VehicleAssignment(String assignmentId, String tenantCode, String vehicleRegistrationNo) {
}
