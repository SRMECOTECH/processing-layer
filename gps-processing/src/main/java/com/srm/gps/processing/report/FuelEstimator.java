package com.srm.gps.processing.report;

/**
 * Turns what a vehicle did into litres, rupees and CO2. The same formulas are shown under every number in the
 * reports ("How is this calculated?"), so a manager can check each one by hand:
 *
 *   driving litres  = distance km / mileage km per litre
 *   idle litres     = idling hours x idle litres per hour
 *   fast litres     = km above the economical speed / mileage x penalty %
 *   used litres     = driving + idle + fast
 *   savable litres  = idle + fast          (an engine switched off, a steadier speed)
 *   rupees          = litres x price per litre
 *   CO2 kg          = litres x CO2 per litre
 *
 * An estimate, not a fuel-sensor reading: no fleet sensor is connected yet.
 */
public final class FuelEstimator {

    private FuelEstimator() {
    }

    /**
     * @param distanceKm  kilometres driven
     * @param idleHours   hours standing with the engine on
     * @param fastKm      kilometres driven above the economical speed (part of distanceKm)
     */
    public static FuelEstimate estimate(double distanceKm, double idleHours, double fastKm, FuelAssumptions a) {
        double driving = Math.max(0, distanceKm) / a.mileageKmPerLitre();
        double idle = Math.max(0, idleHours) * a.idleLitresPerHour();
        double fast = Math.max(0, fastKm) / a.mileageKmPerLitre() * a.fastDrivingPenaltyPercent() / 100.0;
        double used = driving + idle + fast;
        double savable = idle + fast;
        return new FuelEstimate(driving, idle, fast, used, savable, used * a.pricePerLitre(), savable * a.pricePerLitre(),
                used * a.co2KgPerLitre(), savable * a.co2KgPerLitre());
    }
}
