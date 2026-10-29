package com.srm.gps.application.report;

import com.srm.gps.processing.report.FuelAssumptions;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** The cost assumptions of the reports, in PostgreSQL (report_setting). A missing row means the default. */
@Service
public class ReportSettingStore {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportSettingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FuelAssumptions assumptions() {
        Map<String, String> saved = new LinkedHashMap<>();
        jdbc.query("SELECT name, value FROM report_setting", rs -> {
            saved.put(rs.getString("name"), rs.getString("value"));
        });
        FuelAssumptions d = FuelAssumptions.defaults();
        String fuelType = saved.getOrDefault("fuel-type", d.fuelType());
        return new FuelAssumptions(fuelType,
                number(saved, "price-per-litre", d.pricePerLitre()),
                number(saved, "mileage-km-per-litre", d.mileageKmPerLitre()),
                number(saved, "idle-litres-per-hour", d.idleLitresPerHour()),
                (int) number(saved, "economical-speed-kmph", d.economicalSpeedKmph()),
                number(saved, "fast-driving-penalty-percent", d.fastDrivingPenaltyPercent()),
                number(saved, "co2-kg-per-litre", d.co2KgPerLitre()));
    }

    public FuelAssumptions save(FuelAssumptions a) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("fuel-type", a.fuelType());
        values.put("price-per-litre", Double.toString(a.pricePerLitre()));
        values.put("mileage-km-per-litre", Double.toString(a.mileageKmPerLitre()));
        values.put("idle-litres-per-hour", Double.toString(a.idleLitresPerHour()));
        values.put("economical-speed-kmph", Integer.toString(a.economicalSpeedKmph()));
        values.put("fast-driving-penalty-percent", Double.toString(a.fastDrivingPenaltyPercent()));
        values.put("co2-kg-per-litre", Double.toString(a.co2KgPerLitre()));
        values.forEach((name, value) -> jdbc.update("""
                INSERT INTO report_setting (name, value, updated_at) VALUES (:name, :value, now())
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at""",
                new MapSqlParameterSource("name", name).addValue("value", value)));
        return assumptions();
    }

    private static double number(Map<String, String> saved, String name, double fallback) {
        try {
            return saved.containsKey(name) ? Double.parseDouble(saved.get(name)) : fallback;
        } catch (NumberFormatException problem) {
            return fallback;
        }
    }
}
