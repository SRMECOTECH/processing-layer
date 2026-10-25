"use client";

import { AnimatePresence, motion } from "framer-motion";
import { Bookmark, Cloud, LogOut, MessageCircleMore, PhoneCall, Settings, User, Video } from "lucide-react";
import { useState } from "react";
import { DotsIcon, LogoMark } from "./art";
import { EASE } from "./kit";

export type PageId = "videos" | "profile" | "dashboard" | "saved" | "cloud" | "messages" | "settings" | "calls";

type IconProps = { size?: number; strokeWidth?: number };

export const NAV: { id: PageId; label: string; icon: React.ComponentType<IconProps> }[] = [
  { id: "videos", label: "Videos", icon: Video },
  { id: "profile", label: "Profile", icon: User },
  { id: "dashboard", label: "Dashboard", icon: DotsIcon },
  { id: "saved", label: "Saved", icon: Bookmark },
  { id: "cloud", label: "Cloud", icon: Cloud },
  { id: "messages", label: "Messages", icon: MessageCircleMore },
  { id: "settings", label: "Settings", icon: Settings },
  { id: "calls", label: "Calls", icon: PhoneCall },
];

const SPRING = { type: "spring", stiffness: 400, damping: 32 } as const;

/**
 * The floating black capsule. On load it grows down from the top, then the logo and icons fade in one by one.
 * The lavender circle and the black notch on the right edge slide to the active item (shared layoutIds).
 */
export function Sidebar({ page, onNavigate }: { page: PageId; onNavigate: (p: PageId) => void }) {
  return (
    <motion.aside
      className="relative z-10 flex w-16 shrink-0 flex-col items-center rounded-full bg-ink pt-[22px] pb-[18px]"
      style={{ originY: 0 }}
      initial={{ scaleY: 0 }}
      animate={{ scaleY: 1 }}
      transition={{ duration: 0.5, ease: EASE }}
      aria-label="Workspace Studio"
    >
      <Appear n={0}>
        <div className="grid size-10 place-items-center">
          <LogoMark />
        </div>
      </Appear>

      <nav className="mt-[48px] flex flex-col gap-[30px]">
        {NAV.map((item, n) => (
          <Appear key={item.id} n={n + 1}>
            <NavButton label={item.label} active={page === item.id} onClick={() => onNavigate(item.id)}>
              <item.icon size={18} strokeWidth={1.75} />
            </NavButton>
          </Appear>
        ))}
      </nav>

      <div className="mt-auto">
        <Appear n={NAV.length + 1}>
          <NavButton label="Exit studio" href="/">
            <LogOut size={18} strokeWidth={1.75} />
          </NavButton>
        </Appear>
      </div>
    </motion.aside>
  );
}

function Appear({ n, children }: { n: number; children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay: 0.3 + n * 0.04, ease: EASE }}
    >
      {children}
    </motion.div>
  );
}

function NavButton({
  label,
  active = false,
  onClick,
  href,
  children,
}: {
  label: string;
  active?: boolean;
  onClick?: () => void;
  href?: string;
  children: React.ReactNode;
}) {
  const [hover, setHover] = useState(false);
  const className = `relative grid size-10 place-items-center rounded-full transition-colors duration-200 ${
    active ? "text-ink" : "text-icon hover:text-white"
  }`;
  const inner = (
    <>
      {active && (
        <>
          <motion.span layoutId="nav-circle" transition={SPRING} className="absolute inset-0 rounded-full bg-lav-nav" />
          {/* 12px from the item to the sidebar edge, then a 4px gap before the notch. */}
          <motion.span
            layoutId="nav-notch"
            transition={SPRING}
            className="absolute top-1/2 left-[56px] h-5 w-2.5 -translate-y-1/2 rounded-r-full bg-ink"
          />
        </>
      )}
      <span className="relative">{children}</span>
      <AnimatePresence>
        {hover && (
          <motion.span
            role="tooltip"
            className="pointer-events-none absolute top-1/2 left-[70px] -mt-[11px] rounded-lg bg-ink px-2.5 py-1 text-[11px] leading-[14px] whitespace-nowrap text-white shadow-float"
            initial={{ opacity: 0, x: -4 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -4 }}
            transition={{ duration: 0.16 }}
          >
            {label}
          </motion.span>
        )}
      </AnimatePresence>
    </>
  );
  const events = {
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    onFocus: () => setHover(true),
    onBlur: () => setHover(false),
    "aria-label": label,
  };
  return href ? (
    <a href={href} className={className} {...events}>
      {inner}
    </a>
  ) : (
    <button onClick={onClick} className={className} aria-current={active ? "page" : undefined} {...events}>
      {inner}
    </button>
  );
}
