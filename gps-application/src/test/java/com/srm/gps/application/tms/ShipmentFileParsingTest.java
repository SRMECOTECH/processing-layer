package com.srm.gps.application.tms;

import com.srm.gps.application.tms.ShipmentColumns.Field;
import com.srm.gps.application.tms.ShipmentColumns.Mapping;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentFileParsingTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** A row exactly as the transporter sent it (smart_truck tta_trips, trip 754710). */
    private static final String SAMPLE = "'754710', '540', 'local', 'TATA STEEL-JSR', 'JH05CY2516', '0869925072822461', 'TRAILER', NULL, "
            + "'LOCAL TRIP', NULL, 'JAMSHEDPUR', NULL, 'ADITYAPUR', 'TATA STEEL PROCESSING (ASOKA SKY ME,  ,  ,JAMSHEDPUR', "
            + "'LD3&TSCR Plant', '2026-08-03 04:31:10', '2026-08-03 11:20:43', '2026-08-04 11:20:43', NULL, NULL, '0000015300', "
            + "'Virk Eenterprises', 'TATA STEEL PROCESSING (ASOKA SKY ME', 'Close', 'TRIP CLOSE BY GRN', '2314959959', '9FE9E1BB', "
            + "NULL, NULL, NULL, 'ASH0K KUMAR YADAV', '8229835350', NULL, NULL, '2026-09-04 17:39:10', NULL, '2026-09-05 19:15:01', "
            + "'1430', NULL, NULL, 'intra_fence', '409', NULL, NULL";

    @Test
    void aRowCopiedOutOfTheTmsIsReadByTheTtaTripsColumnOrder() {
        ShipmentFileReader.Sheet2D sheet = ShipmentFileReader.read("pasted.txt", SAMPLE.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, sheet.rows().size());
        assertEquals(44, sheet.rows().getFirst().size());
        Mapping mapping = ShipmentColumns.detect(sheet.rows().getFirst());
        assertEquals("tta_trips", mapping.layout());
        assertFalse(mapping.headerRow());
        assertTrue(mapping.missingRequired().isEmpty());

        ShipmentDraft d = ShipmentDraft.parse(1, sheet.rows().getFirst(), mapping.columns(), INDIA);
        assertTrue(d.valid(), () -> d.problems().toString());
        assertEquals("754710", d.tripNo());
        assertEquals("540", d.consignerCode());
        assertEquals("TATA STEEL-JSR", d.consignerUnit());
        assertEquals("JH05CY2516", d.vehicleNo());
        assertEquals("0869925072822461", d.deviceImei());
        assertEquals("JAMSHEDPUR", d.origin());
        assertEquals("ADITYAPUR", d.destination());
        assertEquals("TATA STEEL PROCESSING (ASOKA SKY ME, JAMSHEDPUR", d.finalDestination());
        assertEquals("LD3&TSCR Plant", d.loadPlant());
        assertEquals(Instant.parse("2026-08-02T23:01:10Z"), d.bookedAt());       // 04:31:10 IST
        assertEquals(Instant.parse("2026-08-03T05:50:43Z"), d.dispatchedAt());   // gate-out 11:20:43 IST
        assertEquals(Instant.parse("2026-08-04T05:50:43Z"), d.etaAt());
        assertNull(d.ataAt());
        assertEquals("0000015300", d.transporterCode());
        assertEquals("Virk Eenterprises", d.transporterName());
        assertEquals("TATA STEEL PROCESSING (ASOKA SKY ME", d.consignee());
        assertEquals("Close", d.status());
        assertEquals("TRIP CLOSE BY GRN", d.closeReason());
        assertEquals("2314959959", d.invoice());
        assertEquals("ASH0K KUMAR YADAV", d.driverName());
        assertEquals("8229835350", d.driverMobile());
        assertTrue(d.local());
    }

    @Test
    void anExcelStyleHeaderIsMatchedByItsWords() {
        List<List<String>> rows = ShipmentFileReader.readText("""
                Trip No,Vehicle No.,IMEI,Transporter,Consignor,Consignee,From,To,Trip Start,ETA,Material,Qty
                T-1001,jh 05 cy-2516,869925072822461,Virk Eenterprises,TATA STEEL-JSR,Beekay Steel,JAMSHEDPUR,ADITYAPUR,03/08/2026 11:20,04-08-2026 11:20,HR Coil,"1,250.5"
                """);
        Mapping mapping = ShipmentColumns.detect(rows.getFirst());
        assertTrue(mapping.headerRow());
        assertEquals(1, mapping.columns().get(Field.VEHICLE_NO));
        ShipmentDraft d = ShipmentDraft.parse(2, rows.get(1), mapping.columns(), INDIA);
        assertTrue(d.valid(), () -> d.problems().toString());
        assertEquals("JH05CY2516", d.vehicleNo());
        assertEquals(Instant.parse("2026-08-03T05:50:00Z"), d.dispatchedAt());
        assertEquals("HR Coil", d.material());
        assertEquals(0, d.quantity().compareTo(new java.math.BigDecimal("1250.5")));
    }

    @Test
    void theSameRowHasTheSameFingerprintSoAReUploadChangesNothing() {
        List<String> cells = ShipmentFileReader.readText(SAMPLE).getFirst();
        Mapping mapping = ShipmentColumns.detect(cells);
        String first = ShipmentDraft.parse(1, cells, mapping.columns(), INDIA).hash();
        String again = ShipmentDraft.parse(7, cells, mapping.columns(), INDIA).hash();
        assertEquals(first, again);
    }

    @Test
    void aRowWithoutTripNumberOrVehicleIsRefusedWithTheReason() {
        List<List<String>> rows = ShipmentFileReader.readText("trip no,vehicle no,trip start\n,  ,2026-08-03 11:20:43\n");
        ShipmentDraft d = ShipmentDraft.parse(2, rows.get(1), ShipmentColumns.detect(rows.getFirst()).columns(), INDIA);
        assertFalse(d.valid());
        assertTrue(d.problems().contains("No trip number"));
        assertTrue(d.problems().contains("No vehicle number"));
    }

    @Test
    void wholeInsertStatementsAreReadTupleByTuple() {
        List<List<String>> rows = ShipmentFileReader.readText(
                "INSERT INTO tta_trips VALUES (" + SAMPLE + "), (" + SAMPLE.replace("'754710'", "'754711'") + ");");
        assertEquals(2, rows.size());
        assertEquals("754711", rows.get(1).getFirst());
    }
}
