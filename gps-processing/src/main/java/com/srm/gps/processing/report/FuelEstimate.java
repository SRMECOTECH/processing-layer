package com.srm.gps.processing.report;

/**
 * Fuel and money for one vehicle over a period, estimated from what it did (see {@link FuelEstimator}).
 *
 * @param drivingLitres    distance / mileage
 * @param idleLitres       idling hours x idle burn: fuel burnt standing still with the engine on
 * @param fastLitres       extra fuel of the kilometres above the economical speed
 * @param usedLitres       driving + idle + fast
 * @param savableLitres    idle + fast: what better habits would have saved
 * @param cost             used litres x price (rupees)
 * @param savableCost      savable litres x price (rupees)
 * @param co2Kg            used litres x CO2 per litre
 * @param savableCo2Kg     savable litres x CO2 per litre
 */
public record FuelEstimate(double drivingLitres, double idleLitres, double fastLitres, double usedLitres, double savableLitres,
                           double cost, double savableCost, double co2Kg, double savableCo2Kg) {

    public static final FuelEstimate NONE = new FuelEstimate(0, 0, 0, 0, 0, 0, 0, 0, 0);

    public FuelEstimate plus(FuelEstimate other) {
        return new FuelEstimate(drivingLitres + other.drivingLitres, idleLitres + other.idleLitres, fastLitres + other.fastLitres,
                usedLitres + other.usedLitres, savableLitres + other.savableLitres, cost + other.cost,
                savableCost + other.savableCost, co2Kg + other.co2Kg, savableCo2Kg + other.savableCo2Kg);
    }
}
