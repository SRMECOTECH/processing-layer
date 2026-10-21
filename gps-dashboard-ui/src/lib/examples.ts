// Example data for the planned modules (geofencing, waypoints, route deviation, notifications).
// Every card that shows these is labelled "Example data"; replace them with API calls as each module is built.

export type Zone = { id: string; name: string; level: "Region" | "Site" | "Yard" | "Dock"; area: string; inside: number; dwell: string; children?: Zone[] };

export const ZONE_TREE: Zone[] = [
  {
    id: "z1",
    name: "Jamshedpur industrial belt",
    level: "Region",
    area: "86.0 km²",
    inside: 61,
    dwell: "3 h 10 min",
    children: [
      {
        id: "z1a",
        name: "Adityapur industrial area",
        level: "Site",
        area: "9.4 km²",
        inside: 27,
        dwell: "1 h 45 min",
        children: [
          {
            id: "z1a1",
            name: "Gate 2 loading yard",
            level: "Yard",
            area: "0.18 km²",
            inside: 9,
            dwell: "42 min",
            children: [
              { id: "z1a1a", name: "Dock 4B", level: "Dock", area: "1,200 m²", inside: 3, dwell: "18 min" },
              { id: "z1a1b", name: "Dock 5A", level: "Dock", area: "1,050 m²", inside: 1, dwell: "26 min" },
            ],
          },
          { id: "z1a2", name: "Weighbridge", level: "Yard", area: "0.02 km²", inside: 2, dwell: "6 min" },
        ],
      },
      {
        id: "z1b",
        name: "Tata steel works",
        level: "Site",
        area: "21.0 km²",
        inside: 19,
        dwell: "2 h 05 min",
        children: [{ id: "z1b1", name: "Coil dispatch yard", level: "Yard", area: "0.42 km²", inside: 11, dwell: "55 min" }],
      },
    ],
  },
  {
    id: "z2",
    name: "Kolkata port",
    level: "Region",
    area: "12.6 km²",
    inside: 14,
    dwell: "4 h 30 min",
    children: [{ id: "z2a", name: "Container gate 3", level: "Site", area: "0.6 km²", inside: 6, dwell: "1 h 15 min" }],
  },
];

/** Signed distance to each level's boundary: negative inside, positive outside (metres). */
export const NESTED_DISTANCES: { vehicle: string; deepest: string; levels: [string, number][] }[] = [
  { vehicle: "JH05BS0128", deepest: "Dock 4B", levels: [["Region", -4210], ["Site", -812], ["Yard", -96], ["Dock", -8]] },
  { vehicle: "JH05AK4471", deepest: "Gate 2 loading yard", levels: [["Region", -3980], ["Site", -640], ["Yard", -35], ["Dock", 22]] },
  { vehicle: "JH05CJ9012", deepest: "Coil dispatch yard", levels: [["Region", -2550], ["Site", -1430], ["Yard", -120]] },
  { vehicle: "WB23D7781", deepest: "Container gate 3", levels: [["Region", -900], ["Site", -60]] },
  { vehicle: "JH05BX2204", deepest: "Adityapur industrial area", levels: [["Region", -5120], ["Site", -14], ["Yard", 210]] },
  { vehicle: "OD02AF3310", deepest: "outside every zone", levels: [["Region", 18400]] },
];

export const GEOFENCE_EVENTS: { time: string; vehicle: string; type: "ENTER" | "EXIT" | "DWELL"; zone: string; level: string; detail: string }[] = [
  { time: "14:22", vehicle: "JH05BS0128", type: "ENTER", zone: "Dock 4B", level: "Dock", detail: "from Gate 2 loading yard" },
  { time: "14:19", vehicle: "JH05AK4471", type: "DWELL", zone: "Gate 2 loading yard", level: "Yard", detail: "over 40 min (limit 30 min)" },
  { time: "14:11", vehicle: "JH05CJ9012", type: "EXIT", zone: "Weighbridge", level: "Yard", detail: "after 7 min" },
  { time: "14:02", vehicle: "WB23D7781", type: "ENTER", zone: "Container gate 3", level: "Site", detail: "from Kolkata port" },
  { time: "13:58", vehicle: "JH05BX2204", type: "EXIT", zone: "Gate 2 loading yard", level: "Yard", detail: "after 51 min" },
  { time: "13:47", vehicle: "OD02AF3310", type: "EXIT", zone: "Jamshedpur industrial belt", level: "Region", detail: "left every zone" },
];

export type RoutePlan = { trip: string; vehicle: string; route: string; stops: { name: string; planned: string; actual?: string; state: "done" | "late" | "next" | "pending" | "missed" }[] };

export const ROUTE_PLANS: RoutePlan[] = [
  {
    trip: "TRIP-24-1187",
    vehicle: "JH05BS0128",
    route: "Jamshedpur → Kolkata port",
    stops: [
      { name: "Adityapur depot", planned: "06:00", actual: "05:58", state: "done" },
      { name: "Gate 2 loading yard", planned: "06:40", actual: "06:52", state: "late" },
      { name: "Kharagpur toll", planned: "09:30", state: "next" },
      { name: "Kolkata port gate 3", planned: "12:15", state: "pending" },
    ],
  },
  {
    trip: "TRIP-24-1192",
    vehicle: "JH05AK4471",
    route: "Tata steel works → Ranchi",
    stops: [
      { name: "Coil dispatch yard", planned: "07:10", actual: "07:05", state: "done" },
      { name: "Chandil weighbridge", planned: "07:55", state: "missed" },
      { name: "Ranchi hub", planned: "10:40", state: "next" },
    ],
  },
];

export const ARRIVALS: { trip: string; vehicle: string; waypoint: string; planned: string; actual: string; eta: string; status: "ON TIME" | "LATE" | "EARLY" | "MISSED" | "EN ROUTE" }[] = [
  { trip: "TRIP-24-1187", vehicle: "JH05BS0128", waypoint: "Gate 2 loading yard", planned: "06:40", actual: "06:52", eta: "–", status: "LATE" },
  { trip: "TRIP-24-1187", vehicle: "JH05BS0128", waypoint: "Kharagpur toll", planned: "09:30", actual: "–", eta: "09:41", status: "EN ROUTE" },
  { trip: "TRIP-24-1192", vehicle: "JH05AK4471", waypoint: "Coil dispatch yard", planned: "07:10", actual: "07:05", eta: "–", status: "EARLY" },
  { trip: "TRIP-24-1192", vehicle: "JH05AK4471", waypoint: "Chandil weighbridge", planned: "07:55", actual: "–", eta: "–", status: "MISSED" },
  { trip: "TRIP-24-1203", vehicle: "WB23D7781", waypoint: "Container gate 3", planned: "11:00", actual: "10:58", eta: "–", status: "ON TIME" },
];

export const CORRIDORS: { name: string; route: string; buffer: number; inside: number; deviations: number }[] = [
  { name: "NH-18 Jamshedpur–Kharagpur", route: "Jamshedpur → Kolkata port", buffer: 250, inside: 0.987, deviations: 2 },
  { name: "NH-33 Jamshedpur–Ranchi", route: "Tata steel works → Ranchi", buffer: 250, inside: 0.962, deviations: 5 },
  { name: "Adityapur internal loop", route: "Depot ↔ loading yard", buffer: 60, inside: 0.999, deviations: 0 },
];

export const DEVIATIONS: { time: string; vehicle: string; corridor: string; offBy: string; duration: string; severity: "CRITICAL" | "HIGH" | "MEDIUM"; state: "OPEN" | "REJOINED" }[] = [
  { time: "13:52", vehicle: "JH05AK4471", corridor: "NH-33 Jamshedpur–Ranchi", offBy: "1.8 km", duration: "14 min", severity: "CRITICAL", state: "OPEN" },
  { time: "12:31", vehicle: "JH05CJ9012", corridor: "NH-18 Jamshedpur–Kharagpur", offBy: "420 m", duration: "6 min", severity: "HIGH", state: "REJOINED" },
  { time: "11:05", vehicle: "JH05BX2204", corridor: "NH-33 Jamshedpur–Ranchi", offBy: "310 m", duration: "3 min", severity: "MEDIUM", state: "REJOINED" },
];
