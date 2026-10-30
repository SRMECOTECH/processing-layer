import { useId } from "react";
import s from "./highway-scene.module.css";

/**
 * The sign-in page's story: an Indian national highway with a steel-coil trailer and a painted goods truck rolling
 * past the steel plant, milestones and the green NH sign, while the trailer's GPS pings the satellite. Inline SVG and
 * CSS only (no animation package): the world scrolls past in three parallax layers, the trucks stay in frame.
 * Everything stands still for visitors who ask for reduced motion.
 *
 * Drawn in a 1000 x 420 box and scaled to cover its slot with the road kept at the bottom, so a short panel loses sky,
 * not road. Each scrolling layer is drawn three times, 1000 apart, and slides by exactly 1000: a seamless loop.
 */
export function HighwayScene({ className = "" }: { className?: string }) {
  const id = useId().replace(/:/g, "");
  const coil = `${id}-coil`;
  const glow = `${id}-glow`;
  return (
    <div className={`${s.scene} ${className}`} aria-hidden>
      <svg viewBox="0 0 1000 420" preserveAspectRatio="xMidYMax slice" className={s.svg}>
        <defs>
          <radialGradient id={coil} cx="0.38" cy="0.34" r="0.75">
            <stop offset="0" stopColor="#f4f6fa" />
            <stop offset="0.55" stopColor="#c4cad8" />
            <stop offset="1" stopColor="#8d96ab" />
          </radialGradient>
          <radialGradient id={glow} cx="0.5" cy="0.5" r="0.5">
            <stop offset="0" className={s.sunGlowStop} />
            <stop offset="1" className={s.sunGlowEnd} />
          </radialGradient>
        </defs>

        {/* sky */}
        <circle cx="840" cy="140" r="78" fill={`url(#${glow})`} />
        <circle cx="840" cy="140" r="30" className={s.sun} />
        <g className={s.satellite}>
          <g transform="translate(188 136) rotate(-18)">
            <rect x="-34" y="-5" width="22" height="10" rx="1.5" className={s.panel} />
            <rect x="12" y="-5" width="22" height="10" rx="1.5" className={s.panel} />
            <rect x="-12" y="-1" width="24" height="2" className={s.panelBar} />
            <rect x="-8" y="-7" width="16" height="14" rx="3" className={s.satBody} />
            <path d="M0 7 L0 12 M-5 15 Q0 9 5 15" className={s.satDish} />
          </g>
        </g>

        {/* far: hills and the steel plant */}
        <g className={s.far}>
          {[-1000, 0, 1000].map((dx) => (
            <g key={dx} transform={`translate(${dx} 0)`}>
              <path d={hills([[0, 236], [180, 204], [340, 232], [520, 196], [700, 230], [860, 208], [1000, 236]], 300)} className={s.hillFar} />
              <SteelPlant />
            </g>
          ))}
        </g>

        {/* middle: fields and trees */}
        <g className={s.mid}>
          {[-1000, 0, 1000].map((dx) => (
            <g key={dx} transform={`translate(${dx} 0)`}>
              <path d={hills([[0, 276], [250, 262], [500, 280], [750, 264], [1000, 276]], 320)} className={s.field} />
              {TREES.map(([x, y, size]) => (
                <Tree key={x} x={x} y={y} size={size} />
              ))}
            </g>
          ))}
        </g>

        {/* the road */}
        <rect x="0" y="312" width="1000" height="86" className={s.road} />
        <rect x="0" y="398" width="1000" height="22" className={s.verge} />
        <line x1="0" y1="316.5" x2="1000" y2="316.5" className={s.edgeLine} />
        <line x1="0" y1="393.5" x2="1000" y2="393.5" className={s.edgeLine} />
        <line x1="-64" y1="355" x2="1064" y2="355" className={s.centreLine} />

        {/* roadside: poles and wires, milestone, the green NH sign - moving with the road */}
        <g className={s.near}>
          {[-1000, 0, 1000].map((dx) => (
            <g key={dx} transform={`translate(${dx} 0)`}>
              <Roadside />
            </g>
          ))}
        </g>

        {/* oncoming truck in the far lane */}
        <g className={s.oncoming}>
          <g transform="translate(0 348) scale(-0.84 0.84)" opacity="0.94">
            <GoodsTruck />
          </g>
        </g>

        {/* the convoy in the near lane */}
        <g className={s.bobSlow}>
          <g transform="translate(205 386)">
            <GoodsTruck />
          </g>
        </g>
        <g className={s.bob}>
          <g transform="translate(415 386)">
            <CoilTrailer coil={coil} />
          </g>
        </g>

        {/* foreground tufts */}
        <g className={s.near}>
          {[-1000, 0, 1000].map((dx) => (
            <g key={dx} transform={`translate(${dx} 0)`}>
              {[40, 190, 330, 470, 610, 760, 900].map((x) => (
                <path key={x} d={`M${x} 420 q2 -12 5 -14 q1 8 2 10 q3 -10 6 -12 q0 9 1 16 z`} className={s.tuft} />
              ))}
            </g>
          ))}
        </g>

        {/* GPS: the trailer's antenna pings the satellite, and its reading floats above it */}
        <line x1="643" y1="291" x2="196" y2="140" className={s.beam} />
        <circle cx="643" cy="291" r="18" className={s.ring} />
        <circle cx="643" cy="291" r="18" className={`${s.ring} ${s.ringLate}`} />
        <g className={s.chip}>
          <rect x="523" y="228" width="240" height="30" rx="15" className={s.chipBox} />
          <path d="M637 258 L643 266 L649 258 Z" className={s.chipBox} />
          <circle cx="543" cy="243" r="4" className={s.liveDot} />
          <text x="555" y="247.5" className={s.chipText}>
            JH 05 CY 2516 · 48 km/h · On time
          </text>
        </g>
      </svg>
    </div>
  );
}

/** A smooth skyline through the points (horizontal at each one, so copies 1000 apart join without a seam). */
function hills(points: [number, number][], bottom: number): string {
  let d = `M${points[0][0]} ${bottom} L${points[0][0]} ${points[0][1]}`;
  for (let k = 1; k < points.length; k++) {
    const [x1, y1] = points[k - 1];
    const [x2, y2] = points[k];
    const half = (x2 - x1) / 2;
    d += ` C${x1 + half} ${y1} ${x2 - half} ${y2} ${x2} ${y2}`;
  }
  return `${d} L${points[points.length - 1][0]} ${bottom} Z`;
}

const TREES: [number, number, number][] = [
  [60, 298, 1],
  [150, 290, 0.7],
  [270, 296, 1.15],
  [420, 300, 0.85],
  [560, 292, 1.05],
  [690, 298, 0.75],
  [830, 294, 1.2],
  [950, 300, 0.8],
];

function Tree({ x, y, size }: { x: number; y: number; size: number }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${size})`}>
      <rect x="-2" y="-14" width="4" height="16" rx="1" className={s.trunk} />
      <circle cx="-9" cy="-20" r="11" className={s.tree} />
      <circle cx="8" cy="-22" r="12" className={s.tree} />
      <circle cx="0" cy="-32" r="12" className={s.treeTop} />
    </g>
  );
}

function SteelPlant() {
  return (
    <g>
      <rect x="560" y="212" width="58" height="44" rx="2" className={s.plant} />
      <rect x="620" y="224" width="44" height="32" rx="2" className={s.plant} />
      <path d="M668 256 L672 214 L690 214 L694 256 Z" className={s.plant} />
      <rect x="578" y="168" width="7" height="46" className={s.plant} />
      <rect x="593" y="178" width="7" height="36" className={s.plant} />
      <rect x="640" y="184" width="6" height="42" className={s.plant} />
      <circle cx="581.5" cy="164" r="6" className={`${s.smoke} ${s.smoke1}`} />
      <circle cx="596.5" cy="174" r="5" className={`${s.smoke} ${s.smoke2}`} />
      <circle cx="643" cy="180" r="5" className={`${s.smoke} ${s.smoke3}`} />
    </g>
  );
}

function Roadside() {
  return (
    <g>
      {[125, 375, 625, 875].map((x) => (
        <g key={x}>
          <path d={`M${x} 250 Q${x + 125} 262 ${x + 250} 250`} className={s.wire} />
          <rect x={x - 1.5} y="246" width="3" height="68" className={s.pole} />
          <rect x={x - 10} y="250" width="20" height="2.5" rx="1" className={s.pole} />
        </g>
      ))}
      {/* the milestone: white stone, yellow top - NH 33, 12 km to Jamshedpur */}
      <g transform="translate(300 316)">
        <path d="M-12 0 L-12 -20 A12 12 0 0 1 12 -20 L12 0 Z" fill="#fbfaf7" stroke="#d8d3c8" strokeWidth="0.8" />
        <path d="M-12 -14 L-12 -20 A12 12 0 0 1 12 -20 L12 -14 Z" fill="#f3c74a" />
        <text x="0" y="-18" textAnchor="middle" className={s.mileTop}>
          NH 33
        </text>
        <text x="0" y="-5" textAnchor="middle" className={s.mileText}>
          JSR 12
        </text>
      </g>
      {/* the green national-highway guide sign */}
      <g transform="translate(760 0)">
        <rect x="-26" y="262" width="3" height="54" className={s.pole} />
        <rect x="23" y="262" width="3" height="54" className={s.pole} />
        <rect x="-46" y="222" width="92" height="42" rx="4" fill="#1f7a4d" />
        <rect x="-42.5" y="225.5" width="85" height="35" rx="2.5" fill="none" stroke="#ffffff" strokeWidth="1.2" />
        <text x="-36" y="239" className={s.signText}>
          Jamshedpur
        </text>
        <text x="36" y="239" textAnchor="end" className={s.signText}>
          12
        </text>
        <text x="-36" y="254" className={s.signText}>
          Ranchi
        </text>
        <text x="36" y="254" textAnchor="end" className={s.signText}>
          118
        </text>
      </g>
    </g>
  );
}

function Wheel({ x }: { x: number }) {
  return (
    <g transform={`translate(${x} -11)`}>
      <g className={s.wheel}>
        <circle r="11" fill="#2b2738" />
        <circle r="5.4" fill="#b9b2ca" />
        {[0, 72, 144, 216, 288].map((a) => (
          <circle key={a} cx={3.3 * Math.cos((a * Math.PI) / 180)} cy={3.3 * Math.sin((a * Math.PI) / 180)} r="0.95" fill="#2b2738" />
        ))}
      </g>
    </g>
  );
}

/** A painted Indian goods truck, facing right, ground at y = 0. */
function GoodsTruck() {
  return (
    <g>
      <rect x="6" y="-25" width="166" height="8" rx="2" fill="#3b3550" />
      {/* tarpaulin over the load, the painted wooden body and its famous tail line */}
      <path d="M4 -70 Q62 -94 120 -70 Z" fill="#f0a43a" />
      <rect x="4" y="-71" width="116" height="48" rx="4" fill="#3f63c4" />
      <path d="M4 -59 H120 M4 -35 H120" stroke="#3554a8" strokeWidth="1.5" />
      <rect x="4" y="-53" width="116" height="13" fill="#e04f3a" />
      <text x="62" y="-43.5" textAnchor="middle" className={s.truckText}>
        HORN OK PLEASE
      </text>
      <path d="M4 -71 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5 l6 -5 l6 5" fill="none" stroke="#ffd166" strokeWidth="1.4" />
      {/* cab, with the decorated visor */}
      <path d="M124 -23 L124 -66 Q124 -72 130 -72 L158 -72 Q166 -72 168 -64 L174 -40 L174 -23 Z" fill="#f2b134" />
      <path d="M128 -72 L128 -80 Q146 -87 164 -78 L164 -72 Z" fill="#e04f3a" />
      <circle cx="136" cy="-77" r="2" fill="#ffd166" />
      <circle cx="146" cy="-79.5" r="2" fill="#ffd166" />
      <circle cx="156" cy="-77.5" r="2" fill="#ffd166" />
      <rect x="124" y="-41" width="50" height="5" fill="#2f8f5b" />
      <rect x="130" y="-66" width="16" height="20" rx="2" fill="#d7e6f7" />
      <path d="M150 -66 L160 -66 Q163 -66 164 -62 L169 -46 L150 -46 Z" fill="#d7e6f7" />
      <rect x="170" y="-27" width="7" height="6" rx="1.5" fill="#3b3550" />
      <circle cx="172" cy="-34" r="2.6" fill="#fff4c2" />
      <rect x="7" y="-21" width="4" height="12" rx="1" fill="#e04f3a" />
      <Wheel x={30} />
      <Wheel x={54} />
      <Wheel x={150} />
    </g>
  );
}

/** The tracked truck: a tractor in the console's colours pulling a trailer of steel coils, facing right. */
function CoilTrailer({ coil }: { coil: string }) {
  return (
    <g>
      <rect x="0" y="-31" width="198" height="8" rx="2" fill="#4a4560" />
      <rect x="8" y="-23" width="180" height="5" fill="#3b3550" />
      {[40, 92, 144].map((cx) => (
        <g key={cx}>
          <rect x={cx - 8} y="-34" width="16" height="4" rx="1" fill="#c98b3a" />
          <circle cx={cx} cy="-51" r="19" fill={`url(#${coil})`} stroke="#8a93a8" strokeWidth="1" />
          <circle cx={cx} cy="-51" r="13" fill="none" stroke="#ffffff" strokeOpacity="0.4" strokeWidth="1.2" />
          <circle cx={cx} cy="-51" r="7.5" fill="#5b6378" />
        </g>
      ))}
      <rect x="168" y="-23" width="3" height="14" fill="#3b3550" />
      <rect x="178" y="-25" width="84" height="8" rx="2" fill="#3b3550" />
      <rect x="199" y="-84" width="4" height="60" rx="1.5" fill="#9aa1b4" />
      <path d="M206 -23 L206 -68 Q206 -74 212 -74 L242 -74 Q250 -74 252 -66 L258 -42 L258 -23 Z" fill="#5a4ea6" />
      <path d="M210 -74 L210 -81 Q230 -88 248 -79 L248 -74 Z" fill="#3f3592" />
      <rect x="206" y="-43" width="52" height="4" fill="#cbbfea" />
      <rect x="212" y="-68" width="16" height="20" rx="2" fill="#dfe8f8" />
      <path d="M234 -68 L244 -68 Q247 -68 248 -64 L253 -48 L234 -48 Z" fill="#dfe8f8" />
      <path d="M228 -82 V-93" stroke="#2b2738" strokeWidth="2" strokeLinecap="round" />
      <circle cx="228" cy="-95" r="2.6" fill="#f3c74a" />
      <rect x="254" y="-27" width="8" height="6" rx="1.5" fill="#3b3550" />
      <circle cx="256" cy="-35" r="2.6" fill="#fff4c2" />
      <Wheel x={22} />
      <Wheel x={46} />
      <Wheel x={222} />
      <Wheel x={246} />
    </g>
  );
}
