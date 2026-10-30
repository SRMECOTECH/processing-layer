# Plugging a processing module into the GPS platform

This guide is for whoever writes the next module after Kafka: speed violation, nested geofencing, waypoints,
route deviation, or something nobody has thought of yet. A module is **one interface and one config line**.
The platform does the rest.

## What you write

```java
@Component
public class GeofenceModule implements GpsModule {

    private static final ModuleDescriptor DESCRIPTOR = new ModuleDescriptor(
            "geofence",                                   // id: gps.modules.geofence.enabled, /api/modules/geofence
            "Nested geofencing",                          // shown in the console
            "Entry, exit and dwell per zone, from region down to the loading bay.",
            "gps-geofence-service",                       // its consumer group: NEVER rename it after it has run
            null,                                         // a PipelineStage, or null: load is then counted under the id
            60,                                           // order in the direct flow (ignition 10 ... shipments 50)
            List.of("GEOFENCE_ENTER", "GEOFENCE_EXIT"),   // what it emits (registry only)
            List.of("gps:state:geofence:*"));             // its Redis state, deleted before a replay

    @Override
    public ModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void onPosition(ProcessedGpsPosition position, ModuleContext context) {
        Map<String, String> before = context.state().read();          // gps:state:geofence:{imei}
        GeofenceProcessor.Update update = GeofenceProcessor.update(...); // your pure, unit-tested logic
        for (GeofenceFinding f : update.findings()) {
            context.emit(context.vehicle().event(...));                 // vehicle_event, upserted by key
        }
        context.state().write(update.stateFields());
    }
}
```

Then, in `application.yml` (or as an environment variable):

```yaml
gps:
  modules:
    geofence:
      enabled: true      # false switches it off; nothing else changes
```

The module depends on **`gps-module-api`** only (plus `gps-configuration` for the message records). It has no
Kafka, Redis or SQL code of its own. Put the logic in a plain class next to the existing processors in
`gps-processing` (`speed/`, `stoppage/`, `shipment/` ...) and unit-test it there.

## What the platform gives every module

| You get | How | Where |
|---|---|---|
| Its own copy of every reading | Its own consumer group on `gps.processed-positions` | `ModuleKafkaListeners` |
| One vehicle's readings in order, one at a time | Key = IMEI, one consumer thread per partition (3) | same |
| No data loss when PostgreSQL / Redis / Kafka is down | Retry 1 s, 2 s, 4 s ... then every 60 s, for ever; the offset is not committed, so the reading waits in Kafka | `KafkaRetrySettings` |
| A broken reading never blocks the others | Not JSON or breaks a database rule: straight to `gps.dead-letters`; any other error 3 tries then parked. Archived in PostgreSQL `dead_letter`, replayable from Storage → Dead letters | `KafkaRetrySettings`, `DeadLetterArchive` |
| Replay of old data | `POST /api/modules/{id}/replay?from=2026-10-29T00:00:00Z`: stop the module's consumers, delete its Redis state, call `beforeReplay(from)`, move its bookmark back (Kafka keeps 31 days), start again. Only this module rereads | `ModuleReplayService`, Developer → Module registry |
| The vehicle and company at the time of the reading | `context.vehicle()`, from `device_assignment` periods, so a late reading finds the truck the device was in then | `ModuleRunner`, `DeviceVehicleDirectory` |
| State per device | `context.state()`: Redis hash `gps:state:{id}:{imei}` | `ModuleRunner` |
| Events everyone sees | `context.emit(event)`: `vehicle_event` (one row per episode), Redis recent events, `gps.vehicle-events` | `VehicleEventRecorder` |
| Settings | `context.setting("radius-meters", "100")` reads `gps.modules.{id}.radius-meters` | `ModuleRunner` |
| Load counters, lag, a row in the registry | msg/s, total, failures, lag, running/stopped | `GET /api/modules`, Developer → Module registry |
| The direct flow | Called in `order` inside one method call (no Kafka) | `DirectProcessingFlow` |
| Demo reset | Paused and moved to the end with every other consumer | `KafkaBacklogSkipper` |

## The one rule you must keep: idempotent

The same reading can reach `onPosition` more than once (a redelivery after a crash, a replay), and readings can
arrive late and out of order (a device out of coverage stores readings and sends them all when it is back). So:

- **Events**: always through `context.emit`. The row key is IMEI + type + start, so the same episode found twice is
  one row. Write events **before** state: if anything fails, the reading comes again and finds the same events.
- **State**: keyed by the reading's own time (`recordedAt`), never by when it arrived. Ignore a reading older than
  your state if order matters to you (`position.arrivedOutOfOrder()` tells you), or make the result
  order-independent (the shipment module keeps the **earliest** reading that proves a departure or arrival, so any
  order gives the same answer).
- **Throw** when you could not finish: the platform retries. Never swallow an infrastructure error.

## The modules plugged in today

| id | Consumer group | Order | Emits | Redis state |
|---|---|---|---|---|
| `ignition` | `gps-ignition-service` | 10 | IGNITION_ON / OFF, IDLING | `gps:state:ignition:*` |
| `vehicle-state` | `gps-vehicle-state-service` | 20 | HARSH_ACCELERATION / BRAKING | `gps:state:vehicle:*`, `gps:history:*` |
| `speed-violation` | `gps-speed-violation-service` | 30 | OVERSPEED, SPEED_SENSOR_MISMATCH | `gps:state:speed:*` |
| `stoppage` | `gps-stoppage-service` | 40 | STOPPAGE, PARKED | `gps:state:stoppage:*` |
| `shipment-tracking` | `gps-shipment-tracking-service` | 50 | SHIPMENT_DEPARTED / ARRIVED / DELAYED | none (PostgreSQL `shipment`) |

The first four kept their consumer group names when they became modules, so they continue from their committed
offsets: no replay and no gap. Kafka Streams analytics is not a `GpsModule` (it is a Kafka Streams app with its own
switch, `gps.streams.*`), but it is listed next to them in the console.

`ShipmentTrackingModule` is the worked example: read it first. It shows the descriptor, the context, the pure
function (`ShipmentProgress` in `gps-processing`), events before state, and `beforeReplay`.

## Checklist for a new module

1. A pure class with the logic in `gps-processing/<module>/` and its unit tests (readings twice, in reverse order,
   late).
2. A `GpsModule` bean with a **new** consumer group name (`gps-<module>-service`).
3. `gps.modules.<id>.enabled: true` in `application.yml`.
4. Optional: a `PipelineStage` constant if it should show in the engine dashboard and log lines by stage name.
5. Build, start, and check Developer → Module registry: running, msg/s rising, lag falling to 0.
6. Its entry in `gps-dashboard-ui/src/lib/modules.ts` (the console's description) flipped from `planned` to `live`.
