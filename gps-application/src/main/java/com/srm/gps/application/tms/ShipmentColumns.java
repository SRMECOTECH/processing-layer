package com.srm.gps.application.tms;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which column of a transporter's shipment file is which field.
 *
 * Files come in two shapes, both handled:
 *   - with a header row: each header is matched against the field's names - the smart_truck tta_trips column names
 *     (i_trip_no, s_asset_id ...) and the words people put in Excel ("Trip No", "Vehicle No", "IMEI", "Consignee" ...),
 *     ignoring case, spaces and punctuation;
 *   - without one, exactly in the tta_trips column order (44 columns, like a row copied out of the TMS database):
 *     recognised when the first row matches no header but has the tta_trips width.
 */
public final class ShipmentColumns {

    /** The fields a shipment row can carry, each with the names it goes by. */
    public enum Field {
        TRIP_NO(true, "i_trip_no", "trip no", "trip number", "trip id", "lr no", "lr number", "consignment no", "cn no", "docket no"),
        CONSIGNER_CODE(false, "i_cnr_id", "consignor id", "consigner id", "consignor code", "consigner code"),
        TRIP_CLASS(false, "s_trip_class", "trip class"),
        CONSIGNER(false, "s_cnr_name", "consignor", "consigner", "consignor name", "consigner name", "shipper"),
        VEHICLE_NO(true, "s_asset_id", "vehicle no", "vehicle number", "truck no", "truck number", "registration no", "vehicle", "asset id"),
        DEVICE_IMEI(false, "s_device_id", "imei", "device id", "device imei", "gps imei", "tracker imei", "gps device"),
        VEHICLE_TYPE(false, "s_asset_type", "vehicle type", "asset type", "truck type"),
        TRIP_TYPE_CODE(false, "c_trip_type"),
        TRIP_TYPE(false, "s_trip_type_desc", "trip type", "trip type desc"),
        ORIGIN(false, "s_org_node_name", "origin", "from", "source", "from location", "origin name"),
        DESTINATION(false, "s_dest_node_name", "destination", "to", "to location", "destination name"),
        FINAL_DESTINATION(false, "s_final_dest", "final destination", "ship to", "ship to address", "delivery address"),
        LOAD_PLANT(false, "s_load_plant", "load plant", "loading point", "plant", "loading plant"),
        BOOKED_AT(false, "dt_booking", "booking time", "booking date", "booking", "reporting time", "plant in", "plant entry"),
        DISPATCHED_AT(true, "dt_trip_start", "trip start", "trip start time", "dispatch time", "dispatch date", "dispatched at", "gate out", "start time"),
        ETA(false, "dt_trip_eta", "eta", "expected arrival", "expected delivery"),
        ATA(false, "dt_trip_ata", "ata", "actual arrival", "arrived at", "arrival time"),
        ENDED_AT(false, "dt_trip_end", "trip end", "trip end time", "end time", "closed at"),
        TRANSPORTER_CODE(false, "i_trans_id", "transporter id", "transporter code", "vendor code", "vendor id"),
        TRANSPORTER(false, "s_trans_name", "transporter", "transporter name", "vendor name", "carrier"),
        CONSIGNEE(false, "s_cne_name", "consignee", "consignee name", "customer", "receiver"),
        STATUS(false, "c_trip_status", "status", "trip status"),
        CLOSE_REASON(false, "s_close_reason", "close reason", "closing reason"),
        INVOICE(false, "s_invoice", "invoice", "invoice no", "invoice number"),
        CARD_ID(false, "s_card_id", "card id", "rfid", "rfid card"),
        SHIPMENT_REF(false, "s_shipment_id", "shipment id", "shipment no", "shipment number", "delivery no"),
        GATE_ENTRY(false, "s_gate_entry_no", "gate entry no", "gate entry", "gate pass"),
        DRIVER_NAME(false, "s_driver_name", "driver", "driver name"),
        DRIVER_MOBILE(false, "s_driver_mobile_no", "driver mobile", "driver mobile no", "driver phone", "driver contact", "mobile"),
        ROUTE_ID(false, "i_route_id", "route id"),
        MATERIAL(false, "s_material_desc", "material", "material desc", "material description", "product", "commodity"),
        QUANTITY(false, "d_inv_qty", "quantity", "qty", "invoice qty", "weight", "tonnage", "net weight");

        private final boolean required;
        private final List<String> names;

        Field(boolean required, String... names) {
            this.required = required;
            this.names = List.of(names);
        }

        public boolean required() {
            return required;
        }

        /** The smart_truck column name, used in the template. */
        public String columnName() {
            return names.getFirst();
        }
    }

    /**
     * The smart_truck tta_trips columns in table order: a header-less row is read by position in this list.
     * null = a column the shipment does not take (node numbers, audit columns, the TMS's own derived values).
     */
    static final List<String> TTA_TRIPS_ORDER = List.of(
            "i_trip_no", "i_cnr_id", "s_trip_class", "s_cnr_name", "s_asset_id", "s_device_id", "s_asset_type", "c_trip_type",
            "s_trip_type_desc", "i_org_node_no", "s_org_node_name", "i_dest_node_no", "s_dest_node_name", "s_final_dest",
            "s_load_plant", "dt_booking", "dt_trip_start", "dt_trip_eta", "dt_trip_ata", "dt_trip_end", "i_trans_id",
            "s_trans_name", "s_cne_name", "c_trip_status", "s_close_reason", "s_invoice", "s_card_id", "s_shipment_id",
            "s_event_code", "s_gate_entry_no", "s_driver_name", "s_driver_mobile_no", "i_route_id", "s_created_by",
            "dt_created", "s_modified_by", "dt_modified", "i_gps_ping_count", "dt_geofence_out", "i_geofence_out_gap_min",
            "s_geofence_out_status", "i_works_detention_min", "i_geofence_tail_min", "i_origin_total_min");

    private static final Map<String, Field> BY_NAME = new LinkedHashMap<>();

    static {
        for (Field field : Field.values()) {
            for (String name : field.names) {
                BY_NAME.put(normalise(name), field);
            }
        }
    }

    /**
     * Where each field is in a file.
     *
     * @param columns     field -> column index
     * @param headerRow   true when the first row is a header (and not data)
     * @param layout      "header" or "tta_trips" (header-less, by position)
     * @param ignored     header texts of columns no field takes
     */
    public record Mapping(Map<Field, Integer> columns, boolean headerRow, String layout, List<String> ignored) {

        public List<Field> missingRequired() {
            List<Field> missing = new ArrayList<>();
            for (Field field : Field.values()) {
                if (field.required() && !columns.containsKey(field)) {
                    missing.add(field);
                }
            }
            return missing;
        }

        /** field -> the header text (or tta_trips column) it was read from, for the preview. */
        public Map<String, String> describe(List<String> header) {
            Map<String, String> described = new LinkedHashMap<>();
            columns.forEach((field, index) -> described.put(field.name(),
                    headerRow && index < header.size() ? header.get(index) : TTA_TRIPS_ORDER.get(index)));
            return described;
        }
    }

    private ShipmentColumns() {
    }

    public static Mapping detect(List<String> firstRow) {
        Map<Field, Integer> columns = new EnumMap<>(Field.class);
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < firstRow.size(); i++) {
            String text = firstRow.get(i);
            Field field = text == null ? null : BY_NAME.get(normalise(text));
            if (field != null && !columns.containsKey(field)) {
                columns.put(field, i);
            } else if (text != null && !text.isBlank()) {
                ignored.add(text);
            }
        }
        if (columns.size() >= 3) {
            return new Mapping(columns, true, "header", ignored);
        }
        // no header: read by the tta_trips positions when the row is that wide
        if (firstRow.size() >= TTA_TRIPS_ORDER.size() - 3) {
            Map<Field, Integer> byPosition = new EnumMap<>(Field.class);
            for (int i = 0; i < TTA_TRIPS_ORDER.size() && i < firstRow.size(); i++) {
                Field field = BY_NAME.get(normalise(TTA_TRIPS_ORDER.get(i)));
                if (field != null) {
                    byPosition.put(field, i);
                }
            }
            return new Mapping(byPosition, false, "tta_trips", List.of());
        }
        return new Mapping(columns, true, "header", ignored);
    }

    /** "S_Asset_ID", "Vehicle No.", "vehicle-no" all become "s asset id" / "vehicle no". */
    static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
