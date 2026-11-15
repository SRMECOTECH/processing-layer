/**
 * Road Intelligence: what we know about every road and how it helps transport operations (docs/ROAD_INTELLIGENCE.md).
 * The catalogue of its sub-modules A to M, with what is built, what the HP's Valhalla gives, and what we must add.
 */

export type Status = "live" | "partial" | "planned";

export type Feature = { text: string; status: Status };

export type RoadModule = {
  key: string;
  letter: string;
  title: string;
  href: string;
  status: Status;
  phase: 1 | 2;
  summary: string;
  features: Feature[];
  /** What the HP's Valhalla gives for it (checked on 2026-11-15 against trip 28860462). */
  valhalla: string;
  /** What we build on top. */
  build: string;
  /** Where the data falls short, honestly. */
  caveat?: string;
};

export const STATUS_LABEL: Record<Status, string> = { live: "Built", partial: "Partly built", planned: "Planned" };

export const ROAD_MODULES: RoadModule[] = [
  {
    key: "explorer",
    letter: "A",
    title: "Road network explorer",
    href: "/roads/explorer",
    status: "partial",
    phase: 1,
    summary: "Search the roads our trucks drive: names, numbers, class, lanes, length, the road under any point.",
    features: [
      { text: "Road pieces drawn on a map (in-house, no map tiles)", status: "live" },
      { text: "Road names and numbers (NH19, SH5, MDR156)", status: "live" },
      { text: "Road class and lanes per piece", status: "live" },
      { text: "Road piece length", status: "live" },
      { text: "The road under a point (lat, lon)", status: "live" },
      { text: "One-way and two-way roads", status: "planned" },
      { text: "Intersections and connected roads", status: "planned" },
      { text: "Dead-end roads", status: "planned" },
      { text: "Bridges, tunnels and roundabouts on the map", status: "planned" },
    ],
    valhalla: "/trace_attributes gives names, class, lanes, one-way (traversability), bridge, tunnel, roundabout, use per edge; /locate (verbose) gives dead-end, traffic signals, stop signs and how far one can drive from the road (reach).",
    build: "road_segment library (3,658 pieces, 1,309 km for the test trip), search, map. Next: store one-way, bridge, tunnel, roundabout, dead-end (they are already in Valhalla's answer).",
    caveat: "Valhalla has no 'export every road' call: we know the roads trucks drove. The whole network needs the OpenStreetMap extract itself (see G).",
  },
  {
    key: "classification",
    letter: "B",
    title: "Road classification & hierarchy",
    href: "/roads/classification",
    status: "partial",
    phase: 1,
    summary: "Every road in its hierarchy, expressway to service road, with length by category.",
    features: [
      { text: "Road length by category", status: "live" },
      { text: "Highway versus local-road distribution", status: "live" },
      { text: "Town versus open road", status: "partial" },
      { text: "Road density by district", status: "planned" },
      { text: "Classification heatmap", status: "planned" },
      { text: "Road connectivity score, poorly connected areas", status: "planned" },
    ],
    valhalla: "road_class per edge (motorway, trunk, primary, secondary, tertiary, unclassified, residential, service_other) and density 0..15.",
    build: "Totals from the library. District totals need district boundaries (a GIS layer) and the full network.",
    caveat: "Valhalla's density rarely reaches the town threshold (9) in India: no road on the test trip counted as town yet.",
  },
  {
    key: "restrictions",
    letter: "C",
    title: "Road restrictions & accessibility",
    href: "/roads/restrictions",
    status: "planned",
    phase: 1,
    summary: "Which roads a given truck may not use: height, width, weight, axle load, access, one-way, vehicle type.",
    features: [
      { text: "Truck access per road (allowed / not)", status: "planned" },
      { text: "Destination-only for trucks", status: "planned" },
      { text: "Low bridges (max height)", status: "planned" },
      { text: "Weight and axle-load limits", status: "planned" },
      { text: "Vehicle-specific accessibility (our truck sizes)", status: "planned" },
      { text: "Restriction report per route", status: "planned" },
    ],
    valhalla: "/locate (verbose) gives access per vehicle type, access_restriction, destination_only_hgv, truck_route and the restrictions list; truck costing obeys height/width/weight when OpenStreetMap has them.",
    build: "Store access flags per piece; read maxheight/maxweight/maxaxleload for our way ids from an offline OpenStreetMap extract; vehicle profiles (height, weight, axles) per truck type.",
    caveat: "maxheight / maxweight are rarely tagged on Indian roads: the map will show what is known, and the data-quality page (M) how little.",
  },
  {
    key: "surface",
    letter: "D",
    title: "Road surface & physical characteristics",
    href: "/roads/surface",
    status: "planned",
    phase: 1,
    summary: "Paved or not, surface type, width, lanes, smoothness, lighting: is the road fit for a heavy truck?",
    features: [
      { text: "Lane count", status: "live" },
      { text: "Paved versus unpaved", status: "planned" },
      { text: "Surface type (asphalt, concrete, gravel)", status: "planned" },
      { text: "Road width, narrow roads", status: "planned" },
      { text: "Lighting, sidewalks", status: "planned" },
    ],
    valhalla: "lane_count, surface, unpaved, lit, sidewalk, shoulder per edge.",
    build: "Width and smoothness from the OpenStreetMap extract by way id; a 'fit for heavy vehicles' flag.",
    caveat: "On the test trip every road came back 'paved_smooth' - Valhalla's default when OpenStreetMap has no surface tag - so surface is not usable yet.",
  },
  {
    key: "geometry",
    letter: "E",
    title: "Road geometry & elevation",
    href: "/roads/geometry",
    status: "planned",
    phase: 1,
    summary: "Gradients, sharp bends, curvature, mountain roads: a risk read-out before a truck takes a ghat.",
    features: [
      { text: "Curvature per road piece", status: "planned" },
      { text: "Sharp bends", status: "planned" },
      { text: "Elevation profile of a road", status: "planned" },
      { text: "Steep up and down sections, gradient", status: "planned" },
      { text: "Mountain roads, terrain score", status: "planned" },
    ],
    valhalla: "curvature 0..15 per edge works now; /height and the edge grades need elevation tiles.",
    build: "Store curvature now. On the HP: build Valhalla's elevation tiles for India (valhalla_build_elevation), then /height and max_upward_grade / max_downward_grade fill in.",
    caveat: "The HP's Valhalla was built without elevation: /height answers null today.",
  },
  {
    key: "infrastructure",
    letter: "F",
    title: "Bridges, tunnels & special infrastructure",
    href: "/roads/infrastructure",
    status: "planned",
    phase: 1,
    summary: "Bridges, tunnels, railway crossings, toll booths, ferries, underpasses on the roads we use.",
    features: [
      { text: "Bridges", status: "planned" },
      { text: "Tunnels", status: "planned" },
      { text: "Railway level crossings", status: "planned" },
      { text: "Toll booths", status: "planned" },
      { text: "Ferries", status: "planned" },
      { text: "Bridge height and weight limits (with C)", status: "planned" },
    ],
    valhalla: "bridge, tunnel, toll, use (ferry, ramp) per edge; end_node type (toll booth, level crossing via the extract).",
    build: "Store the flags per piece; level crossings and toll booths are points: from the OpenStreetMap extract.",
    caveat: "2.2 km of bridges flagged on the first 96 km of the test trip; tolls appear as booths (points), not as road edges.",
  },
  {
    key: "connectivity",
    letter: "G",
    title: "Road connectivity & network analysis",
    href: "/roads/connectivity",
    status: "planned",
    phase: 2,
    summary: "Intersections, dead ends, alternatives, bottlenecks: where one bridge or road is the only link.",
    features: [
      { text: "Intersection density", status: "planned" },
      { text: "Dead ends and isolated roads", status: "planned" },
      { text: "Alternative connections between two places", status: "planned" },
      { text: "Bottlenecks: single bridges or roads linking two networks", status: "planned" },
    ],
    valhalla: "/locate reach (inbound/outbound) per edge; /route with alternates; /isochrone for coverage.",
    build: "Needs the whole road graph: import the same OpenStreetMap extract the HP's tiles were built from into PostGIS (osm2pgsql), then graph analysis there.",
    caveat: "Network-wide analysis cannot be done from the roads trucks drove alone.",
  },
  {
    key: "speed",
    letter: "H",
    title: "Road speed & regulatory intelligence",
    href: "/roads/speed",
    status: "live",
    phase: 1,
    summary: "The limit of every road for a goods vehicle, used by the live over-speed check and the day-end report.",
    features: [
      { text: "Limit per road piece: hand-set > OpenStreetMap > table", status: "live" },
      { text: "Limit table by road class, town/open road and state", status: "live" },
      { text: "Live over-speed judged by the road's limit", status: "live" },
      { text: "Day-end road & speed report", status: "live" },
      { text: "Roads with missing speed-limit information", status: "partial" },
      { text: "Speed-limit changes along a road", status: "planned" },
      { text: "Speed-limit distribution by district", status: "planned" },
    ],
    valhalla: "speed_limit (OpenStreetMap maxspeed) per edge: 81 of 963 km on the test trip; tagged for cars, so we use it only when it is lower than the truck table.",
    build: "road_speed_limit table, hand-set limits, road-aware speed check, vehicle_day_road report.",
  },
  {
    key: "toll",
    letter: "I",
    title: "Road toll & cost intelligence",
    href: "/roads/toll",
    status: "planned",
    phase: 2,
    summary: "Toll roads and plazas on our lanes, toll versus toll-free alternatives, toll spend per trip.",
    features: [
      { text: "Toll roads and toll plazas", status: "planned" },
      { text: "Toll versus non-toll route comparison", status: "planned" },
      { text: "Toll spend per trip and lane", status: "planned" },
    ],
    valhalla: "toll flag per edge; routing can avoid tolls (use_tolls).",
    build: "Toll prices from NHAI's public plaza fee tables (a separate dataset), matched to plazas on our trips.",
    caveat: "Valhalla has no Indian toll prices.",
  },
  {
    key: "risk",
    letter: "J",
    title: "Road risk & quality scoring",
    href: "/roads/risk",
    status: "planned",
    phase: 1,
    summary: "A risk score per road from its bends, gradients, width, surface, lighting, restrictions and incidents.",
    features: [
      { text: "Our own incidents per road: over-speed, harsh driving, stops", status: "partial" },
      { text: "Accident black spots (MoRTH list)", status: "planned" },
      { text: "Geometry and surface factors (from D, E)", status: "planned" },
      { text: "Score with its factors shown, not a bare number", status: "planned" },
    ],
    valhalla: "Factors only (curvature, surface, lit, restrictions).",
    build: "Our telemetry per road piece (over-speed episodes already carry their road) + MoRTH black spots + geometry. Factors are shown next to the score.",
    caveat: "A score is only as good as its inputs: accident data is external and must be loaded.",
  },
  {
    key: "reachability",
    letter: "K",
    title: "Road reachability",
    href: "/roads/reachability",
    status: "planned",
    phase: 2,
    summary: "How far a truck gets in 1, 2, 4 hours from a plant or yard (isochrones), and network coverage.",
    features: [{ text: "Isochrones from a place", status: "planned" }],
    valhalla: "/isochrone (truck costing).",
    build: "Isochrones from our geofences, drawn on the in-house map.",
  },
  {
    key: "comparison",
    letter: "L",
    title: "Road comparison",
    href: "/roads/comparison",
    status: "planned",
    phase: 2,
    summary: "Compare alternative roads between two places: length, class, limits, tolls, risk.",
    features: [{ text: "Side-by-side of route alternatives", status: "planned" }],
    valhalla: "/route with alternates and trace_attributes on each.",
    build: "Comparison table from the stored road attributes.",
  },
  {
    key: "quality",
    letter: "M",
    title: "Road data quality",
    href: "/roads/quality",
    status: "planned",
    phase: 1,
    summary: "How much of our roads have names, limits, surface, restrictions: what the other modules can trust.",
    features: [
      { text: "Coverage of speed limits, names, surface, restrictions", status: "planned" },
      { text: "Roads with inconsistent attributes", status: "planned" },
    ],
    valhalla: "Whatever it returns, counted.",
    build: "Coverage counts per attribute over road_segment: cheap, and it keeps every other module honest.",
  },
];

export function moduleByKey(key: string): RoadModule | undefined {
  return ROAD_MODULES.find((m) => m.key === key);
}

/** What the HP's Valhalla returned for the first 96 km of trip 28860462 (2026-11-15). */
export const VALHALLA_PROBE: { attribute: string; result: string; usable: Status }[] = [
  { attribute: "Road names, numbers, class, lanes", result: "Returned for every edge", usable: "live" },
  { attribute: "OpenStreetMap speed limit", result: "81 of 963 km (car limits)", usable: "partial" },
  { attribute: "One-way / two-way", result: "77 km one-way (divided highway), 18 km two-way", usable: "live" },
  { attribute: "Bridges", result: "2.2 km flagged", usable: "live" },
  { attribute: "Curvature", result: "0..7 on these roads", usable: "live" },
  { attribute: "Road use (ramp, service road, turn lane)", result: "Returned", usable: "live" },
  { attribute: "Dead end, access per vehicle type, destination-only for trucks, signals, reach", result: "Returned by /locate (verbose)", usable: "live" },
  { attribute: "Surface", result: "Always 'paved_smooth' (the default when untagged)", usable: "planned" },
  { attribute: "Elevation, gradients (/height)", result: "null: tiles built without elevation", usable: "planned" },
  { attribute: "Tolls", result: "No toll edges; booths are points", usable: "planned" },
  { attribute: "State code", result: "Not returned by this build", usable: "planned" },
];
