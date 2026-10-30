package com.srm.gps.application.tms;

import com.srm.gps.application.auth.PortalUser;
import com.srm.gps.application.tms.ShipmentColumns.Field;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The portals of the three kinds of user, behind the sign-in token (PortalAuthFilter). Every answer is cut to the
 * user's scope: a transporter or consigner never sees another company's data, whatever it puts in the URL; the GPS
 * provider may narrow with ?transporter= / ?consigner= (company codes).
 *
 *   GET  /api/portal/overview                     the role's KPIs
 *   GET  /api/portal/shipments?q=&days=           shipments with stage, verdict, ETA estimate
 *   GET  /api/portal/shipments/{id}               one shipment: its track, events and timeline
 *   POST /api/portal/shipments/import/preview     a file (raw body, ?fileName=): what an import would do, nothing written
 *   POST /api/portal/shipments/import             the same file, imported (idempotent)
 *   GET  /api/portal/shipments/imports            upload history
 *   GET  /api/portal/shipments/template           an empty CSV in the tta_trips columns
 *   GET  /api/portal/fleet                        the transporter's trucks, live
 *   GET  /api/portal/alerts                       events the user may see
 */
@RestController
@RequestMapping("/api/portal")
public class PortalController {

    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final PortalService portal;
    private final ShipmentImportService importer;
    private final ShipmentStore shipments;
    private final TenantDirectory tenants;

    public PortalController(PortalService portal, ShipmentImportService importer, ShipmentStore shipments, TenantDirectory tenants) {
        this.portal = portal;
        this.importer = importer;
        this.shipments = shipments;
        this.tenants = tenants;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request, @RequestParam(required = false) String transporter,
                                        @RequestParam(required = false) String consigner) {
        PortalUser user = PortalUser.of(request);
        return portal.overview(user, portal.scope(user, transporter, consigner));
    }

    @GetMapping("/shipments")
    public List<ShipmentView> shipments(HttpServletRequest request, @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "500") int limit,
                                        @RequestParam(required = false) String transporter, @RequestParam(required = false) String consigner) {
        PortalUser user = PortalUser.of(request);
        return portal.shipments(portal.scope(user, transporter, consigner), q, days, Math.clamp(limit, 1, 5_000));
    }

    @GetMapping("/shipments/{id}")
    public Map<String, Object> shipment(HttpServletRequest request, @PathVariable long id) {
        PortalUser user = PortalUser.of(request);
        return portal.shipment(portal.scope(user, null, null), id);
    }

    @PostMapping(value = "/shipments/import/preview", consumes = MediaType.ALL_VALUE)
    public ShipmentImportService.Report preview(HttpServletRequest request, @RequestParam(defaultValue = "upload") String fileName,
                                                @RequestBody byte[] content) {
        return importer.preview(fileName, limited(content), PortalUser.of(request));
    }

    @PostMapping(value = "/shipments/import", consumes = MediaType.ALL_VALUE)
    public ShipmentImportService.Report importFile(HttpServletRequest request, @RequestParam(defaultValue = "upload") String fileName,
                                                   @RequestParam(defaultValue = "false") boolean applyMappings, @RequestBody byte[] content) {
        return importer.commit(fileName, limited(content), PortalUser.of(request), applyMappings);
    }

    @GetMapping("/shipments/imports")
    public List<Map<String, Object>> imports(HttpServletRequest request) {
        return shipments.imports(portal.scope(PortalUser.of(request), null, null), 50);
    }

    /** An empty file with the smart_truck tta_trips columns the importer reads (any of the names in ShipmentColumns work). */
    @GetMapping("/shipments/template")
    public ResponseEntity<byte[]> template() {
        String header = Arrays.stream(Field.values()).map(Field::columnName).collect(Collectors.joining(","));
        String example = "754710,540,local,TATA STEEL-JSR,JH05CY2516,0869925072822461,TRAILER,,LOCAL TRIP,JAMSHEDPUR,ADITYAPUR,"
                + "\"TATA STEEL PROCESSING (ASOKA SKY ME, JAMSHEDPUR\",LD3&TSCR Plant,2026-08-03 04:31:10,2026-08-03 11:20:43,"
                + "2026-08-04 11:20:43,,,0000015300,Virk Eenterprises,TATA STEEL PROCESSING (ASOKA SKY ME,Open,,2314959959,9FE9E1BB,,,"
                + "ASH0K KUMAR YADAV,8229835350,,HR Coil,25.5";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shipments-template.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body((header + "\n" + example + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/fleet")
    public List<Map<String, Object>> fleet(HttpServletRequest request, @RequestParam(required = false) String transporter) {
        PortalUser user = PortalUser.of(request);
        return portal.fleet(portal.scope(user, transporter, null));
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(HttpServletRequest request, @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String transporter, @RequestParam(required = false) String consigner) {
        PortalUser user = PortalUser.of(request);
        return portal.alerts(portal.scope(user, transporter, consigner), Math.clamp(limit, 1, 1_000));
    }

    /** Companies for the pickers: the GPS provider sees every one, anyone else only their own. */
    @GetMapping("/companies")
    public List<TenantDirectory.Company> companies(HttpServletRequest request, @RequestParam(required = false) String kind) {
        PortalUser user = PortalUser.of(request);
        if (!user.developer()) {
            return tenants.byCode(user.tenantCode()).stream().toList();
        }
        return tenants.list(kind == null || kind.isBlank() ? null : TenantDirectory.Kind.valueOf(kind.trim().toUpperCase()));
    }

    private static byte[] limited(byte[] content) {
        if (content != null && content.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("The file is larger than 10 MB; split it");
        }
        return content;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException problem) {
        return ResponseEntity.badRequest().body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> notPossible(IllegalStateException problem) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(PortalUser.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(PortalUser.ForbiddenException problem) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", problem.getMessage()));
    }

    @ExceptionHandler(PortalUser.NotSignedInException.class)
    public ResponseEntity<Map<String, String>> notSignedIn(PortalUser.NotSignedInException problem) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", problem.getMessage()));
    }
}
