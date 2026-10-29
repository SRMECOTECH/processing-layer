"""Draws the figures used by the SRS and the database schema document (PNG, 200 dpi).

Run:  python -I diagrams.py <output-folder>
"""
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

NAVY = "#1F3864"
LIGHT = "#DEEAF6"
PALE = "#F2F2F2"
GREEN = "#E2EFDA"
AMBER = "#FFF2CC"
RED = "#FBE5D6"
GREY = "#595959"

plt.rcParams["font.family"] = "Arial"


def box(ax, x, y, w, h, text, fill=LIGHT, edge=NAVY, size=8, bold=False, dashed=False):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.08",
                                facecolor=fill, edgecolor=edge, linewidth=1.1,
                                linestyle="--" if dashed else "-"))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=size, color="black",
            fontweight="bold" if bold else "normal", linespacing=1.25)


def arrow(ax, x1, y1, x2, y2, label=None, dashed=False, size=6.5, both=False, rad=0.0, label_xy=None, color=GREY):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="<|-|>" if both else "-|>", mutation_scale=9,
                                 color=color, linewidth=1.0, linestyle="--" if dashed else "-",
                                 connectionstyle=f"arc3,rad={rad}"))
    if label:
        lx, ly = label_xy if label_xy else ((x1 + x2) / 2, (y1 + y2) / 2)
        ax.text(lx, ly, label, fontsize=size, color="#262626", ha="center", va="center",
                bbox=dict(facecolor="white", edgecolor="none", pad=0.6))


def canvas(w, h):
    fig, ax = plt.subplots(figsize=(w, h))
    ax.set_xlim(0, w)
    ax.set_ylim(0, h)
    ax.axis("off")
    return fig, ax


def save(fig, out, name):
    fig.savefig(out / name, dpi=200, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def context_diagram(out):
    fig, ax = canvas(10, 5.6)
    ax.add_patch(FancyBboxPatch((2.9, 1.25), 4.2, 4.1, boxstyle="round,pad=0.02,rounding_size=0.15",
                                facecolor="#FAFCFF", edgecolor=NAVY, linewidth=1.6, linestyle="--"))
    ax.text(5.0, 5.12, "System boundary: GPS Processing Layer", ha="center", fontsize=8.5, fontweight="bold", color=NAVY)
    box(ax, 3.15, 3.85, 3.7, 1.0, "gps-streaming-service (Spring Boot)\nprocessing, stream consumers,\nKafka Streams, REST API, reports",
        size=7.5)
    box(ax, 3.15, 2.75, 1.75, 0.8, "Apache Kafka\n(topics, 31-day log)", fill=AMBER, size=7)
    box(ax, 5.1, 2.75, 1.75, 0.8, "Ingest journal\n(local disk, fsync)", fill=AMBER, size=7)
    box(ax, 3.15, 1.5, 1.15, 0.95, "PostgreSQL\ngps_tracking", fill=GREEN, size=7)
    box(ax, 4.42, 1.5, 1.15, 0.95, "Redis\n(live state,\nconfig)", fill=GREEN, size=7)
    box(ax, 5.7, 1.5, 1.15, 0.95, "PulseStream\nconsole\n(Next.js)", size=7)

    box(ax, 0.1, 4.6, 2.2, 0.85, "GPS devices / device parser\n(future)", fill=PALE, size=7, dashed=True)
    box(ax, 0.1, 3.35, 2.2, 0.85, "Fleet simulator\n(replays real trips)", fill=PALE, size=7)
    box(ax, 0.1, 2.1, 2.2, 0.85, "smart_truck MySQL\n(legacy, READ-ONLY)", fill=PALE, size=7)
    box(ax, 0.1, 0.55, 2.2, 0.95, "Console users: fleet operator,\nfleet manager, platform\nengineer, administrator", fill=PALE, size=7)
    box(ax, 7.7, 4.6, 2.2, 0.85, "Valhalla routing engine\n(HP home server :8002)", fill=PALE, size=7)
    box(ax, 7.7, 3.45, 2.2, 0.85, "HP ingestion API\n(FastAPI :8000, health only)", fill=PALE, size=7)
    box(ax, 7.7, 2.3, 2.2, 0.85, "Open-Meteo weather API\nTomTom traffic API (opt.)", fill=PALE, size=7)
    box(ax, 7.7, 1.0, 2.2, 0.95, "HP home-server Redis\n(optional, database 1)", fill=PALE, size=7)

    arrow(ax, 2.3, 5.0, 3.15, 4.45, "raw frames (future)", dashed=True)
    arrow(ax, 2.3, 3.8, 3.15, 4.1, "raw frames JSON")
    arrow(ax, 2.3, 2.5, 3.15, 3.95, "SELECT trips/pings", label_xy=(2.55, 3.05))
    arrow(ax, 2.3, 1.0, 5.7, 1.75, "HTTPS sign-in, pages", rad=0.18, label_xy=(3.9, 0.82))
    arrow(ax, 7.7, 5.0, 6.85, 4.55, "route / test", both=True)
    arrow(ax, 7.7, 3.85, 6.85, 4.2, "health check", both=True)
    arrow(ax, 7.7, 2.7, 6.85, 3.95, "weather / traffic", both=True, label_xy=(7.3, 3.25))
    arrow(ax, 7.7, 1.45, 6.85, 2.0, "state (optional)", both=True, dashed=True)
    save(fig, out, "context.png")


def pipeline_diagram(out):
    fig, ax = canvas(10, 7.0)
    box(ax, 0.1, 6.0, 1.7, 0.75, "Simulator /\ndevice parser", fill=PALE, size=7)
    box(ax, 2.1, 6.0, 1.7, 0.75, "Ingest journal\n(fsync first)", fill=AMBER, size=7)
    box(ax, 4.1, 6.0, 2.1, 0.75, "gps.raw-device-frames\n3 partitions, key = IMEI", fill=AMBER, size=7, bold=True)
    box(ax, 6.6, 5.85, 3.3, 1.05, "Normalizer (Kafka Streams, exactly-once)\ngroup gps-raw-frame-processing-service\nvalidate · de-duplicate · distance · status",
        size=7)
    arrow(ax, 1.8, 6.37, 2.1, 6.37)
    arrow(ax, 3.8, 6.37, 4.1, 6.37)
    arrow(ax, 6.2, 6.37, 6.6, 6.37)

    box(ax, 6.6, 4.45, 3.3, 0.75, "gps.rejected-frames\n(reason + original payload)", fill=RED, size=7, bold=True)
    box(ax, 0.1, 4.45, 3.9, 0.75, "gps.processed-positions  (3 partitions, key = IMEI,\ntimestamp = recordedAt, read_committed)", fill=AMBER, size=7, bold=True)
    arrow(ax, 8.25, 5.85, 8.25, 5.2, "rejected")
    arrow(ax, 7.2, 5.85, 3.0, 5.2, "accepted", label_xy=(5.1, 5.52))

    consumers = [
        ("gps-position-storage-service", "PostgreSQL gps_position"),
        ("gps-live-location-service", "Redis gps:live:*"),
        ("gps-trip-service", "gps_trip, gps_stop,\nvehicle_motion_state"),
        ("gps-speed-violation-service", "vehicle_event, Redis"),
        ("gps-vehicle-state-service", "Redis state + history"),
        ("gps-stoppage-service", "vehicle_event, Redis"),
        ("gps-ignition-service", "vehicle_event, Redis"),
        ("gps-streams-analytics", "KTable, gps.vehicle-activity"),
    ]
    top, step = 3.75, 0.47
    for i, (group, sink) in enumerate(consumers):
        y = top - i * step
        box(ax, 0.1, y, 2.55, 0.42, group, size=6.5)
        box(ax, 2.95, y, 2.0, 0.42, sink, fill=GREEN, size=6)
        arrow(ax, 2.65, y + 0.21, 2.95, y + 0.21)
        arrow(ax, 0.05, y + 0.21, 0.1, y + 0.21)
    ax.plot([0.05, 0.05], [top - (len(consumers) - 1) * step + 0.21, 4.45], color=GREY, linewidth=1.0)

    box(ax, 6.6, 3.45, 3.3, 0.6, "gps-rejected-frame-logger\n-> PostgreSQL rejected_frame + log", size=6.5)
    arrow(ax, 8.25, 4.45, 8.25, 4.05)
    box(ax, 5.4, 1.95, 2.0, 0.55, "Events: gps.vehicle-events\n(+ Redis list per vehicle)", fill=AMBER, size=6.5)
    arrow(ax, 4.95, 2.55, 5.4, 2.3)
    box(ax, 7.65, 1.95, 2.25, 0.55, "Reports: vehicle_day_summary\n(rebuilt every 5 min)", fill=GREEN, size=6.5)
    box(ax, 6.6, 0.55, 3.3, 0.85, "gps.dead-letters  <-  any consumer or Streams app\nthat cannot handle a record\n-> gps-dead-letter-archive -> dead_letter", fill=RED, size=6.5)
    save(fig, out, "pipeline.png")


def trip_state_machine(out):
    fig, ax = canvas(10, 4.5)
    box(ax, 0.3, 1.5, 2.6, 1.2, "STATIONARY\nstop OPEN\n(motion_streak = false)", fill=GREEN, size=8, bold=True)
    box(ax, 6.9, 1.5, 2.6, 1.2, "MOVING\ntrip OPEN\n(motion_streak = true)", size=8, bold=True)
    arrow(ax, 2.9, 2.45, 6.9, 2.45, "a reading is >= 200 m (min-distance) from the stop point\n-> stop CLOSED, trip OPEN (start back-dated to first movement)",
          label_xy=(4.9, 2.8), size=7)
    arrow(ax, 6.9, 1.75, 2.9, 1.75, "no reading of the last 180 s (min-duration) is >= 200 m away\n-> trip CLOSED, stop OPEN",
          label_xy=(4.9, 1.38), size=7)
    arrow(ax, 8.2, 2.7, 1.6, 2.7, "silence > 3600 s (stop-gap) or device moved to another vehicle\n-> trip CLOSED at the last reading before the gap",
          rad=0.32, label_xy=(4.9, 3.85), size=7)
    ax.text(4.9, 0.45, "Late or repeated reading (recorded_at <= last_recorded_at): no state change; a late reading is noted once in\n"
                       "gps_trip_late_reading and the affected trip's late_reading_count is increased by 1.",
            fontsize=7, ha="center", va="center", bbox=dict(facecolor="white", edgecolor=GREY, pad=3))
    save(fig, out, "trip-state.png")


def dead_letter_lifecycle(out):
    fig, ax = canvas(10, 2.6)
    box(ax, 0.2, 0.9, 2.2, 0.9, "Record cannot be\nhandled (consumer)", fill=PALE, size=7.5)
    box(ax, 3.3, 0.9, 1.8, 0.9, "PARKED", fill=AMBER, size=9, bold=True)
    box(ax, 6.4, 1.5, 1.8, 0.75, "REPLAYED", fill=GREEN, size=9, bold=True)
    box(ax, 6.4, 0.25, 1.8, 0.75, "DISCARDED", fill=RED, size=9, bold=True)
    arrow(ax, 2.4, 1.35, 3.3, 1.35, "park")
    arrow(ax, 5.1, 1.55, 6.4, 1.85, "replay (Kafka confirmed)", size=6.5, label_xy=(5.7, 2.1))
    arrow(ax, 5.1, 1.15, 6.4, 0.65, "discard (note required)", size=6.5, label_xy=(5.7, 0.6))
    ax.text(9.1, 1.87, "may be replayed\nagain:\nreplay_count + 1", fontsize=6.5, ha="center", va="center")
    ax.text(9.1, 0.62, "final state:\nno replay", fontsize=6.5, ha="center", va="center")
    save(fig, out, "dead-letter.png")


def table_box(ax, x, y, w, title, rows, fill):
    line = 0.2
    h = 0.32 + line * len(rows)
    ax.add_patch(FancyBboxPatch((x, y - h), w, h, boxstyle="round,pad=0.0,rounding_size=0.05",
                                facecolor="white", edgecolor=NAVY, linewidth=1.0))
    ax.add_patch(FancyBboxPatch((x, y - 0.32), w, 0.32, boxstyle="round,pad=0.0,rounding_size=0.05",
                                facecolor=fill, edgecolor=NAVY, linewidth=1.0))
    ax.text(x + w / 2, y - 0.16, title, ha="center", va="center", fontsize=7.5, fontweight="bold", color=NAVY)
    for i, r in enumerate(rows):
        ax.text(x + 0.07, y - 0.32 - line * (i + 0.5), r, ha="left", va="center", fontsize=6.0)


def er_diagram(out):
    fig, ax = canvas(13, 8.3)
    table_box(ax, 0.2, 8.2, 2.6, "tenant", ["PK id uuid", "UK code varchar(40)", "legal_name", "status (ACTIVE|SUSPENDED|CLOSED)", "created_at"], GREEN)
    table_box(ax, 3.6, 8.2, 2.7, "vehicle", ["PK id uuid", "UK registration_no varchar(50)", "FK owner_tenant_id -> tenant (null = market)", "created_at"], GREEN)
    table_box(ax, 6.9, 8.2, 2.7, "device_assignment", ["PK id uuid", "FK device_id -> device", "FK vehicle_id -> vehicle", "valid_from, valid_to (null = open)", "EXCLUDE overlapping periods per device"], GREEN)
    table_box(ax, 10.2, 8.2, 2.6, "device", ["PK id uuid", "UK imei varchar(50)", "kind (GPS|FUEL|TEMPERATURE|DOOR|OBD)", "protocol", "created_at"], GREEN)
    arrow(ax, 3.6, 7.55, 2.8, 7.55, color=NAVY)
    ax.text(3.2, 7.68, "0..1 : N", fontsize=6.5, ha="center", color=NAVY)
    arrow(ax, 6.9, 7.55, 6.3, 7.55, color=NAVY)
    ax.text(6.6, 7.68, "1 : N", fontsize=6.5, ha="center", color=NAVY)
    arrow(ax, 9.6, 7.55, 10.2, 7.55, color=NAVY)
    ax.text(9.9, 7.68, "N : 1", fontsize=6.5, ha="center", color=NAVY)

    table_box(ax, 0.2, 6.2, 3.0, "gps_position", ["PK id bigint identity", "UK (imei, recorded_at)", "tenant_code, vehicle_registration_no", "latitude, longitude, speed_kmph",
                                                 "movement_status, distance_from_previous_m", "total_distance_m, distance_source",
                                                 "ignition, odometer_m, arrived_out_of_order", "processed_at, stored_at"], LIGHT)
    table_box(ax, 3.5, 6.2, 3.0, "vehicle_motion_state", ["PK imei", "assignment_id (logical)", "motion_streak, event_recorded_at", "event_latitude, event_longitude",
                                                         "last_recorded_at (guard)", "reading_window jsonb", "open_trip jsonb, open_stop jsonb"], LIGHT)
    table_box(ax, 6.8, 6.2, 3.0, "gps_trip", ["PK id bigint identity", "UK (imei, start_recorded_at)", "start/end time and position", "distance_m >= 0, duration_s",
                                             "max/avg speed, reading_count", "late_reading_count", "status (OPEN|CLOSED)"], LIGHT)
    table_box(ax, 10.1, 6.2, 2.7, "gps_stop", ["PK id bigint identity", "UK (imei, start_recorded_at)", "start/end time", "latitude, longitude", "duration_s, reading_count", "status (OPEN|CLOSED)"], LIGHT)
    table_box(ax, 6.8, 4.25, 3.0, "gps_trip_late_reading", ["PK (imei, recorded_at)", "trip_start_recorded_at (logical)", "noted_at"], LIGHT)
    table_box(ax, 10.1, 4.45, 2.7, "vehicle_event", ["PK id bigint identity", "UK event_key", "imei, tenant_code, vehicle_reg_no", "event_type, severity", "status (OPEN|CLOSED)",
                                                    "started_at, ended_at", "detected_by, details jsonb"], LIGHT)
    table_box(ax, 0.2, 3.75, 3.0, "vehicle_day_summary", ["PK (day, imei, generation)", "readings, first/last_seen_at", "distance_m, moving_s, stopped_s", "fast_distance_m, idle_s",
                                                         "stops, trips, overspeed/harsh events", "computed_at"], AMBER)
    table_box(ax, 3.5, 3.75, 3.0, "dead_letter", ["PK id bigint identity", "UK (dl_partition, dl_offset, parked_at)", "source, original topic/partition/offset", "record_key, record_value",
                                                 "exception class/message/stacktrace", "status (PARKED|REPLAYED|DISCARDED)", "replay_count, resolution_note"], RED)
    table_box(ax, 6.8, 3.1, 3.0, "rejected_frame", ["PK id bigint identity", "UK (rejected_at, payload_hash)", "imei, rejection_reason", "original_payload, archived_at"], RED)
    table_box(ax, 10.1, 2.25, 2.7, "connection_setting", ["PK name", "url, updated_at"], PALE)
    table_box(ax, 10.1, 1.35, 2.7, "report_setting", ["PK name", "value, updated_at"], PALE)

    ax.text(5.0, 0.75, "Green = master data (foreign keys). Blue = telemetry and trip detection, amber = reporting, red = archives,\n"
                       "grey = configuration. Telemetry, reporting and archive tables reference master data LOGICALLY through imei,\n"
                       "tenant_code and vehicle_registration_no (no foreign keys).",
            ha="center", fontsize=7, color="#262626", bbox=dict(facecolor="#FAFAFA", edgecolor=GREY, pad=4))
    save(fig, out, "er-diagram.png")


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "figures")
    target.mkdir(parents=True, exist_ok=True)
    context_diagram(target)
    pipeline_diagram(target)
    trip_state_machine(target)
    dead_letter_lifecycle(target)
    er_diagram(target)
    print("figures written to", target.resolve())
