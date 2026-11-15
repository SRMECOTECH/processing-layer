package com.srm.gps.application.tms;

import com.srm.gps.application.auth.AccountStore;
import com.srm.gps.application.auth.PortalUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What only the GPS provider (role DEVELOPER) changes: companies and their users, devices, trucks, the device <->
 * truck mapping, the geofences, and the demo shipments.
 *
 *   /api/portal/admin/users            GET list, POST create; /{id}/password, /{id}/active
 *   /api/portal/admin/companies        GET list, POST create
 *   /api/portal/admin/devices          GET with their truck and last reading, POST register an IMEI
 *   /api/portal/admin/vehicles         GET with their device, POST add a truck
 *   /api/portal/admin/mappings         POST fit a device in a truck, POST /unassign, GET /history
 *   /api/portal/admin/places           GET, POST (name, role, position, radius), DELETE /{id}
 *   /api/portal/admin/demo-shipments   POST: the shipments behind the trips the simulator drives
 */
@RestController
@RequestMapping("/api/portal/admin")
public class PortalAdminController {

    public record NewUser(String role, String username, String displayName, String password, String tenantCode) {
    }

    public record NewCompany(String kind, String name, String externalCode) {
    }

    public record NewDevice(String imei, String kind, String protocol) {
    }

    public record NewVehicle(String registrationNo, String ownerCode) {
    }

    public record MappingRequest(String imei, String vehicleNo, Instant from) {
    }

    public record NewPlace(String name, String role, double latitude, double longitude, int radiusMeters) {
    }

    private final AccountStore accounts;
    private final TenantDirectory tenants;
    private final DeviceMappingService mapping;
    private final PlaceStore places;
    private final DemoShipmentLoader demo;

    public PortalAdminController(AccountStore accounts, TenantDirectory tenants, DeviceMappingService mapping, PlaceStore places,
                                 DemoShipmentLoader demo) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.mapping = mapping;
        this.places = places;
        this.demo = demo;
    }

    @GetMapping("/users")
    public List<AccountStore.AccountView> users(HttpServletRequest request) {
        developer(request);
        return accounts.list();
    }

    @PostMapping("/users")
    public AccountStore.AccountView createUser(HttpServletRequest request, @RequestBody NewUser body) {
        developer(request);
        return accounts.create(PortalUser.Role.valueOf(String.valueOf(body.role()).toUpperCase()), body.username(), body.displayName(),
                body.password(), body.tenantCode());
    }

    @PostMapping("/users/{id}/password")
    public Map<String, String> password(HttpServletRequest request, @PathVariable UUID id, @RequestBody Map<String, String> body) {
        developer(request);
        accounts.setPassword(id, body.get("password"));
        return Map.of("result", "Password changed");
    }

    @PostMapping("/users/{id}/active")
    public Map<String, Object> active(HttpServletRequest request, @PathVariable UUID id, @RequestParam boolean value) {
        PortalUser user = developer(request);
        if (!value && id.toString().equals(user.userId())) {
            throw new IllegalArgumentException("You cannot switch off your own user");
        }
        accounts.setActive(id, value);
        return Map.of("active", value);
    }

    @GetMapping("/companies")
    public List<TenantDirectory.Company> companies(HttpServletRequest request) {
        developer(request);
        return tenants.list(null);
    }

    @PostMapping("/companies")
    public TenantDirectory.Company createCompany(HttpServletRequest request, @RequestBody NewCompany body) {
        developer(request);
        TenantDirectory.Kind kind = TenantDirectory.Kind.valueOf(String.valueOf(body.kind()).toUpperCase());
        if (kind == TenantDirectory.Kind.GPS_PROVIDER || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("A company is a transporter or a consigner, with a name");
        }
        return tenants.findOrCreate(kind, body.name(), body.externalCode());
    }

    @GetMapping("/devices")
    public List<DeviceMappingService.DeviceRow> devices(HttpServletRequest request) {
        developer(request);
        return mapping.devices();
    }

    @PostMapping("/devices")
    public Map<String, String> registerDevice(HttpServletRequest request, @RequestBody NewDevice body) {
        developer(request);
        mapping.registerDevice(body.imei(), body.kind(), body.protocol());
        return Map.of("imei", body.imei().trim());
    }

    @GetMapping("/vehicles")
    public List<DeviceMappingService.VehicleRow> vehicles(HttpServletRequest request, @RequestParam(required = false) String owner) {
        developer(request);
        return mapping.vehicles(owner == null || owner.isBlank() ? null : owner);
    }

    @PostMapping("/vehicles")
    public Map<String, String> registerVehicle(HttpServletRequest request, @RequestBody NewVehicle body) {
        developer(request);
        mapping.registerVehicle(body.registrationNo(), body.ownerCode());
        return Map.of("registrationNo", ShipmentDraft.vehicleNo(body.registrationNo()));
    }

    @PostMapping("/mappings")
    public DeviceMappingService.Change assign(HttpServletRequest request, @RequestBody MappingRequest body) {
        developer(request);
        return mapping.assign(body.imei(), body.vehicleNo(), body.from());
    }

    @PostMapping("/mappings/unassign")
    public DeviceMappingService.Change unassign(HttpServletRequest request, @RequestBody MappingRequest body) {
        developer(request);
        return mapping.unassign(body.imei(), body.from());
    }

    @GetMapping("/mappings/history")
    public List<DeviceMappingService.AssignmentRow> history(HttpServletRequest request, @RequestParam(required = false) String imei,
                                                            @RequestParam(required = false) String vehicle) {
        developer(request);
        return mapping.history(blank(imei), vehicle == null || vehicle.isBlank() ? null : ShipmentDraft.vehicleNo(vehicle));
    }

    @GetMapping("/places")
    public List<PlaceStore.Place> places(HttpServletRequest request) {
        developer(request);
        return places.list();
    }

    @PostMapping("/places")
    public PlaceStore.Place savePlace(HttpServletRequest request, @RequestBody NewPlace body) {
        developer(request);
        return places.save(body.name(), PlaceStore.Role.valueOf(String.valueOf(body.role()).toUpperCase()), body.latitude(),
                body.longitude(), body.radiusMeters());
    }

    @DeleteMapping("/places/{id}")
    public Map<String, Object> deletePlace(HttpServletRequest request, @PathVariable long id) {
        developer(request);
        places.deactivate(id);
        return Map.of("id", id, "active", false);
    }

    @PostMapping("/demo-shipments")
    public ShipmentImportService.Report demoShipments(HttpServletRequest request) {
        return demo.load(developer(request));
    }

    private static PortalUser developer(HttpServletRequest request) {
        PortalUser user = PortalUser.of(request);
        user.requireDeveloper();
        return user;
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.trim();
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

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(org.springframework.dao.DataIntegrityViolationException problem) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "The database refused it: "
                + problem.getMostSpecificCause().getMessage()));
    }
}
