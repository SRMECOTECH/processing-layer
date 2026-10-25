"use client";

import { CircleCheck, RefreshCw, ShieldCheck, SlidersHorizontal } from "lucide-react";
import { CompareChart } from "@/components/ops/charts";
import { Card, DemoButton, PageTitle, Tag } from "@/components/ops/kit";

const CAN_SPEED = [18, 21, 25, 29, 33, 37, 42, 47, 53, 59, 66, 72, 74, 69, 54, 38];
const HAVERSINE = [18, 21, 24, 28, 33, 36, 41, 45, 50, 53, 55, 56, 55, 52, 46, 36];

export default function OverspeedPage() {
  return (
    <div className="stack">
      <PageTitle
        title="Over-speed verification"
        subtitle="An over-speed alert is only raised when the hardware speed counter and the geodesic speed (Haversine distance over elapsed time) agree. A large gap points to a tampered or faulty sensor."
      />

      <Card
        icon={ShieldCheck}
        title="GPS device log detection and over-speed dual verification"
        badges={
          <>
            <Tag tone="accent">PRIMARY DUAL-VERIFIER</Tag>
            <Tag tone="neutral" mono>
              RULE_ID: HAVERSINE_SPEED_DELTA_09
            </Tag>
          </>
        }
        subtitle="Hardware CAN-bus speed counter cross-audited against geodesic Haversine distance over elapsed delta timestamp (Δs/Δt)."
        live={false}
        actions={
          <div style={{ textAlign: "right" }}>
            <div className="faint" style={{ fontSize: 12 }}>
              Selected fleet profile
            </div>
            <div style={{ fontSize: 16, fontWeight: 650 }}>Logistics Alpha (Interstate)</div>
          </div>
        }
      >
        <div className="grid-main-side">
          <div className="inner">
            <div style={{ display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: 10, marginBottom: 6 }}>
              <strong>Raw hardware telemetry vs geodesic Haversine</strong>
              <span className="mono faint" style={{ fontSize: 12 }}>
                T-window: 60s
              </span>
            </div>
            <div className="legend-row">
              <span>
                <i />
                GPS CAN (km/h)
              </span>
              <span>
                <i className="dashed" />
                Haversine calc (km/h)
              </span>
              <span className="bad">
                <i className="limit" />
                Discrepancy alert
              </span>
            </div>
            <CompareChart
              primary={CAN_SPEED}
              secondary={HAVERSINE}
              limit={60}
              flagFrom={10}
              flagTo={13}
              flagLabel="SPOOF DETECTED (Δ > 15%)"
              limitLabel="CRITICAL LIMIT: 60 km/h"
              height={230}
            />
            <div className="grid-4" style={{ marginTop: 14 }}>
              <div className="tile" style={{ background: "var(--surface)" }}>
                <div className="tile-label">Logged CAN speed</div>
                <div className="tile-value accent">
                  74.2 <span className="tile-unit">km/h</span>
                </div>
              </div>
              <div className="tile" style={{ background: "var(--surface)" }}>
                <div className="tile-label">Haversine calculated</div>
                <div className="tile-value">
                  54.8 <span className="tile-unit">km/h</span>
                </div>
              </div>
              <div className="tile" style={{ background: "var(--surface)" }}>
                <div className="tile-label">Variance (Δ speed)</div>
                <div className="tile-value bad">
                  +19.4 <span className="tile-unit">km/h</span>
                </div>
              </div>
              <div className="tile" style={{ background: "var(--surface)" }}>
                <div className="tile-label">Validation verdict</div>
                <div className="bad mono" style={{ fontWeight: 600, marginTop: 6 }}>
                  TAMPER_ALERT
                </div>
              </div>
            </div>
          </div>

          <div className="inner">
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 14 }}>
              <strong>Rule engine config</strong>
              <span className="mono accent-text" style={{ fontSize: 12 }}>
                v2.4 Active
              </span>
            </div>
            <div className="field" style={{ marginBottom: 12 }}>
              <span className="field-label">Entity policy authority</span>
              <span className="field-value" style={{ justifyContent: "space-between" }}>
                Logistics Alpha (Hazardous Material) <CircleCheck size={16} className="accent-text" />
              </span>
            </div>
            <div className="grid-2" style={{ gap: 10, marginBottom: 12 }}>
              <div className="field">
                <span className="field-label">Max ceiling speed</span>
                <span className="field-value mono">60 km/h</span>
              </div>
              <div className="field">
                <span className="field-label">Continuous window</span>
                <span className="field-value mono">60 seconds</span>
              </div>
            </div>
            <div className="field-label" style={{ marginBottom: 6 }}>
              Violation trigger strategy
            </div>
            <div className="field-value" style={{ justifyContent: "space-between", marginBottom: 8 }}>
              <span>Warning buffer (50 to 60 km/h)</span>
              <Tag mono>Log Event</Tag>
            </div>
            <div className="field-value" style={{ justifyContent: "space-between", background: "var(--bad-soft)", color: "var(--bad)", marginBottom: 12 }}>
              <span>Critical breach (&gt;60 km/h + Haversine &gt; 58)</span>
              <Tag tone="bad" mono>
                Auto-Dispatch
              </Tag>
            </div>
            <div className="code">
              {`// Mathematical cross-check formula
d = 2R · asin(√(sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)))
speed_calc = (d / (t2 - t1)) · 3600`}
            </div>
            <div className="card-actions">
              <DemoButton primary icon={RefreshCw}>
                Re-evaluate Haversine pipeline
              </DemoButton>
              <DemoButton icon={SlidersHorizontal}>Edit rule</DemoButton>
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}
