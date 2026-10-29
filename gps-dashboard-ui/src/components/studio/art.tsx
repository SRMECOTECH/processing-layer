"use client";

import { useId } from "react";
import a from "./art.module.css";

/** useId() returns characters that break `url(#…)` references, so keep only safe ones. */
function useSvgId(name: string) {
  return `${name}-${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
}

/** The "//" mark with a tick at the top right. */
export function LogoMark({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden>
      <path d="M4.5 20 L10 5" stroke="white" strokeWidth="3.4" strokeLinecap="round" />
      <path d="M11 20 L16.5 5" stroke="white" strokeWidth="3.4" strokeLinecap="round" />
      <path d="M19.5 4.2 L21 3" stroke="white" strokeWidth="2.6" strokeLinecap="round" />
    </svg>
  );
}

/** Four dots in a square (the dashboard icon in the sidebar). */
export function DotsIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <circle cx="8.5" cy="8.5" r="1.9" />
      <circle cx="15.5" cy="8.5" r="1.9" />
      <circle cx="8.5" cy="15.5" r="1.9" />
      <circle cx="15.5" cy="15.5" r="1.9" />
    </svg>
  );
}

// A swirling petal pointing up from the centre, leaning right, so rotated copies read as a pinwheel.
const PETAL = "M0 -6 C 24 -18, 44 -68, 16 -104 C -6 -112, -32 -88, -24 -52 C -18 -30, -9 -16, 0 -6 Z";

/** Soft-lit lavender flower / pinwheel: two rings of gradient petals, each casting a faint shadow. Turns slowly. */
export function Flower({ className = "", spin = true }: { className?: string; spin?: boolean }) {
  const id = useSvgId("flower");
  const outer = Array.from({ length: 12 }, (_, k) => k * 30);
  const inner = Array.from({ length: 8 }, (_, k) => k * 45 + 18);
  return (
    <svg viewBox="-125 -125 250 250" className={`${spin ? a.spin : ""} ${className}`} aria-hidden>
      <defs>
        <linearGradient id={`${id}-p`} x1="0" y1="1" x2="0.3" y2="0">
          <stop offset="0" stopColor="#a993e6" />
          <stop offset="0.55" stopColor="#c7b3f0" />
          <stop offset="1" stopColor="#ecdcf7" />
        </linearGradient>
        <linearGradient id={`${id}-q`} x1="0" y1="1" x2="0.3" y2="0">
          <stop offset="0" stopColor="#9c82e0" />
          <stop offset="1" stopColor="#dcc9f4" />
        </linearGradient>
        <radialGradient id={`${id}-h`} cx="0.35" cy="0.3" r="0.6">
          <stop offset="0" stopColor="#fff" stopOpacity="0.6" />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </radialGradient>
        <radialGradient id={`${id}-c`} cx="0.4" cy="0.35" r="0.7">
          <stop offset="0" stopColor="#f6eefc" />
          <stop offset="1" stopColor="#b39de9" />
        </radialGradient>
        <filter id={`${id}-s`} x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="0.5" result="soft" />
          <feDropShadow in="soft" dx="0" dy="3" stdDeviation="4" floodColor="#7d5fd0" floodOpacity="0.3" />
        </filter>
      </defs>
      {outer.map((a) => (
        <g key={`o${a}`} transform={`rotate(${a})`} filter={`url(#${id}-s)`}>
          <path d={PETAL} fill={`url(#${id}-p)`} />
          <path d={PETAL} fill={`url(#${id}-h)`} stroke="rgba(255,255,255,0.55)" strokeWidth="0.8" />
        </g>
      ))}
      {inner.map((a) => (
        <g key={`i${a}`} transform={`rotate(${a}) scale(0.56)`} filter={`url(#${id}-s)`}>
          <path d={PETAL} fill={`url(#${id}-q)`} />
          <path d={PETAL} fill={`url(#${id}-h)`} stroke="rgba(255,255,255,0.6)" strokeWidth="1.2" />
        </g>
      ))}
      <circle r="13" fill={`url(#${id}-c)`} filter={`url(#${id}-s)`} />
    </svg>
  );
}

const round = (n: number) => Math.round(n * 100) / 100;

/** Pink/lavender coil: a stack of thick ribbon rings, each slightly tilted so the stack looks twisted. */
export function Spiral({ className = "" }: { className?: string }) {
  const id = useSvgId("coil");
  const rings = Array.from({ length: 10 }, (_, k) => k);
  return (
    <svg viewBox="0 0 160 230" className={className} aria-hidden>
      <defs>
        <linearGradient id={`${id}-a`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#cbbfea" />
          <stop offset="0.45" stopColor="#f2c8ee" />
          <stop offset="1" stopColor="#c6b6ec" />
        </linearGradient>
        <linearGradient id={`${id}-b`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#f2c8ee" />
          <stop offset="0.55" stopColor="#ddc9f3" />
          <stop offset="1" stopColor="#f6d6f0" />
        </linearGradient>
        <filter id={`${id}-s`} x="-20%" y="-40%" width="140%" height="180%">
          <feDropShadow dx="0" dy="3" stdDeviation="2.6" floodColor="#b07cc8" floodOpacity="0.35" />
        </filter>
      </defs>
      {rings.map((k) => {
        // Rounded so server and client render the same attribute strings (Math.sin differs in the last digit).
        const cx = round(80 + Math.sin(k * 0.9) * 9);
        const cy = 24 + k * 19.5;
        const rx = round(50 - Math.abs(Math.sin(k * 0.6)) * 6);
        const tilt = round(Math.sin(k * 1.1) * 9);
        return (
          <g key={k} transform={`rotate(${tilt} ${cx} ${cy})`} filter={`url(#${id}-s)`}>
            <ellipse cx={cx} cy={cy} rx={rx} ry={13} fill="none" stroke={`url(#${id}-${k % 2 ? "b" : "a"})`} strokeWidth="13" />
            <ellipse cx={cx} cy={cy - 4} rx={rx - 4} ry={10} fill="none" stroke="rgba(255,255,255,0.55)" strokeWidth="1.6" />
          </g>
        );
      })}
    </svg>
  );
}

export type BlobTone = "violet" | "blue" | "crimson" | "teal";

const BLOB_TONES: Record<BlobTone, { light: string; mid: string; dark: string }> = {
  violet: { light: "#c9a8ff", mid: "#7b4fe0", dark: "#3f1d9c" },
  blue: { light: "#8fa6ff", mid: "#2d4fd8", dark: "#13288f" },
  crimson: { light: "#f59ab4", mid: "#c2264f", dark: "#6e0f2a" },
  teal: { light: "#9be6d6", mid: "#21a08d", dark: "#0b5a50" },
};

/**
 * Glossy brain-like blob on a near-white backdrop: a gradient sphere whose outline is roughened by noise,
 * with specular highlights lit from the top left. `seed` varies the bumps between thumbnails.
 */
export function Blob({ tone = "violet", seed = 3, className = "" }: { tone?: BlobTone; seed?: number; className?: string }) {
  const id = useSvgId("blob");
  const c = BLOB_TONES[tone];
  return (
    // The backdrop is CSS (light or dark) so it fills any box; the drawing keeps its proportions (wide tiles don't blow the blob up).
    <svg viewBox="0 0 200 130" overflow="visible" className={`${a.blob} ${className}`} aria-hidden>
      <defs>
        <radialGradient id={`${id}-g`} cx="0.36" cy="0.3" r="0.75">
          <stop offset="0" stopColor={c.light} />
          <stop offset="0.5" stopColor={c.mid} />
          <stop offset="1" stopColor={c.dark} />
        </radialGradient>
        <filter id={`${id}-f`} x="-20%" y="-20%" width="140%" height="140%">
          <feTurbulence type="fractalNoise" baseFrequency="0.06" numOctaves="2" seed={seed} result="noise" />
          <feDisplacementMap in="SourceGraphic" in2="noise" scale="10" xChannelSelector="R" yChannelSelector="G" result="shape" />
          <feSpecularLighting in="noise" surfaceScale="5" specularConstant="0.8" specularExponent="20" lightingColor="#ffffff" result="spec">
            <fePointLight x="60" y="10" z="90" />
          </feSpecularLighting>
          <feComposite in="spec" in2="shape" operator="in" result="gloss" />
          <feMerge>
            <feMergeNode in="shape" />
            <feMergeNode in="gloss" />
          </feMerge>
        </filter>
        <filter id={`${id}-sh`} x="-50%" y="-200%" width="200%" height="500%">
          <feGaussianBlur stdDeviation="4" />
        </filter>
      </defs>
      <path d="M-200 100 C -120 80, -60 110, -10 96 C 40 76, 80 112, 130 92 S 190 70, 240 84 S 340 104, 400 90" fill="none" strokeWidth="9" opacity="0.7" style={{ stroke: "var(--blob-wave)" }} />
      <ellipse cx="100" cy="108" rx="46" ry="6" fill="#2a1a4a" opacity="0.18" filter={`url(#${id}-sh)`} />
      <ellipse cx="100" cy="62" rx="50" ry="37" fill={`url(#${id}-g)`} filter={`url(#${id}-f)`} />
    </svg>
  );
}
