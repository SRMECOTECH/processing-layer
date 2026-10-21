"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { ChevronRight, PanelLeftClose, PanelLeftOpen, Search, X } from "lucide-react";
import { SECTIONS, itemContains, sectionFor, type NavGroup, type NavItem } from "@/lib/nav";
import { useOverview } from "@/lib/overview-context";
import { isKafkaFlow } from "@/lib/pipeline";
import { usePreferences } from "@/lib/preferences";

/**
 * The left menu of the current section: groups, items and, under an item, its sub-pages.
 * Collapses to an icon rail on wide screens and becomes a drawer on narrow ones.
 */
export function Sidebar() {
  const pathname = usePathname();
  const section = sectionFor(pathname);
  const { sidebarOpen, toggleSidebar, drawerOpen, setDrawerOpen } = usePreferences();
  const [filter, setFilter] = useState("");
  const [toggled, setToggled] = useState<Record<string, boolean>>({});

  useEffect(() => {
    setDrawerOpen(false);
  }, [pathname, setDrawerOpen]);

  useEffect(() => {
    setFilter("");
  }, [section.href]);

  const groups = useMemo(() => filterGroups(section.groups, filter), [section.groups, filter]);
  const SectionIcon = section.icon;

  return (
    <>
      <div className={`drawer-scrim${drawerOpen ? " open" : ""}`} onClick={() => setDrawerOpen(false)} aria-hidden="true" />
      <aside className={`sidebar${drawerOpen ? " open" : ""}`} aria-label={`${section.label} menu`}>
        <div className="drawer-sections">
          <div className="side-title">Sections</div>
          <div className="drawer-section-list">
            {SECTIONS.map((s) => {
              const Icon = s.icon;
              return (
                <Link key={s.href} href={s.href} className="drawer-section" aria-current={s.href === section.href ? "page" : undefined}>
                  <Icon size={15} />
                  {s.label}
                </Link>
              );
            })}
          </div>
          <button type="button" className="icon-button drawer-close" aria-label="Close the menu" onClick={() => setDrawerOpen(false)}>
            <X size={18} />
          </button>
        </div>

        <div className="side-head">
          <div className="side-section">
            <span className="side-section-icon">
              <SectionIcon size={16} />
            </span>
            <div>
              <div className="side-section-name">{section.label}</div>
              <div className="side-section-desc">{section.description}</div>
            </div>
          </div>
          <label className="side-filter">
            <Search size={14} />
            <input
              type="search"
              placeholder="Filter this menu"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              aria-label={`Filter the ${section.label} menu`}
            />
          </label>
        </div>

        <nav className="side-nav">
          {groups.length === 0 && <p className="side-empty">Nothing in {section.label} matches “{filter}”.</p>}
          {groups.map((group) => (
            <div className="side-group" key={group.title}>
              <div className="side-title">{group.title}</div>
              {group.items.map((item) => (
                <SideItem
                  key={item.href + item.label}
                  item={item}
                  pathname={pathname}
                  open={filter.trim() !== "" || (toggled[item.label] ?? itemContains(item, pathname))}
                  onToggle={() => setToggled((t) => ({ ...t, [item.label]: !(t[item.label] ?? itemContains(item, pathname)) }))}
                />
              ))}
            </div>
          ))}
        </nav>

        <SystemStatus />

        <button type="button" className="side-collapse" onClick={toggleSidebar} aria-label={sidebarOpen ? "Collapse the menu" : "Expand the menu"}>
          {sidebarOpen ? <PanelLeftClose size={16} /> : <PanelLeftOpen size={16} />}
          <span>{sidebarOpen ? "Collapse menu" : ""}</span>
        </button>
      </aside>
    </>
  );
}

function SideItem({ item, pathname, open, onToggle }: { item: NavItem; pathname: string; open: boolean; onToggle: () => void }) {
  const Icon = item.icon;
  const inside = itemContains(item, pathname);
  const current = !item.children && item.href === pathname;

  if (!item.children) {
    return (
      <Link href={item.href} className="side-link" aria-current={current ? "page" : undefined} title={item.label}>
        <Icon size={16} strokeWidth={current ? 2.2 : 1.8} className="side-icon" />
        <span className="side-label">{item.label}</span>
        {item.feed === "planned" && <span className="side-pill">Planned</span>}
      </Link>
    );
  }

  return (
    <div className={`side-tree${open ? " open" : ""}${inside ? " inside" : ""}`}>
      <div className="side-link side-parent" data-inside={inside || undefined}>
        <Link href={item.href} className="side-parent-link" title={item.label} onClick={() => !open && onToggle()}>
          <Icon size={16} strokeWidth={inside ? 2.2 : 1.8} className="side-icon" />
          <span className="side-label">{item.label}</span>
          {item.feed === "planned" && <span className="side-pill">Planned</span>}
        </Link>
        <button type="button" className="side-chevron" onClick={onToggle} aria-expanded={open} aria-label={`${open ? "Hide" : "Show"} ${item.label} pages`}>
          <ChevronRight size={14} />
        </button>
      </div>
      <div className="side-sub" hidden={!open}>
        {item.children.map((leaf) => (
          <Link key={leaf.href} href={leaf.href} className="side-sublink" aria-current={leaf.href === pathname ? "page" : undefined}>
            {leaf.label}
          </Link>
        ))}
      </div>
    </div>
  );
}

function filterGroups(groups: NavGroup[], filter: string): NavGroup[] {
  const q = filter.trim().toLowerCase();
  if (!q) return groups;
  return groups
    .map((g) => ({
      ...g,
      items: g.items
        .map((item) => {
          if (item.label.toLowerCase().includes(q)) return item;
          const children = item.children?.filter((c) => c.label.toLowerCase().includes(q));
          return children && children.length > 0 ? { ...item, children } : null;
        })
        .filter((item): item is NavItem => item !== null),
    }))
    .filter((g) => g.items.length > 0);
}

/** The state of every store at a glance, at the foot of the menu. */
function SystemStatus() {
  const { data: o, error } = useOverview();
  const down = !!error && !o;
  const rows: [string, "good" | "bad" | "muted" | "warn", string][] = [
    ["Kafka", down ? "bad" : !o ? "muted" : !isKafkaFlow(o) ? "muted" : o.kafkaProblem ? "bad" : "good", !o ? "–" : isKafkaFlow(o) ? (o.kafkaProblem ? "down" : "up") : "direct flow"],
    ["Redis", down ? "bad" : !o ? "muted" : o.redisProblem ? "bad" : "good", !o ? "–" : o.redisProblem ? "down" : "up"],
    ["PostgreSQL", down ? "bad" : !o ? "muted" : o.storedPositions < 0 ? "bad" : "good", !o ? "–" : o.storedPositions < 0 ? "down" : "up"],
    ["ClickHouse", "muted", "planned"],
    ["Simulator", !o ? "muted" : o.simulator.running ? "good" : "warn", !o ? "–" : o.simulator.running ? "running" : "stopped"],
  ];
  return (
    <div className="side-status" aria-label="System status">
      <div className="side-title">System</div>
      {down && <div className="side-status-down">GPS service not answering</div>}
      {rows.map(([name, tone, text]) => (
        <div className="side-status-row" key={name} title={`${name}: ${text}`}>
          <span className={`status-dot ${tone}`} />
          <span className="side-label">{name}</span>
          <span className="side-status-text">{text}</span>
        </div>
      ))}
    </div>
  );
}
