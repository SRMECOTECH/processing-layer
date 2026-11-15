package com.srm.gps.application.tms;

import com.srm.gps.application.tms.ShipmentColumns.Field;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One row of a shipment file, read into typed values and checked. A row with a problem is reported and not imported;
 * a warning is reported and the row is imported without that value.
 *
 * Times without a zone are the consigner's local time (India), as the smart_truck TMS writes them.
 */
public record ShipmentDraft(
        int rowNumber,
        String tripNo,
        String consignerCode,
        String consignerUnit,
        String tripClass,
        String vehicleNo,
        String deviceImei,
        String vehicleType,
        String tripType,
        String origin,
        String destination,
        String finalDestination,
        String loadPlant,
        Instant bookedAt,
        Instant dispatchedAt,
        Instant etaAt,
        Instant ataAt,
        Instant endedAt,
        String transporterCode,
        String transporterName,
        String consignee,
        String status,
        String closeReason,
        String invoice,
        String cardId,
        String shipmentRef,
        String gateEntry,
        String driverName,
        String driverMobile,
        String routeId,
        String material,
        BigDecimal quantity,
        List<String> problems,
        List<String> warnings) {

    private static final List<DateTimeFormatter> LOCAL_TIMES = List.of(
            pattern("yyyy-MM-dd HH:mm[:ss][.SSSSSS][.SSS][.S]"),
            pattern("yyyy-MM-dd'T'HH:mm[:ss][.SSSSSS][.SSS][.S]"),
            pattern("dd-MM-yyyy HH:mm[:ss]"),
            pattern("dd/MM/yyyy HH:mm[:ss]"),
            pattern("dd.MM.yyyy HH:mm[:ss]"),
            pattern("dd-MMM-yyyy HH:mm[:ss]"),
            pattern("dd MMM yyyy HH:mm[:ss]"),
            pattern("d/M/yyyy H:mm[:ss]"));
    private static final List<DateTimeFormatter> LOCAL_DATES = List.of(
            pattern("yyyy-MM-dd"), pattern("dd-MM-yyyy"), pattern("dd/MM/yyyy"), pattern("dd.MM.yyyy"), pattern("dd-MMM-yyyy"));

    public boolean valid() {
        return problems.isEmpty();
    }

    /** Whether the trip is a local one (inside the city or the plant's zone): the estimate then uses city speeds. */
    public boolean local() {
        return tripClass != null && tripClass.equalsIgnoreCase("local");
    }

    /**
     * A fingerprint of everything the row says: the same row uploaded again has the same hash and is left alone.
     */
    public String hash() {
        String canonical = String.join("\u001f", List.of(
                s(tripNo), s(consignerCode), s(consignerUnit), s(tripClass), s(vehicleNo), s(deviceImei), s(vehicleType), s(tripType),
                s(origin), s(destination), s(finalDestination), s(loadPlant), s(bookedAt), s(dispatchedAt), s(etaAt), s(ataAt),
                s(endedAt), s(transporterCode), s(transporterName), s(consignee), s(status), s(closeReason), s(invoice), s(cardId),
                s(shipmentRef), s(gateEntry), s(driverName), s(driverMobile), s(routeId), s(material),
                quantity == null ? "" : quantity.stripTrailingZeros().toPlainString()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static ShipmentDraft parse(int rowNumber, List<String> cells, Map<Field, Integer> columns, ZoneId zone) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Reader r = new Reader(cells, columns, zone, problems, warnings);

        String tripNo = r.text(Field.TRIP_NO, 60);
        if (tripNo == null) {
            problems.add("No trip number");
        }
        String vehicle = vehicleNo(r.text(Field.VEHICLE_NO, 50));
        if (vehicle == null) {
            problems.add("No vehicle number");
        }
        String imei = r.text(Field.DEVICE_IMEI, 50);
        if (imei != null && !imei.matches("\\d{6,20}")) {
            warnings.add("IMEI " + imei + " is not a device number; ignored");
            imei = null;
        }
        Instant booked = r.time(Field.BOOKED_AT);
        Instant dispatched = r.time(Field.DISPATCHED_AT);
        if (dispatched == null && booked != null) {
            warnings.add("No trip start (gate-out): the booking time is used");
            dispatched = booked;
        } else if (dispatched == null && !problems.contains("No trip number")) {
            problems.add("No trip start (gate-out) time");
        }
        Instant eta = r.time(Field.ETA);
        if (eta != null && dispatched != null && eta.isBefore(dispatched)) {
            warnings.add("ETA is before the trip start; ignored");
            eta = null;
        }
        String tripType = r.text(Field.TRIP_TYPE, 100);
        return new ShipmentDraft(rowNumber, tripNo, r.text(Field.CONSIGNER_CODE, 60), r.text(Field.CONSIGNER, 255),
                r.text(Field.TRIP_CLASS, 20), vehicle, imei, r.text(Field.VEHICLE_TYPE, 100),
                tripType != null ? tripType : r.text(Field.TRIP_TYPE_CODE, 100),
                r.text(Field.ORIGIN, 255), r.text(Field.DESTINATION, 255), address(r.text(Field.FINAL_DESTINATION, 500)),
                r.text(Field.LOAD_PLANT, 255), booked, dispatched, eta, r.time(Field.ATA), r.time(Field.ENDED_AT),
                r.text(Field.TRANSPORTER_CODE, 60), r.text(Field.TRANSPORTER, 255), r.text(Field.CONSIGNEE, 255),
                r.text(Field.STATUS, 30), r.text(Field.CLOSE_REASON, 255), r.text(Field.INVOICE, 100), r.text(Field.CARD_ID, 50),
                r.text(Field.SHIPMENT_REF, 100), r.text(Field.GATE_ENTRY, 100), r.text(Field.DRIVER_NAME, 255),
                r.text(Field.DRIVER_MOBILE, 20), r.text(Field.ROUTE_ID, 30), r.text(Field.MATERIAL, 500), r.number(Field.QUANTITY),
                List.copyOf(problems), List.copyOf(warnings));
    }

    /** "jh 05 cy-2516" -> "JH05CY2516": the plate as the master data keeps it. */
    static String vehicleNo(String text) {
        if (text == null) {
            return null;
        }
        String plate = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return plate.length() >= 4 ? plate : null;
    }

    /** "TATA STEEL PROCESSING (ASOKA SKY ME,  ,  ,JAMSHEDPUR" -> "TATA STEEL PROCESSING (ASOKA SKY ME, JAMSHEDPUR" */
    static String address(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("(\\s*,\\s*)+", ", ").replaceAll("^[,\\s]+|[,\\s]+$", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    static Instant parseTime(String text, ZoneId zone) {
        String t = text.trim();
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException notWithZone) {
            // most files: local time without a zone
        }
        for (DateTimeFormatter f : LOCAL_TIMES) {
            try {
                return LocalDateTime.parse(t, f).atZone(zone).toInstant();
            } catch (DateTimeParseException tryNext) {
                // next pattern
            }
        }
        for (DateTimeFormatter f : LOCAL_DATES) {
            try {
                return LocalDate.parse(t, f).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException tryNext) {
                // next pattern
            }
        }
        return null;
    }

    private static DateTimeFormatter pattern(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH);
    }

    private static String s(Object value) {
        return value == null ? "" : value.toString();
    }

    private record Reader(List<String> cells, Map<Field, Integer> columns, ZoneId zone, List<String> problems, List<String> warnings) {

        String text(Field field, int maxLength) {
            Integer index = columns.get(field);
            if (index == null || index >= cells.size()) {
                return null;
            }
            String value = ShipmentFileReader.clean(cells.get(index));
            if (value != null && value.length() > maxLength) {
                warnings.add(field.columnName() + " was longer than " + maxLength + " characters and was cut");
                value = value.substring(0, maxLength);
            }
            return value;
        }

        Instant time(Field field) {
            String value = text(field, 60);
            if (value == null) {
                return null;
            }
            Instant time = parseTime(value, zone);
            if (time == null) {
                warnings.add(field.columnName() + " '" + value + "' is not a date and time; ignored");
            }
            return time;
        }

        BigDecimal number(Field field) {
            String value = text(field, 40);
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(value.replace(",", ""));
            } catch (NumberFormatException notANumber) {
                warnings.add(field.columnName() + " '" + value + "' is not a number; ignored");
                return null;
            }
        }
    }
}
