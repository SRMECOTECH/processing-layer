"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Clock, MapPinned, Route, Siren, Truck } from "@/components/icons";
import { EventTable } from "@/components/ops/events";
import { Card, Empty, KeyValues, PageHeader, Stats, Tag, Tile } from "@/components/ops/kit";
import { Explain, Formula } from "@/components/ops/reports";
import { formatAgo, formatDateTime } from "@/lib/format";
import { GPS_LINK_LABEL, gpsLinkTone, minutes, type ShipmentDetail } from "@/lib/tms";
import { usePoll } from "@/lib/use-poll";
import { useViewer } from "@/lib/viewer";
import { RouteMap } from "./maps";
import { StageTag, VerdictTag } from "./shipments";
import s from "./tms.module.css";

/**
 * One shipment, as its transporter, consigner and the GPS provider see it: where the truck is, how it went, whether it
 * will make the ETA. The truck is shown only for the shipment's own window (booking to arrival), never before or after.
 */
export function ShipmentDetailPage() {
  const id = useSearchParams().get("id");
  const viewer = useViewer();
  const poll = usePoll<ShipmentDetail>(id ? `/api/portal/shipments/${encodeURIComponent(id)}` : null, 15_000);
  const back = viewer.role === "TRANSPORTER" ? "/transporter/shipments" : viewer.role === "CONSIGNER" ? "/consigner/shipments" : "/tms/shipments";

  if (!id) return <Empty>No shipment chosen.</Empty>;
  if (poll.error && !poll.data) {
    return (
      <div className="stack">
        <PageHeader title={`Shipment ${id}`} />
        <Empty>{poll.error}</Empty>
      </div>
    );
  }
  const d = poll.data;
  if (!d) return <Empty>Loading the shipment.</Empty>;
  const v = d.shipment;
  const now = v.lastLatitude !== null && v.lastLongitude !== null ? { latitude: v.lastLatitude, longitude: v.lastLongitude } : null;
  const nowMs = Date.now();

  return (
    <div className="stack">
      <PageHeader
        title={`Trip ${v.tripNo} · ${v.vehicleNo}`}
        description={`${v.origin ?? "?"} → ${v.destination ?? "?"}${v.consignee ? ` for ${v.consignee}` : ""}. Carried by ${v.transporterName}${v.consignerUnit ? `, sent by ${v.consignerUnit}` : ""}.`}
        badges={
          <>
            <StageTag stage={v.stage} />
            <VerdictTag shipment={v} />
          </>
        }
        actions={
          <Link className="btn soft" href={back}>
            All shipments
          </Link>
        }
      />

      <Stats columns={5}>
        <Tile label="Gate-out" icon={Truck} value={formatDateTime(v.dispatchedAt)} foot={v.bookedAt ? `booked ${formatDateTime(v.bookedAt)}` : undefined} />
        <Tile label="ETA" icon={Clock} value={v.etaAt ? formatDateTime(v.etaAt) : "–"} foot={v.estimatedArrivalAt ? `estimate ${formatDateTime(v.estimatedArrivalAt)}` : undefined} />
        <Tile
          label={v.arrivedAt ? "Arrived" : "Still to go"}
          icon={MapPinned}
          value={v.arrivedAt ? formatDateTime(v.arrivedAt) : v.remainingKm !== null ? `${v.remainingKm} km` : "–"}
          foot={v.arrivedAt && v.gpsArrivedAt === null && v.reportedAtaAt ? "reported by the TMS" : v.arrivedAt ? "seen by the GPS" : undefined}
        />
        <Tile label="Transit" icon={Route} value={minutes(v.transitMinutes)} foot={v.leftOriginAt ? `left the plant ${formatDateTime(v.leftOriginAt)}` : "not left the plant fence yet"} />
        <Tile
          label="Last GPS reading"
          icon={Siren}
          value={v.lastReadingAt ? formatAgo(v.lastReadingAt, nowMs) : "none"}
          tone={v.gpsSilent ? "bad" : undefined}
          foot={v.lastSpeedKmph !== null ? `${v.lastSpeedKmph} km/h` : undefined}
        />
      </Stats>

      <div className="grid-main-side">
        <Card icon={MapPinned} title="Route" subtitle={`Readings from ${formatDateTime(d.trackFrom)} to ${formatDateTime(d.trackTo)}`} live={!poll.error}>
          <RouteMap track={d.track} origin={v.originFence} destination={v.destinationFence} now={v.arrivedAt ? null : now} />
          {(!v.originFence || !v.destinationFence) && (
            <p className={s.small} style={{ marginTop: 10 }}>
              {!v.destinationFence && `"${v.destination ?? "?"}" has no geofence, so the arrival cannot be seen by the GPS. `}
              {!v.originFence && `"${v.origin ?? "?"}" has no geofence: the departure is the first moving reading after the gate-out. `}
              {viewer.role === "DEVELOPER" ? (
                <Link className="link" href="/tms/places">
                  Add the place
                </Link>
              ) : (
                "Ask the GPS provider to add the place."
              )}
            </p>
          )}
        </Card>
        <Card icon={Clock} title="Timeline" subtitle="From booking to arrival">
          <ol className={s.steps}>
            {d.timeline.map((step, i) => (
              <li key={`${step.kind}-${i}`} className={`${s.step}${new Date(step.at).getTime() > nowMs ? ` ${s.stepFuture}` : ""}`}>
                <span className={s.stepDot} data-kind={step.kind} />
                <span className={s.stepLabel}>{step.label}</span>
                <span className={s.stepTime}>{formatDateTime(step.at)}</span>
              </li>
            ))}
          </ol>
          <Explain>
            <Formula
              label="Works detention"
              how="gate-out (dt_trip_start) − booking (dt_booking): the time in the plant the TMS declares"
              result={minutes(v.worksDetentionMinutes)}
            />
            <Formula
              label="Hold after gate-out"
              how="first GPS reading outside the plant geofence − gate-out: the hidden part of the detention"
              result={minutes(v.originHoldMinutes)}
            />
            <Formula
              label="Estimate"
              how={`last position → destination: straight line × 1.3 for roads, at ${v.tripClass === "local" ? "25" : "40"} km/h (${v.tripClass ?? "zonal"} trip)`}
              result={v.estimatedArrivalAt ? formatDateTime(v.estimatedArrivalAt) : "–"}
            />
            <Formula label="Verdict" how="arrival against the ETA once arrived; before that, now and the estimate against the ETA" result={v.verdict} />
          </Explain>
        </Card>
      </div>

      <div className="grid-2">
        <Card icon={Truck} title="Shipment">
          <KeyValues
            rows={[
              ["Trip number", <span className="mono">{v.tripNo}</span>],
              ["Invoice", v.invoiceNo ?? "–"],
              ["Shipment / delivery", v.shipmentRef ?? "–"],
              ["Material", v.material ? `${v.material}${v.quantity !== null ? ` · ${v.quantity}` : ""}` : v.quantity !== null ? String(v.quantity) : "–"],
              ["Load plant", v.loadPlant ?? "–"],
              ["Consignee", v.consignee ?? "–"],
              ["Ship-to address", v.consigneeAddress ?? "–"],
              ["Trip class / type", [v.tripClass, v.tripType].filter(Boolean).join(" · ") || "–"],
              ["Status in the TMS", [v.sourceStatus, v.closeReason].filter(Boolean).join(" · ") || "–"],
              ["Closed here", v.closedAt ? `${formatDateTime(v.closedAt)} (${(v.closedReason ?? "").replaceAll("_", " ").toLowerCase()})` : "–"],
            ]}
          />
        </Card>
        <Card icon={Truck} title="Truck and driver">
          <KeyValues
            rows={[
              ["Vehicle", <span className="mono">{v.vehicleNo}</span>],
              ["Vehicle type", v.vehicleType ?? "–"],
              ["Transporter", v.transporterName],
              ["Driver", v.driverName ?? "–"],
              ["Driver mobile", v.driverMobile ? <a className="link" href={`tel:${v.driverMobile}`}>{v.driverMobile}</a> : "–"],
              ["GPS device", <Tag tone={gpsLinkTone(v.gpsLink)}>{GPS_LINK_LABEL[v.gpsLink]}</Tag>],
              ...(viewer.role !== "CONSIGNER"
                ? ([
                    ["Mapped IMEI", <span className="mono">{v.mappedImei ?? "–"}</span>],
                    ["IMEI in the file", <span className="mono">{v.deviceImei ?? "–"}</span>],
                  ] as [string, React.ReactNode][])
                : []),
            ]}
          />
        </Card>
      </div>

      <Card icon={Siren} title="What happened on the road" subtitle="Events of the truck during this shipment" flush>
        <EventTable events={d.events} empty="Nothing happened on the road." />
      </Card>
    </div>
  );
}
