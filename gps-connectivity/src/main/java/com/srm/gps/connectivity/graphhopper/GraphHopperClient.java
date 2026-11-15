package com.srm.gps.connectivity.graphhopper;

import com.srm.gps.connectivity.http.JsonHttp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The GraphHopper routing engine on this laptop (compose profile "routing", http://graphhopper:8989): the India graph
 * with a truck profile, contraction hierarchies for speed. Used when Valhalla on the home server does not answer.
 *
 *   GET  /info    the map's bounds, the profiles, when it was imported
 *   POST /route   points [lon, lat] in order: distance (m), time (ms), the line, instructions with point intervals
 */
public class GraphHopperClient {

    /** One instruction: the line's points from..to take this long. */
    public record Instruction(int fromIndex, int toIndex, double timeSeconds, double distanceMeters) {
    }

    /** @param points the line, {latitude, longitude} */
    public record Path(double distanceMeters, double timeSeconds, List<double[]> points, List<Instruction> instructions, long millis) {
    }

    public record Info(List<String> profiles, String dataDate, long millis) {
    }

    private final JsonHttp http;

    public GraphHopperClient(JsonHttp http) {
        this.http = http;
    }

    public Info info(String baseUrl) throws IOException, InterruptedException {
        JsonHttp.Answer answer = http.get(JsonHttp.baseUrl(baseUrl) + "/info");
        List<String> profiles = new ArrayList<>();
        if (answer.body().get("profiles") instanceof List<?> list) {
            for (Object p : list) {
                if (p instanceof Map<?, ?> profile && profile.get("name") != null) {
                    profiles.add(String.valueOf(profile.get("name")));
                }
            }
        }
        Object date = answer.body().get("data_date");
        return new Info(profiles, date == null ? null : String.valueOf(date), answer.millis());
    }

    /** @param latLon the points in order, {latitude, longitude} */
    public Path route(String baseUrl, List<double[]> latLon, String profile) throws IOException, InterruptedException {
        List<List<Double>> points = new ArrayList<>();
        for (double[] p : latLon) {
            points.add(List.of(p[1], p[0]));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("points", points);
        request.put("profile", profile);
        request.put("points_encoded", false);
        request.put("instructions", true);
        request.put("locale", "en");
        JsonHttp.Answer answer = http.post(JsonHttp.baseUrl(baseUrl) + "/route", request);
        if (!(answer.body().get("paths") instanceof List<?> paths) || paths.isEmpty() || !(paths.get(0) instanceof Map<?, ?> path)) {
            throw new IOException("GraphHopper answered without a path");
        }
        List<double[]> line = new ArrayList<>();
        if (path.get("points") instanceof Map<?, ?> geometry && geometry.get("coordinates") instanceof List<?> coordinates) {
            for (Object c : coordinates) {
                if (c instanceof List<?> xy && xy.size() >= 2 && xy.get(0) instanceof Number lon && xy.get(1) instanceof Number lat) {
                    line.add(new double[] {lat.doubleValue(), lon.doubleValue()});
                }
            }
        }
        List<Instruction> instructions = new ArrayList<>();
        if (path.get("instructions") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> i && i.get("interval") instanceof List<?> interval && interval.size() == 2
                        && interval.get(0) instanceof Number from && interval.get(1) instanceof Number to) {
                    instructions.add(new Instruction(from.intValue(), to.intValue(), number(i.get("time")) / 1000.0, number(i.get("distance"))));
                }
            }
        }
        if (line.size() < 2) {
            throw new IOException("GraphHopper answered without a line");
        }
        return new Path(number(path.get("distance")), number(path.get("time")) / 1000.0, line, instructions, answer.millis());
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
