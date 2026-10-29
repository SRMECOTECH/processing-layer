package com.srm.gps.route.api;

import com.srm.gps.route.engine.GeoPoint;
import com.srm.gps.route.engine.RouteResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A planned route as the API returns it: kilometres and minutes, points as [latitude, longitude] (Leaflet's order).
 *
 * @param routes the best route first; alternatives after it when algorithm=ALTERNATIVES
 */
public record RouteView(String profile, String algorithm, long visitedNodes, long engineMillis, String roadDataDate,
                        List<PathView> routes) {

    public static RouteView from(RouteResult result) {
        return new RouteView(result.query().profile(), result.query().algorithm().name(), result.visitedNodes(),
                result.engineMillis(), result.roadDataDate(), result.paths().stream().map(PathView::from).toList());
    }

    /**
     * @param kmByRoadClass length per road class: MOTORWAY expressways, TRUNK national highways, PRIMARY state highways ...
     * @param tollKm        length on tolled roads
     */
    public record PathView(double distanceKm, double durationMinutes, double tollKm, Map<String, Double> kmByRoadClass,
                           List<double[]> points, List<InstructionView> instructions) {

        static PathView from(RouteResult.Path path) {
            Map<String, Double> kmByRoadClass = new LinkedHashMap<>();
            path.metersByRoadClass().forEach((roadClass, meters) -> kmByRoadClass.put(roadClass, km(meters)));
            return new PathView(km(path.distanceMeters()), minutes(path.durationMillis()), km(path.tollMeters()),
                    kmByRoadClass, path.points().stream().map(PathView::latLon).toList(),
                    path.instructions().stream().map(InstructionView::from).toList());
        }

        private static double[] latLon(GeoPoint point) {
            return new double[]{point.latitude(), point.longitude()};
        }
    }

    public record InstructionView(String text, String streetName, double distanceKm, double durationMinutes, int sign) {

        static InstructionView from(RouteResult.Instruction instruction) {
            return new InstructionView(instruction.text(), instruction.streetName(), km(instruction.distanceMeters()),
                    minutes(instruction.durationMillis()), instruction.sign());
        }
    }

    static double km(double meters) {
        return Math.round(meters / 10.0) / 100.0;
    }

    static double minutes(long millis) {
        return Math.round(millis / 600.0) / 100.0;
    }
}
