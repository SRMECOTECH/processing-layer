"use client";

import { Bookmark, Cloud, LayoutGrid, LogOut, MessageCircleMore, Moon, PhoneCall, Settings, Sun, User, Video } from "@/components/icons";
import { SlideMarker } from "@/components/slide-marker";
import { DotsIcon, LogoMark } from "./art";
import { cx } from "./kit";
import { signOut } from "@/lib/auth-actions";
import { useTheme } from "./use-theme";
import s from "./sidebar.module.css";

export type PageId = "videos" | "profile" | "dashboard" | "saved" | "cloud" | "messages" | "settings" | "calls";

type NavIconProps = { size?: number; strokeWidth?: number };

export const NAV: { id: PageId; label: string; icon: React.ComponentType<NavIconProps> }[] = [
  { id: "videos", label: "Videos", icon: Video },
  { id: "profile", label: "Profile", icon: User },
  { id: "dashboard", label: "Dashboard", icon: DotsIcon },
  { id: "saved", label: "Saved", icon: Bookmark },
  { id: "cloud", label: "Cloud", icon: Cloud },
  { id: "messages", label: "Messages", icon: MessageCircleMore },
  { id: "settings", label: "Settings", icon: Settings },
  { id: "calls", label: "Calls", icon: PhoneCall },
];

/**
 * The floating black capsule. On load it grows down from the top, then the logo and icons fade in one by one.
 * The lavender circle and the black notch on the right edge slide to the active item. At the bottom: the
 * light/dark switch, the way back to the console and sign out.
 */
export function Sidebar({ page, onNavigate }: { page: PageId; onNavigate: (p: PageId) => void }) {
  const [theme, toggleTheme] = useTheme();
  const ThemeIcon = theme === "dark" ? Sun : Moon;

  return (
    <aside className={s.sidebar} aria-label="Workspace Studio">
      <div className={cx(s.appear, s.logo)} style={{ "--n": 0 } as React.CSSProperties}>
        <LogoMark />
      </div>

      <nav className={s.nav}>
        {NAV.map((item, n) => (
          <NavButton key={item.id} n={n + 1} label={item.label} active={page === item.id} onClick={() => onNavigate(item.id)}>
            <item.icon size={18} strokeWidth={1.75} />
          </NavButton>
        ))}
      </nav>

      <div className={s.bottom}>
        <NavButton n={NAV.length + 1} label={theme === "dark" ? "Light mode" : "Dark mode"} onClick={toggleTheme}>
          <ThemeIcon size={18} strokeWidth={1.75} />
        </NavButton>
        <NavButton n={NAV.length + 2} label="Back to console" href="/">
          <LayoutGrid size={18} strokeWidth={1.75} />
        </NavButton>
        {/* A form, so signing out is a POST (a link on another site cannot sign you out). */}
        <form action={signOut}>
          <NavButton n={NAV.length + 3} label="Sign out" submit>
            <LogOut size={18} strokeWidth={1.75} />
          </NavButton>
        </form>
      </div>
    </aside>
  );
}

function NavButton({
  n,
  label,
  active = false,
  onClick,
  href,
  submit = false,
  children,
}: {
  n: number;
  label: string;
  active?: boolean;
  onClick?: () => void;
  href?: string;
  submit?: boolean;
  children: React.ReactNode;
}) {
  const inner = (
    <>
      {active && (
        <>
          <SlideMarker id="studio-nav-circle" className={s.circle} />
          {/* 12px from the item to the sidebar edge, then a 4px gap before the notch. */}
          <SlideMarker id="studio-nav-notch" className={s.notch} />
        </>
      )}
      <span className={s.glyph}>{children}</span>
      <span role="tooltip" className={s.tip}>
        {label}
      </span>
    </>
  );
  const props = { className: cx(s.appear, s.item), style: { "--n": n } as React.CSSProperties, "aria-label": label };
  return href ? (
    <a href={href} {...props}>
      {inner}
    </a>
  ) : (
    <button type={submit ? "submit" : "button"} onClick={onClick} aria-current={active ? "page" : undefined} {...props}>
      {inner}
    </button>
  );
}
