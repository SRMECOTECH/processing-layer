package com.srm.gps.application.tms;

import com.srm.gps.application.auth.DemoAccounts;
import com.srm.gps.application.auth.PortalUser;
import com.srm.gps.application.auth.PortalUser.Role;
import com.srm.gps.application.tms.ShipmentColumns.Mapping;
import com.srm.gps.application.tms.TenantDirectory.Company;
import com.srm.gps.application.tms.TenantDirectory.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reads a transporter's shipment file and imports it - first as a preview that writes nothing, then for real.
 *
 * Idempotent: a shipment is identified by its trip number within its transporter, so the same file uploaded twice,
 * or the consigner's next export repeating yesterday's trips, updates the shipments instead of adding copies; a row
 * that did not change is not even written. The whole file is imported in one transaction: all of it or nothing.
 *
 * Who carries a shipment: a transporter's own upload is always its own (a different transporter named in a row is
 * reported); the GPS provider's upload takes the transporter from each row (vendor code first, then name). The
 * consigner is the company part of the consignor name ("TATA STEEL-JSR" -> TATA STEEL, unit JSR), or the uploading
 * consigner itself.
 */
@Service
public class ShipmentImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ShipmentImportService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PROBLEMS_SHOWN = 200;

    /** The file's IMEI against the device fitted in the truck. */
    public record MappingHint(String vehicleNo, String imeiInFile, String mappedImei, String state) {
    }

    public record RowProblem(int row, String tripNo, String message, boolean blocking) {
    }

    /**
     * @param columns       field -> the header (or tta_trips column) it was read from
     * @param unknownPlaces origin / destination names with no geofence: arrival and departure cannot be seen there
     * @param newCompanies  transporters and consigners the import will add
     * @param importedBefore when this exact file was imported before, or null
     */
    public record Report(Long importId, String fileName, String format, String layout, Map<String, String> columns,
                         List<String> ignoredColumns, List<String> missingRequired, int rowsTotal, int rowsValid, int rowsFailed,
                         int created, int updated, int unchanged, int closedByNextUpload, List<RowProblem> problems,
                         Map<String, Integer> unknownPlaces, List<String> newCompanies, List<MappingHint> mappingHints,
                         Instant importedBefore, List<Map<String, Object>> sample, boolean committed) {
    }

    private final TenantDirectory tenants;
    private final PlaceStore places;
    private final ShipmentStore shipments;
    private final DeviceMappingService mapping;
    private final DemoAccounts demoAccounts;
    private final TransactionTemplate transaction;
    private final ZoneId zone;

    public ShipmentImportService(TenantDirectory tenants, PlaceStore places, ShipmentStore shipments, DeviceMappingService mapping,
                                 DemoAccounts demoAccounts, TransactionTemplate transaction,
                                 @Value("${gps.tms.time-zone:Asia/Kolkata}") String zone) {
        this.tenants = tenants;
        this.places = places;
        this.shipments = shipments;
        this.mapping = mapping;
        this.demoAccounts = demoAccounts;
        this.transaction = transaction;
        this.zone = ZoneId.of(zone);
    }

    public Report preview(String fileName, byte[] content, PortalUser user) {
        return run(fileName, content, user, false, false, "FILE");
    }

    /** @param applyMappings GPS provider only: fit the file's IMEI in its truck where the truck has no device yet */
    public Report commit(String fileName, byte[] content, PortalUser user, boolean applyMappings) {
        return run(fileName, content, user, true, applyMappings && user.developer(), "FILE");
    }

    /** The demo loader's import: rows built from the smart_truck trips the simulator is driving. */
    Report commitRows(String name, List<List<String>> rows, PortalUser user, String source) {
        StringBuilder text = new StringBuilder();
        rows.forEach(r -> text.append(String.join("\u0001", r.stream().map(v -> v == null ? "" : v).toList())).append('\n'));
        return run(name, rows, sha256(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "SMART_TRUCK", user, true, false, source);
    }

    private Report run(String fileName, byte[] content, PortalUser user, boolean commit, boolean applyMappings, String source) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The file is empty");
        }
        ShipmentFileReader.Sheet2D sheet = ShipmentFileReader.read(fileName, content);
        return run(fileName, sheet.rows(), sha256(content), sheet.format(), user, commit, applyMappings, source);
    }

    private Report run(String fileName, List<List<String>> rows, String sha, String format, PortalUser user, boolean commit,
                       boolean applyMappings, String source) {
        if (user.role() != Role.DEVELOPER && user.tenantCode() == null) {
            throw new IllegalArgumentException("Your user has no company");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no rows");
        }
        Instant importedBefore = shipments.importedBefore(sha).orElse(null);
        Mapping columns = ShipmentColumns.detect(rows.getFirst());
        List<String> header = rows.getFirst();
        List<List<String>> data = columns.headerRow() ? rows.subList(1, rows.size()) : rows;
        List<String> missing = columns.missingRequired().stream().map(f -> f.columnName()).toList();
        if (!missing.isEmpty()) {
            return new Report(null, fileName, format, columns.layout(), columns.describe(header), columns.ignored(), missing,
                    data.size(), 0, data.size(), 0, 0, 0, 0, List.of(new RowProblem(columns.headerRow() ? 1 : 0, null,
                    "No column for " + String.join(", ", missing) + " - name the header like the template", true)),
                    Map.of(), List.of(), List.of(), importedBefore, List.of(), false);
        }

        Company uploader = user.tenantCode() == null ? null : tenants.byCode(user.tenantCode()).orElse(null);
        List<ShipmentDraft> drafts = new ArrayList<>();
        int firstDataRow = columns.headerRow() ? 2 : 1;
        for (int i = 0; i < data.size(); i++) {
            drafts.add(ShipmentDraft.parse(firstDataRow + i, data.get(i), columns.columns(), zone));
        }

        Imported result = commit
                ? transaction.execute(status -> importDrafts(drafts, user, uploader, fileName, sha, format, columns, header, source, true, applyMappings))
                : importDrafts(drafts, user, uploader, fileName, sha, format, columns, header, source, false, false);
        if (commit && result.created + result.updated > 0) {
            try {
                demoAccounts.ensureTransporter();
            } catch (RuntimeException notNeeded) {
                LOG.debug("Demo transporter sign-in not created: {}", notNeeded.getMessage());
            }
        }
        List<Map<String, Object>> sample = drafts.stream().filter(ShipmentDraft::valid).limit(15).map(ShipmentImportService::sample).toList();
        return new Report(result.importId, fileName, format, columns.layout(), columns.describe(header), columns.ignored(), List.of(),
                drafts.size(), result.valid, drafts.size() - result.valid, result.created, result.updated, result.unchanged,
                result.closed, result.problems.stream().limit(PROBLEMS_SHOWN).toList(), result.unknownPlaces, result.newCompanies,
                result.hints.stream().limit(PROBLEMS_SHOWN).toList(), importedBefore, sample, commit);
    }

    private static final class Imported {
        Long importId;
        int valid;
        int created;
        int updated;
        int unchanged;
        int closed;
        final List<RowProblem> problems = new ArrayList<>();
        final Map<String, Integer> unknownPlaces = new TreeMap<>();
        final List<String> newCompanies = new ArrayList<>();
        final List<MappingHint> hints = new ArrayList<>();
    }

    private Imported importDrafts(List<ShipmentDraft> drafts, PortalUser user, Company uploader, String fileName, String sha, String format,
                                  Mapping columns, List<String> header, String source, boolean write, boolean applyMappings) {
        Imported out = new Imported();
        if (write) {
            out.importId = shipments.createImport(fileName, sha, format, user.username(), user.role().name(), uploader == null ? null : uploader.id());
        }
        Map<String, String> fittedByImei = mapping.currentVehicleByImei();
        Map<String, String> imeiByVehicle = new LinkedHashMap<>();
        fittedByImei.forEach((imei, vehicle) -> imeiByVehicle.put(vehicle, imei));
        Map<String, Company> companyCache = new LinkedHashMap<>();
        Map<UUID, Map<String, String>> hashesByTransporter = new LinkedHashMap<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        java.util.Set<String> hinted = new java.util.HashSet<>();

        for (ShipmentDraft d : drafts) {
            d.warnings().forEach(w -> out.problems.add(new RowProblem(d.rowNumber(), d.tripNo(), w, false)));
            if (!d.valid()) {
                d.problems().forEach(p -> out.problems.add(new RowProblem(d.rowNumber(), d.tripNo(), p, true)));
                continue;
            }
            // who carries it and who sends it
            Company transporter;
            if (user.role() == Role.TRANSPORTER) {
                transporter = uploader;
                if (d.transporterName() != null && !TenantDirectory.blankToNull(d.transporterName()).equalsIgnoreCase(uploader.name())
                        && (d.transporterCode() == null || !d.transporterCode().equals(uploader.externalCode()))) {
                    out.problems.add(new RowProblem(d.rowNumber(), d.tripNo(), "The row names transporter " + d.transporterName()
                            + "; imported as yours", false));
                }
            } else if (d.transporterName() == null && d.transporterCode() == null) {
                out.problems.add(new RowProblem(d.rowNumber(), d.tripNo(), "No transporter (s_trans_name / i_trans_id)", true));
                continue;
            } else {
                transporter = company(Kind.TRANSPORTER, d.transporterName(), d.transporterCode(), write, companyCache, out);
            }
            Company consigner = user.role() == Role.CONSIGNER ? uploader
                    : d.consignerUnit() == null && d.consignerCode() == null ? null
                    : company(Kind.CONSIGNER, consignerCompanyName(d.consignerUnit()), d.consignerCode(), write, companyCache, out);

            String key = (transporter == null ? "new:" + d.transporterName() : transporter.id()) + "|" + d.tripNo();
            if (!seenKeys.add(key)) {
                out.problems.add(new RowProblem(d.rowNumber(), d.tripNo(), "Trip " + d.tripNo() + " is in the file twice; the later row wins", false));
            }
            out.valid++;

            Optional<PlaceStore.Place> origin = places.resolve(d.origin(), PlaceStore.Role.ORIGIN)
                    .or(() -> places.resolve(d.loadPlant(), PlaceStore.Role.ORIGIN));
            Optional<PlaceStore.Place> destination = places.resolve(d.destination(), PlaceStore.Role.DESTINATION)
                    .or(() -> places.resolve(d.consignee(), PlaceStore.Role.DESTINATION));
            if (origin.isEmpty() && d.origin() != null) {
                out.unknownPlaces.merge("origin " + d.origin(), 1, Integer::sum);
            }
            if (destination.isEmpty() && d.destination() != null) {
                out.unknownPlaces.merge("destination " + d.destination(), 1, Integer::sum);
            }
            hint(d, fittedByImei, imeiByVehicle, hinted, out, write && applyMappings);

            if (!write) {
                if (transporter == null) {
                    out.created++;
                } else {
                    String stored = hashesByTransporter.computeIfAbsent(transporter.id(), t -> new LinkedHashMap<>())
                            .computeIfAbsent(d.tripNo(), trip -> shipments.hashes(transporter.id(), List.of(trip)).get(trip));
                    if (stored == null) {
                        out.created++;
                    } else if (stored.equals(d.hash())) {
                        out.unchanged++;
                    } else {
                        out.updated++;
                    }
                }
                continue;
            }
            mapping.registerVehicle(d.vehicleNo(), null);
            Optional<Map.Entry<Long, ShipmentStore.Outcome>> written = shipments.upsert(d, source, transporter.id(),
                    consigner == null ? null : consigner.id(), origin.map(PlaceStore.Place::id).orElse(null),
                    destination.map(PlaceStore.Place::id).orElse(null), out.importId);
            if (written.isEmpty()) {
                out.unchanged++;
                continue;
            }
            if (written.get().getValue() == ShipmentStore.Outcome.CREATED) {
                out.created++;
            } else {
                out.updated++;
            }
            out.closed += shipments.closeEarlierTripsOfVehicle(d.vehicleNo(), d.dispatchedAt(), written.get().getKey());
        }
        if (write) {
            shipments.finishImport(out.importId, drafts.size(), out.created, out.updated, out.unchanged, drafts.size() - out.valid,
                    JSON.writeValueAsString(out.problems.stream().limit(PROBLEMS_SHOWN).toList()),
                    JSON.writeValueAsString(columns.describe(header)));
            LOG.info("Shipment import {} ({}, by {} {}): {} rows, {} new, {} changed, {} unchanged, {} refused, {} closed by next upload",
                    out.importId, fileName, user.role(), user.username(), drafts.size(), out.created, out.updated, out.unchanged,
                    drafts.size() - out.valid, out.closed);
        }
        return out;
    }

    /** The file's IMEI checked against the device mapped to the truck - the GPS provider fixes the mapping from this. */
    private void hint(ShipmentDraft d, Map<String, String> fittedByImei, Map<String, String> imeiByVehicle, java.util.Set<String> hinted,
                      Imported out, boolean apply) {
        String mapped = imeiByVehicle.get(d.vehicleNo());
        String fileImei = d.deviceImei();
        String state;
        if (fileImei == null) {
            state = mapped == null ? "NO_DEVICE" : "MAPPED";
        } else if (mapped != null && sameImei(mapped, fileImei)) {
            state = "MAPPED";
        } else if (mapped != null) {
            state = "DIFFERENT_DEVICE";
        } else {
            String fittedElsewhere = fittedByImei.entrySet().stream().filter(e -> sameImei(e.getKey(), fileImei))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            state = fittedElsewhere != null ? "DEVICE_IN_OTHER_TRUCK" : "UNMAPPED";
            if (apply && fittedElsewhere == null) {
                try {
                    mapping.registerDevice(fileImei, "GPS", null);
                    mapping.assign(fileImei, d.vehicleNo(), d.dispatchedAt().isBefore(Instant.now()) ? d.dispatchedAt() : Instant.now());
                    imeiByVehicle.put(d.vehicleNo(), fileImei);
                    fittedByImei.put(fileImei, d.vehicleNo());
                    state = "MAPPED_FROM_FILE";
                } catch (IllegalArgumentException notPossible) {
                    state = "UNMAPPED";
                }
            }
        }
        if (!"MAPPED".equals(state) && hinted.add(d.vehicleNo())) {
            out.hints.add(new MappingHint(d.vehicleNo(), fileImei, mapped, state));
        }
    }

    private Company company(Kind kind, String name, String code, boolean write, Map<String, Company> cache, Imported out) {
        String key = kind + "|" + (code != null ? "#" + code : String.valueOf(name).toUpperCase());
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Company found = write ? tenants.findOrCreate(kind, name, code) : tenants.find(kind, name, code).orElse(null);
        if (found == null && !out.newCompanies.contains(kind.name().toLowerCase() + " " + name)) {
            out.newCompanies.add(kind.name().toLowerCase() + " " + (name != null ? name : code));
        }
        cache.put(key, found);
        return found;
    }

    /** "TATA STEEL-JSR", "TATA STEEL- BARA" -> "TATA STEEL"; a name without a unit stays as it is. */
    static String consignerCompanyName(String consignorName) {
        if (consignorName == null) {
            return null;
        }
        int dash = consignorName.indexOf('-');
        String company = dash >= 3 ? consignorName.substring(0, dash).trim() : consignorName.trim();
        return company.isEmpty() ? consignorName.trim() : company;
    }

    static boolean sameImei(String a, String b) {
        return a.replaceFirst("^0+", "").equals(b.replaceFirst("^0+", ""));
    }

    private static Map<String, Object> sample(ShipmentDraft d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("row", d.rowNumber());
        row.put("tripNo", d.tripNo());
        row.put("vehicleNo", d.vehicleNo());
        row.put("imei", d.deviceImei());
        row.put("transporter", d.transporterName());
        row.put("consigner", d.consignerUnit());
        row.put("consignee", d.consignee());
        row.put("origin", d.origin());
        row.put("destination", d.destination());
        row.put("dispatchedAt", d.dispatchedAt());
        row.put("etaAt", d.etaAt());
        row.put("status", d.status());
        return row;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
