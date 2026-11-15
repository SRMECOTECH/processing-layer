"use client";

import Link from "next/link";
import { Building2, ChartLine, Clock, Gauge, MapPinned, Radio, Route, Siren, Timer, Truck, TriangleAlert } from "@/components/icons";
import { EventFeed } from "@/components/ops/events";
import { Card, Empty, PageHeader, Stats, Tile } from "@/components/ops/kit";
import { Explain, Formula } from "@/components/ops/reports";
import { formatDateTime, formatNumber } from "@/lib/format";
import { minutes, percent, scoped, type Overview } from "@/lib/tms";
import { usePoll } from "@/lib/use-poll";
import { useViewer } from "@/lib/viewer";
import { LaneTable, PlantTable, TransporterTable } from "./performance";
import { CompanyPicker, ShipmentTable, useCompanyScope } from "./shipments";

function useOverviewData(path = "/api/portal/overview") {
  const scope = useCompanyScope();
  return usePoll<Overview>(scoped(path, scope), 20_000);
}

function OnTimeExplain({ o }: { o: Overview }) {
  const k = o.shipments;
  return (
    <Explain>
      <Formula
        label="On-time delivery"
        how={`shipments that arrived (GPS entered the destination fence, or the TMS reported an arrival) by their ETA ÷ all that arrived with an ETA, last ${o.periodDays} days`}
        result={`${percent(k.onTimePct)} of ${k.onTimeBasis}`}
      />
      <Formula label="Delayed" how="on the road and past the ETA right now" result={formatNumber(k.delayed)} />
      <Formula label="At risk" how="on the road, ETA not passed, but the estimate (straight line × 1.3 at 25 / 40 km/h) lands after it" result={formatNumber(k.atRisk)} />
      <Formula label="Average transit" how="first reading outside the plant fence → arrival, for shipments that arrived" result={k.avgTransitHours === null ? "–" : `${k.avgTransitHours} h`} />
    </Explain>
  );
}

function DetentionExplain({ o }: { o: Overview }) {
  const k = o.shipments;
  return (
    <Explain title="How is detention worked out?">
      <Formula label="Works detention" how="gate-out (dt_trip_start) − booking / plant entry (dt_booking): declared by the TMS" result={minutes(k.avgWorksDetentionMinutes)} />
      <Formula
        label="Hold after gate-out"
        how="first GPS reading outside the plant geofence − gate-out: what the TMS does not see (the truck is out of the gate, not out of the plant)"
        result={minutes(k.avgOriginHoldMinutes)}
      />
    </Explain>
  );
}

function ShipmentTiles({ o, base }: { o: Overview; base: string }) {
  const k = o.shipments;
  return (
    <Stats columns={5}>
      <Tile label="On the road" icon={Route} value={k.active} foot={`${k.atOrigin} still in the plant fence · ${k.inTransit} in transit`} href={`${base}/shipments`} big />
      <Tile label="Delayed" icon={TriangleAlert} value={k.delayed} tone={k.delayed > 0 ? "bad" : "good"} foot={`${k.atRisk} more at risk of missing the ETA`} />
      <Tile label="Arrived today" icon={MapPinned} value={k.arrivedToday} foot={`${k.arrived} in ${o.periodDays} days`} />
      <Tile
        label="On-time delivery"
        icon={Clock}
        value={k.onTimePct === null ? "–" : `${k.onTimePct}`}
        unit={k.onTimePct === null ? undefined : "%"}
        progress={k.onTimePct === null ? undefined : k.onTimePct / 100}
        tone={k.onTimePct !== null && k.onTimePct < 85 ? "warn" : undefined}
        foot={`${k.onTimeBasis} arrivals with an ETA`}
      />
      <Tile label="GPS silent on the road" icon={Radio} value={k.gpsSilent} tone={k.gpsSilent > 0 ? "warn" : undefined} foot="no reading for over an hour" />
    </Stats>
  );
}

/** A transporter's day: its trucks, the shipments it carries, what needs a phone call now. */
export function TransporterOverview({ asDeveloper }: { asDeveloper?: boolean }) {
  const poll = useOverviewData();
  const viewer = useViewer();
  const o = poll.data;
  const base = asDeveloper ? "/tms" : "/transporter";
  return (
    <div className="stack">
      <PageHeader
        title={asDeveloper ? "As a transporter" : `Good day${viewer.tenantName ? `, ${viewer.tenantName}` : ""}`}
        description="Your trucks and the shipments you carry, live from their GPS. Delays and silent trucks are at the top."
        actions={asDeveloper ? <CompanyPicker kinds={["TRANSPORTER"]} /> : undefined}
      />
      {!o ? (
        <Empty>{poll.error ?? "Loading."}</Empty>
      ) : (
        <>
          {o.fleet && (
            <Stats columns={5}>
              <Tile label="Trucks" icon={Truck} value={o.fleet.vehicles} foot={`${o.fleet.withDevice} with a GPS device`} href={asDeveloper ? undefined : "/transporter/fleet"} big />
              <Tile label="Moving now" icon={Gauge} value={o.fleet.moving} tone="good" foot={`${o.fleet.standing} standing`} />
              <Tile label="Offline" icon={Radio} value={o.fleet.offline + o.fleet.never_reported} tone={o.fleet.offline > 0 ? "warn" : undefined} foot={`${o.fleet.no_device} without a device`} />
              <Tile
                label="Utilisation"
                icon={Route}
                value={o.fleet.utilisationPct === null ? "–" : `${o.fleet.utilisationPct}`}
                unit={o.fleet.utilisationPct === null ? undefined : "%"}
                progress={o.fleet.utilisationPct === null ? undefined : o.fleet.utilisationPct / 100}
                foot={`${o.fleet.onShipment} trucks on a shipment`}
              />
              <Tile label="Driven today" icon={ChartLine} value={o.today ? `${o.today.km}` : "–"} unit="km" foot={o.today ? `${o.today.overspeedEvents} over-speed · ${o.today.idleHours} h idling` : undefined} />
            </Stats>
          )}
          <ShipmentTiles o={o} base={base} />
          <div className="grid-main-side">
            <Card icon={TriangleAlert} title="Needs attention" subtitle="Delayed, at risk, or the truck went silent" flush>
              <ShipmentTable rows={o.attention} compact empty="Nothing needs attention: every shipment on the road is on track." />
            </Card>
            <Card icon={Siren} title="Latest alerts" subtitle="Your trucks, and hired trucks while they carry your shipments">
              <EventFeed events={o.recentAlerts} empty="No alerts in the last 7 days." />
            </Card>
          </div>
          <div className="grid-2">
            <Card icon={Timer} title="Detention" subtitle={`Average over ${o.periodDays} days`}>
              <Stats columns={2}>
                <Tile label="In the plant" value={minutes(o.shipments.avgWorksDetentionMinutes)} foot="booking → gate-out" />
                <Tile label="After the gate-out" value={minutes(o.shipments.avgOriginHoldMinutes)} foot="gate-out → out of the plant fence" />
              </Stats>
              <DetentionExplain o={o} />
            </Card>
            <Card icon={Clock} title="Delivery performance" subtitle="How your consigners see you">
              <Stats columns={2}>
                <Tile label="Average transit" value={o.shipments.avgTransitHours === null ? "–" : `${o.shipments.avgTransitHours}`} unit="h" />
                <Tile label="Late by, on average" value={minutes(o.shipments.avgDelayMinutesWhenLate)} foot="of the shipments that arrived late" />
              </Stats>
              <OnTimeExplain o={o} />
            </Card>
          </div>
          {o.lanes && o.lanes.length > 0 && (
            <Card icon={Route} title="Lanes" subtitle="Origin → destination, busiest first" flush>
              <LaneTable rows={o.lanes} />
            </Card>
          )}
        </>
      )}
    </div>
  );
}

/** A consigner's view: its goods on the road, whether they make the ETA, and who carries them how well. */
export function ConsignerOverview({ asDeveloper }: { asDeveloper?: boolean }) {
  const poll = useOverviewData();
  const viewer = useViewer();
  const o = poll.data;
  const base = asDeveloper ? "/tms" : "/consigner";
  return (
    <div className="stack">
      <PageHeader
        title={asDeveloper ? "As a consigner" : viewer.tenantName ?? "Your shipments"}
        description="Every shipment from your plants to the consignee, followed by the truck's GPS: in the plant, on the road, arrived - and whether it makes the ETA."
        actions={asDeveloper ? <CompanyPicker kinds={["CONSIGNER"]} /> : undefined}
      />
      {!o ? (
        <Empty>{poll.error ?? "Loading."}</Empty>
      ) : (
        <>
          <ShipmentTiles o={o} base={base} />
          <Stats columns={4}>
            <Tile label="Booked, not out yet" icon={Building2} value={o.shipments.booked} />
            <Tile label="Average transit" icon={Route} value={o.shipments.avgTransitHours === null ? "–" : `${o.shipments.avgTransitHours}`} unit="h" />
            <Tile label="Works detention" icon={Timer} value={minutes(o.shipments.avgWorksDetentionMinutes)} foot={`then ${minutes(o.shipments.avgOriginHoldMinutes)} to clear the plant fence`} />
            <Tile label="Quantity on the road" icon={Truck} value={formatNumber(o.shipments.quantityOnTheRoad)} foot={`${o.shipments.gpsCoveragePct ?? "–"} % of shipments with GPS`} />
          </Stats>
          <div className="grid-main-side">
            <Card icon={TriangleAlert} title="Needs attention" subtitle="Delayed, at risk, or the truck went silent" flush>
              <ShipmentTable rows={o.attention} compact empty="Nothing needs attention: every shipment on the road is on track." />
            </Card>
            <Card icon={Siren} title="On the road" subtitle="Events while trucks carry your goods">
              <EventFeed events={o.recentAlerts} empty="No alerts in the last 7 days." />
            </Card>
          </div>
          <div className="grid-2">
            <Card icon={Clock} title="Delivery performance" subtitle={`Last ${o.periodDays} days`}>
              <OnTimeExplain o={o} />
              <DetentionExplain o={o} />
            </Card>
            <Card icon={Building2} title="Plants" subtitle="Detention per loading plant" flush>
              <PlantTable rows={o.plants ?? []} />
            </Card>
          </div>
          <Card
            icon={Truck}
            title="Transporters"
            subtitle="Who carries your goods, and how well"
            flush
            actions={
              <Link className="btn soft" href={asDeveloper ? "/tms/consigner" : "/consigner/performance"}>
                Scorecards
              </Link>
            }
          >
            <TransporterTable rows={(o.transporters ?? []).slice(0, 10)} />
          </Card>
        </>
      )}
    </div>
  );
}

/** The GPS provider's TMS view: everything the others see, across every company, plus devices and data quality. */
export function PlatformOverview() {
  const poll = useOverviewData();
  const o = poll.data;
  const p = o?.platform ?? {};
  return (
    <div className="stack">
      <PageHeader
        title="TMS overview"
        description="Every transporter and consigner on the platform: shipments, how they map to the GPS, device health and the data coming in."
        actions={<CompanyPicker kinds={["TRANSPORTER", "CONSIGNER"]} />}
        meta={o ? [["Generated", formatDateTime(o.generatedAt)], ["Period", `${o.periodDays} days`]] : undefined}
      />
      {!o ? (
        <Empty>{poll.error ?? "Loading."}</Empty>
      ) : (
        <>
          <Stats columns={5}>
            <Tile label="Transporters" icon={Truck} value={p.transporters} foot={`${p.consigners} consigners · ${p.users} users`} href="/tms/companies" />
            <Tile label="Devices" icon={Radio} value={p.devices} foot={`${p.mapped_devices} mapped to a truck`} href="/tms/mapping" />
            <Tile label="Reporting now" icon={Gauge} value={p.devices_reporting_15m} tone="good" foot={`${p.devices_silent_1h} silent > 1 h · ${p.devices_never_reported} never`} />
            <Tile label="Shipments" icon={Route} value={o.shipments.total} foot={`${p.shipments_all_time} all time · ${p.imports_7d} uploads in 7 days`} href="/tms/shipments" />
            <Tile
              label="Without GPS"
              icon={TriangleAlert}
              value={o.shipments.noDevice}
              tone={o.shipments.noDevice > 0 ? "warn" : undefined}
              foot={`${o.shipments.imeiMismatch} with another IMEI in the file`}
              href="/tms/mapping"
            />
          </Stats>
          <ShipmentTiles o={o} base="/tms" />
          {o.fleet && (
            <Stats columns={4}>
              <Tile label="Trucks" value={o.fleet.vehicles} foot={`${o.fleet.withDevice} with a device`} />
              <Tile label="Moving now" value={o.fleet.moving} tone="good" />
              <Tile label="Offline" value={o.fleet.offline + o.fleet.never_reported} tone={o.fleet.offline > 0 ? "warn" : undefined} />
              <Tile label="Pipeline" value={p.dead_letters_parked} unit="dead letters" foot={`${p.rejected_24h} frames rejected in 24 h`} href="/storage/dead-letters" />
            </Stats>
          )}
          <div className="grid-main-side">
            <Card icon={TriangleAlert} title="Needs attention" subtitle="Across every company" flush>
              <ShipmentTable rows={o.attention} compact empty="Nothing needs attention." />
            </Card>
            <Card icon={Siren} title="Latest events">
              <EventFeed events={o.recentAlerts} empty="No events in the last 7 days." />
            </Card>
          </div>
          <Card icon={Truck} title="Transporters" subtitle="Performance per transporter" flush>
            <TransporterTable rows={(o.transporters ?? []).slice(0, 15)} />
          </Card>
          <div className="grid-2">
            <Card icon={Route} title="Lanes" flush>
              <LaneTable rows={o.lanes ?? []} />
            </Card>
            <Card icon={Building2} title="Plants" flush>
              <PlantTable rows={o.plants ?? []} />
            </Card>
          </div>
        </>
      )}
    </div>
  );
}
