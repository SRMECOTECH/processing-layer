package com.srm.gps.application.kafkastreams;

import com.srm.gps.application.kafkastreams.KafkaStreamsQueryService.StreamsUnavailableException;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.FleetWindow;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.ParityView;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.StreamsOverview;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.VehicleParity;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.VehicleView;
import com.srm.gps.application.kafkastreams.KafkaStreamsViews.WindowShare;
import com.srm.gps.configuration.ProcessingFlowNames;
import com.srm.gps.streams.analytics.VehicleSnapshot;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The Kafka Streams layer, read through Interactive Queries.
 *
 *   GET /api/streams/overview                apps, state, topology, threads and tasks, store lag, restorations, metrics
 *   GET /api/streams/vehicles?limit=200      the vehicle KTable (every instance's share)
 *   GET /api/streams/vehicles/{imei}         one vehicle: state, owning partition, standbys, its activity windows
 *   GET /api/streams/fleet/activity?windows= the fleet per 5-minute window, summed over all vehicles
 *   GET /api/streams/parity?limit=25         shadow mode: the listener against Kafka Streams, reading by reading
 *
 *   GET /api/streams/local/...               the same, for this instance only - what instances ask each other
 *
 * While an app starts, rebalances or restores its stores, queries answer 503 with the reason.
 */
@RestController
@RequestMapping("/api/streams")
public class KafkaStreamsController {

    private final ObjectProvider<KafkaStreamsQueryService> queries;
    private final String processingFlow;
    private final boolean enabled;

    public KafkaStreamsController(ObjectProvider<KafkaStreamsQueryService> queries,
                                  @Value("${" + ProcessingFlowNames.PROPERTY + ":" + ProcessingFlowNames.KAFKA + "}") String processingFlow,
                                  @Value("${gps.streams.enabled:true}") boolean enabled) {
        this.queries = queries;
        this.processingFlow = processingFlow;
        this.enabled = enabled;
    }

    @GetMapping("/overview")
    public StreamsOverview overview() {
        KafkaStreamsQueryService service = queries.getIfAvailable();
        if (service != null) {
            return service.overview();
        }
        return StreamsOverview.disabled(!ProcessingFlowNames.KAFKA.equals(processingFlow)
                ? "Direct flow: Kafka, and with it Kafka Streams, is not used"
                : enabled ? "Kafka Streams is not running" : "Kafka Streams is switched off (gps.streams.enabled=false)");
    }

    @GetMapping("/vehicles")
    public List<VehicleSnapshot> vehicles(@RequestParam(defaultValue = "200") int limit) {
        return service().vehicles(Math.clamp(limit, 1, 5_000));
    }

    @GetMapping("/vehicles/{imei}")
    public VehicleView vehicle(@PathVariable String imei, @RequestParam(defaultValue = "12") int windows) {
        return service().vehicle(imei, Math.clamp(windows, 1, 200));
    }

    @GetMapping("/fleet/activity")
    public List<FleetWindow> fleetActivity(@RequestParam(defaultValue = "12") int windows) {
        return service().fleetActivity(Math.clamp(windows, 1, 200));
    }

    @GetMapping("/parity")
    public ParityView parity(@RequestParam(defaultValue = "25") int limit) {
        return service().parity(Math.clamp(limit, 1, 5_000));
    }

    @GetMapping("/local/vehicles")
    public List<VehicleSnapshot> localVehicles(@RequestParam(defaultValue = "200") int limit) {
        return service().localVehicles(Math.clamp(limit, 1, 5_000));
    }

    @GetMapping("/local/vehicles/{imei}")
    public VehicleView localVehicle(@PathVariable String imei, @RequestParam(defaultValue = "12") int windows) {
        return service().localVehicle(imei, Math.clamp(windows, 1, 200));
    }

    @GetMapping("/local/fleet/activity")
    public List<WindowShare> localFleetActivity() {
        return service().localWindowShares();
    }

    @GetMapping("/local/parity")
    public List<VehicleParity> localParity() {
        return service().localParity();
    }

    @ExceptionHandler({StreamsUnavailableException.class, InvalidStateStoreException.class})
    public ResponseEntity<Map<String, String>> unavailable(RuntimeException problem) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", problem.getMessage()));
    }

    private KafkaStreamsQueryService service() {
        KafkaStreamsQueryService service = queries.getIfAvailable();
        if (service == null) {
            throw new StreamsUnavailableException(overview().reason());
        }
        return service;
    }
}
