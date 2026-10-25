"use client";

import { animate, motion } from "framer-motion";
import { createContext, useContext, useEffect, useRef, useState } from "react";
import type { LucideIcon } from "lucide-react";
import { CirclePlus } from "lucide-react";

export const EASE = [0.22, 1, 0.36, 1] as const;
const STAGGER = 0.06;

/** Seconds before a page's first card appears: longer on app load (the sidebar grows first), none on a page switch. */
export const BaseDelay = createContext(0);

/** Delay for the i-th item of a page, in reading order. */
export function useDelay(i: number) {
  return useContext(BaseDelay) + i * STAGGER;
}

/** Blur-in: fades up out of a 10px blur. Everything on a page enters through this. */
export function BlurIn({
  i = 0,
  className,
  children,
  as = "div",
}: {
  i?: number;
  className?: string;
  children?: React.ReactNode;
  as?: "div" | "section";
}) {
  const delay = useDelay(i);
  const Tag = as === "section" ? motion.section : motion.div;
  return (
    <Tag
      className={className}
      initial={{ opacity: 0, filter: "blur(10px)", y: 10 }}
      animate={{ opacity: 1, filter: "blur(0px)", y: 0 }}
      transition={{ duration: 0.5, delay, ease: EASE }}
    >
      {children}
    </Tag>
  );
}

/** White card. Lifts 2px on hover (CSS `translate`, so it never fights framer-motion's `transform`). */
export function Card({ i = 0, className = "", children }: { i?: number; className?: string; children?: React.ReactNode }) {
  return (
    <BlurIn i={i} className={`rounded-card bg-white p-[18px] transition-[translate] duration-200 hover:-translate-y-0.5 ${className}`}>
      {children}
    </BlurIn>
  );
}

/** Heading that reveals word by word with the same blur-in. */
export function RevealText({ text, i = 0, className = "" }: { text: string; i?: number; className?: string }) {
  const delay = useDelay(i);
  return (
    <span className={className} aria-label={text}>
      {text.split(" ").map((word, n) => (
        <motion.span
          key={n}
          aria-hidden
          className="inline-block whitespace-pre"
          initial={{ opacity: 0, filter: "blur(10px)", y: 10 }}
          animate={{ opacity: 1, filter: "blur(0px)", y: 0 }}
          transition={{ duration: 0.5, delay: delay + n * 0.05, ease: EASE }}
        >
          {word + (n < text.split(" ").length - 1 ? " " : "")}
        </motion.span>
      ))}
    </span>
  );
}

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
    const controls = animate(current.current, value, {
      duration: 0.8,
      delay: started.current ? 0 : delay,
      ease: "easeOut",
      onUpdate: (n) => {
        started.current = true;
        current.current = n;
        setShown(n);
      },
    });
    return () => controls.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  return <span className="tabular-nums">{format(shown)}</span>;
}

/** A bar that grows from nothing to its size: `axis` x grows width, y grows height. */
export function GrowBar({
  size,
  axis = "x",
  i = 0,
  n = 0,
  className = "",
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
  const prop = axis === "x" ? "width" : "height";
  return (
    <motion.div
      className={className}
      initial={{ [prop]: "0%" }}
      animate={{ [prop]: `${size}%` }}
      transition={{ duration: 0.7, delay, ease: EASE }}
    >
      {children}
    </motion.div>
  );
}

export function CardTitle({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return <h3 className={`text-[17px] leading-tight ${className}`}>{children}</h3>;
}

export function PlusButton({ label }: { label: string }) {
  return (
    <button aria-label={label} className="press grid size-6 place-items-center rounded-full text-text-2 hover:text-ink">
      <CirclePlus size={17} strokeWidth={1.6} />
    </button>
  );
}

export function PillButton({
  icon: Icon,
  children,
  tone = "ink",
  className = "",
  onClick,
  disabled,
}: {
  icon?: LucideIcon;
  children: React.ReactNode;
  tone?: "ink" | "lav" | "grey";
  className?: string;
  onClick?: () => void;
  disabled?: boolean;
}) {
  const tones = {
    ink: "bg-ink text-white",
    lav: "bg-lav text-text",
    grey: "bg-[#bfbcc0] text-white",
  };
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`press inline-flex h-9 shrink-0 items-center gap-2 rounded-full px-4 text-[12px] font-medium disabled:cursor-default disabled:hover:filter-none ${tones[tone]} ${className}`}
    >
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
    <div className="flex items-center gap-0.5">
      {options.map((o) => (
        <button
          key={o}
          onClick={() => onChange(o)}
          className={`relative h-7 rounded-full px-3 text-[11px] transition-colors ${o === value ? "text-white" : "text-text-2 hover:text-text"}`}
        >
          {o === value && (
            <motion.span
              layoutId={`seg-${id}`}
              className="absolute inset-0 rounded-full bg-ink"
              transition={{ type: "spring", stiffness: 400, damping: 32 }}
            />
          )}
          <span className="relative">{o}</span>
        </button>
      ))}
    </div>
  );
}

export function Avatar({ img, size = 32, ring = false, className = "" }: { img: number; size?: number; ring?: boolean; className?: string }) {
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      src={`https://i.pravatar.cc/100?img=${img}`}
      alt=""
      width={size}
      height={size}
      style={{ width: size, height: size }}
      className={`shrink-0 rounded-full bg-lav-soft object-cover ${ring ? "ring-2 ring-white" : ""} ${className}`}
    />
  );
}
