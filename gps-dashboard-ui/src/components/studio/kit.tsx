"use client";

import { createContext, useContext, useEffect, useRef, useState, type CSSProperties } from "react";
import { CirclePlus, type IconComponent } from "@/components/icons";
import { SlideMarker } from "@/components/slide-marker";
import s from "./kit.module.css";

export { s as kit };

const STAGGER = 0.06;

/** Seconds before a page's first card appears: longer on app load (the sidebar grows first), none on a page switch. */
export const BaseDelay = createContext(0);

/** Delay for the i-th item of a page, in reading order. */
export function useDelay(i: number) {
  return useContext(BaseDelay) + i * STAGGER;
}

/** Joins class names, skipping empty ones. */
export function cx(...names: (string | false | null | undefined)[]) {
  return names.filter(Boolean).join(" ");
}

/** CSS animation delay in seconds, as an inline style. */
export function after(seconds: number, style?: CSSProperties): CSSProperties {
  return { ...style, animationDelay: `${seconds}s` };
}

/** Blur-in: fades up out of a 10px blur. Everything on a page enters through this. */
export function BlurIn({
  i = 0,
  className,
  style,
  children,
  as: Tag = "div",
}: {
  i?: number;
  className?: string;
  style?: CSSProperties;
  children?: React.ReactNode;
  as?: "div" | "section" | "li";
}) {
  const delay = useDelay(i);
  return (
    <Tag className={cx(s.blurIn, className)} style={after(delay, style)}>
      {children}
    </Tag>
  );
}

/** White card that blurs in and lifts 2px on hover. `tile` gives the smaller radius and padding. */
export function Card({ i = 0, tile = false, className, children }: { i?: number; tile?: boolean; className?: string; children?: React.ReactNode }) {
  return (
    <BlurIn i={i} className={cx(s.card, tile && s.tile, className)}>
      {children}
    </BlurIn>
  );
}

/** Heading that reveals word by word with the same blur-in. */
export function RevealText({ text, i = 0 }: { text: string; i?: number }) {
  const delay = useDelay(i);
  const words = text.split(" ");
  return (
    <span aria-label={text}>
      {words.map((word, n) => (
        <span key={n} aria-hidden className={s.word} style={after(delay + n * 0.05)}>
          {word + (n < words.length - 1 ? " " : "")}
        </span>
      ))}
    </span>
  );
}

const easeOut = (t: number) => 1 - (1 - t) ** 3;

/**
 * Counts up from 80% of the value over 0.8s once its card has appeared; a later change of `value`
 * (a toggle) counts from what is shown, without the entry delay.
 */
export function CountUp({
  value,
  i = 0,
  format = (n) => Math.round(n).toLocaleString("en-US"),
}: {
  value: number;
  i?: number;
  format?: (n: number) => string;
}) {
  const delay = useDelay(i);
  const [shown, setShown] = useState(value * 0.8);
  const current = useRef(value * 0.8);
  const started = useRef(false);

  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      current.current = value;
      setShown(value);
      return;
    }
    let frame = 0;
    const timer = window.setTimeout(
      () => {
        started.current = true;
        const from = current.current;
        let start = 0;
        const tick = (now: number) => {
          start ||= now;
          const t = Math.min(1, (now - start) / 800);
          current.current = from + (value - from) * easeOut(t);
          setShown(current.current);
          if (t < 1) frame = requestAnimationFrame(tick);
        };
        frame = requestAnimationFrame(tick);
      },
      started.current ? 0 : delay * 1000,
    );
    return () => {
      window.clearTimeout(timer);
      cancelAnimationFrame(frame);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  return <span className={s.num}>{format(shown)}</span>;
}

/** A bar that grows from nothing to its size: `axis` x grows width, y grows height. */
export function GrowBar({
  size,
  axis = "x",
  i = 0,
  n = 0,
  className,
  children,
}: {
  size: number;
  axis?: "x" | "y";
  i?: number;
  n?: number;
  className?: string;
  children?: React.ReactNode;
}) {
  const delay = useDelay(i) + 0.15 + n * 0.05;
  const style = axis === "x" ? { width: `${size}%` } : { height: `${size}%` };
  return (
    <div className={cx(axis === "x" ? s.growX : s.growY, className)} style={after(delay, style)}>
      {children}
    </div>
  );
}

export function CardTitle({ children, className }: { children: React.ReactNode; className?: string }) {
  return <h3 className={cx(s.cardTitle, className)}>{children}</h3>;
}

export function PlusButton({ label }: { label: string }) {
  return (
    <button aria-label={label} className={cx(s.press, s.plus)}>
      <CirclePlus size={17} strokeWidth={1.6} />
    </button>
  );
}

export function PillButton({
  icon: Icon,
  children,
  tone = "ink",
  className,
  onClick,
  disabled,
}: {
  icon?: IconComponent;
  children: React.ReactNode;
  tone?: "ink" | "lav" | "grey";
  className?: string;
  onClick?: () => void;
  disabled?: boolean;
}) {
  return (
    <button onClick={onClick} disabled={disabled} className={cx(s.press, s.pill, s[tone], className)}>
      {Icon && <Icon size={14} strokeWidth={1.9} />}
      {children}
    </button>
  );
}

/** Segmented pills: the black active pill slides between options. */
export function Segmented<T extends string>({
  id,
  options,
  value,
  onChange,
}: {
  id: string;
  options: readonly T[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div className={s.segmented}>
      {options.map((o) => (
        <button key={o} onClick={() => onChange(o)} aria-pressed={o === value} className={s.segment}>
          {o === value && <SlideMarker id={`seg-${id}`} className={s.segmentPill} duration={360} />}
          <span className={s.segmentLabel}>{o}</span>
        </button>
      ))}
    </div>
  );
}

const AVATAR_TINTS = [s.tintLav, s.tintPink, s.tintMint, s.tintSky];

/** A person's initials on a pastel circle; the colour is fixed per name. Drawn locally, so no image host is needed. */
export function Avatar({ name, size = 32, ring = false, className }: { name: string; size?: number; ring?: boolean; className?: string }) {
  const initials = name
    .split(" ")
    .slice(0, 2)
    .map((part) => part[0])
    .join("");
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return (
    <span
      role="img"
      aria-label={name}
      title={name}
      className={cx(s.avatar, AVATAR_TINTS[hash % AVATAR_TINTS.length], ring && s.ring, className)}
      style={{ width: size, height: size, fontSize: Math.round(size * 0.36) }}
    >
      {initials}
    </span>
  );
}

/** Page heading with a lead line and actions on the right. */
export function PageTop({ title, lead, children }: { title: string; lead: string; children?: React.ReactNode }) {
  return (
    <div className={s.pageTop}>
      <div>
        <h1 className={s.pageTitle}>
          <RevealText text={title} />
        </h1>
        <BlurIn i={1} className={s.pageLead}>
          {lead}
        </BlurIn>
      </div>
      {children && (
        <BlurIn i={1} className={s.pageActions}>
          {children}
        </BlurIn>
      )}
    </div>
  );
}
