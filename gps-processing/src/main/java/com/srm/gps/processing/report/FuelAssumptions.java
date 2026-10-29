package com.srm.gps.processing.report;

/**
 * The numbers behind every fuel and cost estimate in the reports, edited on Reports -> Cost assumptions.
 * The defaults suit a loaded diesel truck in India; change them to the fleet's own figures.
 *
 * @param fuelType                 "diesel" or "petrol" (label, and the CO2 default)
 * @param pricePerLitre            rupees per litre
 * @param mileageKmPerLitre        kilometres per litre while driving
 * @param idleLitresPerHour        litres an idling engine burns per hour
 * @param economicalSpeedKmph      above this speed the engine burns more per kilometre
 * @param fastDrivingPenaltyPercent extra fuel per kilometre driven above the economical speed
 * @param co2KgPerLitre            kg of CO2 per litre burnt (diesel 2.68, petrol 2.31)
 */
public record FuelAssumptions(String fuelType, double pricePerLitre, double mileageKmPerLitre, double idleLitresPerHour,
                              int economicalSpeedKmph, double fastDrivingPenaltyPercent, double co2KgPerLitre) {

    public FuelAssumptions {
        fuelType = "petrol".equalsIgnoreCase(fuelType) ? "petrol" : "diesel";
        if (pricePerLitre < 0 || idleLitresPerHour < 0 || fastDrivingPenaltyPercent < 0 || co2KgPerLitre < 0) {
            throw new IllegalArgumentException("Prices, rates and percentages cannot be negative");
        }
        if (mileageKmPerLitre <= 0) {
            throw new IllegalArgumentException("The mileage must be more than 0 km per litre");
        }
        if (economicalSpeedKmph < 10 || economicalSpeedKmph > 150) {
            throw new IllegalArgumentException("The economical speed must be 10-150 km/h");
        }
    }

    public static FuelAssumptions defaults() {
        return new FuelAssumptions("diesel", 92.0, 4.0, 2.5, 60, 20.0, 2.68);
    }
}
