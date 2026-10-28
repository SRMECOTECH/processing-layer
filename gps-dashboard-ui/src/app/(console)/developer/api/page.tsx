"use client";

import { Braces } from "@/components/icons";
import { Card, Code, PageHeader, Tag } from "@/components/ops/kit";

const ENDPOINTS: [string, string, string, string][] = [
  ["GET", "/api/engine/overview", "Producers, consumers, lag, topics, storage counts, simulator", "Every page (shared 2 s poll)"],
  ["GET", "/api/engine/devices?imei=", "Devices seen and their partition", "Vehicle list, live map"],
  ["GET", "/api/engine/devices/{imei}", "One device: load per stage and its Redis live position", "Vehicle list, live map"],
  ["GET", "/api/vehicles/states?limit=", "Vehicle-state hash of every vehicle", "State store, Kalman, master lookup"],
  ["GET", "/api/vehicles/{imei}/state?history=", "Everything kept for one vehicle: cache, history, events", "Vehicle inspector, historical context"],
  ["GET", "/api/speed/rules", "All speed rules in Redis", "Entity rules, cross-check"],
  ["PUT", "/api/speed/rules/{scope}", "Create or replace a rule", "Entity rules"],
  ["DELETE", "/api/speed/rules/{scope}", "Remove a rule (not default)", "Entity rules"],
  ["GET", "/api/speed/active", "Vehicles over their limit now", "Live episodes, cross-check"],
  ["GET", "/api/events?type=&imei=&status=&limit=", "Vehicle events, newest first", "Alerts, event stream, processors"],
  ["GET", "/api/events/summary", "Count per type: last 24 h and open now", "Overview tiles"],
  ["GET", "/api/trips?imei=&from=&to=&limit=", "Trips overlapping a period", "Trips, trip increments"],
  ["GET", "/api/stops?imei=&from=&to=&limit=", "Stops overlapping a period", "Stops"],
  ["GET", "/api/trips/count", "Open and closed trips and stops", "Overview tiles"],
  ["GET", "/api/gps-positions/recently-stored", "Last 20 stored positions", "Device log audit"],
  ["GET", "/api/gps-positions/count", "Stored positions", "–"],
  ["GET", "/api/streams/overview", "Kafka Streams apps: state, topology, threads and tasks, stores, restorations, metrics", "Kafka Streams · topology & tasks"],
  ["GET", "/api/streams/vehicles?limit=", "The vehicle KTable through Interactive Queries (every instance's share)", "Kafka Streams · vehicle table"],
  ["GET", "/api/streams/vehicles/{imei}?windows=", "One vehicle: state, owning partition, standbys, activity windows", "Kafka Streams · vehicle table"],
  ["GET", "/api/streams/fleet/activity?windows=", "The fleet per 5-minute event-time window", "Kafka Streams · activity windows"],
  ["GET", "/api/streams/parity?limit=", "Shadow mode: the listener against Kafka Streams, per vehicle", "Kafka Streams · shadow parity"],
  ["GET", "/api/data-safety", "Every no-data-loss check: retention, copies, consumer positions, journal, dead letters", "Data safety"],
  ["GET", "/api/dead-letters?status=&topic=&limit=", "Parked records, newest first", "Dead letters"],
  ["GET", "/api/dead-letters/summary", "Parked, replayed, discarded; by topic and by exception", "Dead letters"],
  ["GET", "/api/dead-letters/{id}", "One dead letter with its record and stack trace", "Dead letters"],
  ["POST", "/api/dead-letters/{id}/replay", "Send it to its original topic again (confirmed by Kafka)", "Dead letters"],
  ["POST", "/api/dead-letters/replay?topic=&limit=", "Replay the oldest parked ones", "Dead letters"],
  ["POST", "/api/dead-letters/{id}/discard?note=", "Mark it not to be replayed, with the reason (kept)", "Dead letters"],
  ["GET", "/api/rejected-frames?imei=&limit=", "Archived rejected frames with their payload and reason", "Rejected frames"],
  ["GET", "/api/rejected-frames/summary", "Rejected frames by reason, last 24 h and all time", "Rejected frames"],
  ["POST", "/api/simulator/start", "Start the simulated fleet", "Fleet simulator"],
  ["POST", "/api/simulator/stop", "Stop the simulated fleet", "Fleet simulator"],
  ["POST", "/api/admin/reset?restartFleet=&keepReports=", "Empty Kafka, PostgreSQL and Redis, rebuild Kafka Streams state; reports kept unless keepReports=false", "Demo tools, Clear database"],
  ["GET", "/api/connections", "Redis, Valhalla and the HP ingestion API: where each points, each checked now", "Connections"],
  ["POST", "/api/connections/redis/test", "Check a Redis without using it: ping, memory, eviction policy, its database", "Connections"],
  ["PUT", "/api/connections/redis", "Move the service to another Redis at once (speed rules copied)", "Connections"],
  ["POST", "/api/connections/valhalla/test", "Valhalla /status and a short test route", "Connections"],
  ["PUT", "/api/connections/valhalla", "Save the routing engine's address", "Connections"],
  ["POST", "/api/connections/ingestion-api/test", "The HP ingestion API's /health (read-only)", "Connections"],
  ["GET", "/api/reports/filters", "Companies, vehicles and today, for the report drop-downs", "Reports"],
  ["GET", "/api/reports/day?date=&tenant=&imei=", "Every vehicle on one day: distance, stops, idling, fuel, cost, could-save", "Day report"],
  ["GET", "/api/reports/month?month=&tenant=&imei=", "Every vehicle over a month, and the fleet per day", "Month report, Fuel & savings"],
  ["GET", "/api/reports/vehicle-day?imei=&date=", "One vehicle's trips, stops and events in time order", "Vehicle day sheet"],
  ["POST", "/api/reports/refresh?date=", "Rebuild a day's summaries now (otherwise every 5 minutes)", "Day report"],
  ["GET", "/api/reports/assumptions", "Fuel price, mileage, idle burn, economical speed behind the estimates", "Cost assumptions"],
  ["PUT", "/api/reports/assumptions", "Change them; every report is priced again at once", "Cost assumptions"],
];

const TONE: Record<string, "good" | "accent" | "warn" | "bad"> = { GET: "good", PUT: "accent", POST: "warn", DELETE: "bad" };

export default function ApiReferencePage() {
  return (
    <div className="stack">
      <PageHeader
        title="API reference"
        description="Every endpoint of the GPS service this console calls. The browser only talks to the console; /api/* is forwarded to GPS_API_URL (default http://localhost:8080)."
      />
      <Card icon={Braces} title="Endpoints" subtitle={`${ENDPOINTS.length} endpoints`} flush>
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Method</th>
                <th>Path</th>
                <th>Returns</th>
                <th>Used by</th>
              </tr>
            </thead>
            <tbody>
              {ENDPOINTS.map(([method, path, returns, usedBy]) => (
                <tr key={`${method} ${path}`}>
                  <td>
                    <Tag tone={TONE[method]} mono>
                      {method}
                    </Tag>
                  </td>
                  <td>
                    <Code>{path}</Code>
                  </td>
                  <td className="wrap">{returns}</td>
                  <td className="muted wrap">{usedBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}
