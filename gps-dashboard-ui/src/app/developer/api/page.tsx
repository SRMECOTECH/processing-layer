"use client";

import { Braces } from "lucide-react";
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
  ["POST", "/api/simulator/start", "Start the simulated fleet", "Fleet simulator"],
  ["POST", "/api/simulator/stop", "Stop the simulated fleet", "Fleet simulator"],
  ["POST", "/api/admin/reset?restartFleet=", "Empty Kafka, PostgreSQL and Redis (demo only)", "Demo tools"],
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
