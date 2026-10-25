"use client";

import { AnimatePresence, motion } from "framer-motion";
import { Bookmark, ExternalLink, FileText, Folder, Globe } from "lucide-react";
import { useState } from "react";
import { Blob, type BlobTone } from "../art";
import { BlurIn, Card, CardTitle, CountUp, EASE, GrowBar, PillButton, PlusButton, RevealText, Segmented, useDelay } from "../kit";

type Kind = "Video" | "Doc" | "Link";

type Item = {
  id: number;
  kind: Kind;
  title: string;
  source: string;
  saved: string;
  tone?: BlobTone;
  tile?: string;
};

const ITEMS: Item[] = [
  { id: 1, kind: "Video", title: "Brain Aneurysm Clipping with the Leica M530", source: "Last videos", saved: "Mar 3", tone: "violet" },
  { id: 2, kind: "Doc", title: "Q2 Design Review — Findings & Next Steps", source: "Workspace Studio", saved: "Apr 18", tile: "bg-[#c7bee7]" },
  { id: 3, kind: "Link", title: "Material motion guidelines", source: "m3.material.io", saved: "Apr 22", tile: "bg-pink" },
  { id: 4, kind: "Video", title: "Awake Craniotomy: Language Mapping", source: "Library", saved: "May 10", tone: "crimson" },
  { id: 5, kind: "Doc", title: "Onboarding checklist for new teammates", source: "People Ops", saved: "May 12", tile: "bg-mint" },
  { id: 6, kind: "Link", title: "Inclusive design patterns cheat sheet", source: "a11yproject.com", saved: "May 13", tile: "bg-sky" },
  { id: 7, kind: "Video", title: "Endoscopic Pituitary Surgery Walkthrough", source: "Library", saved: "May 15", tone: "teal" },
];

const COLLECTIONS = [
  { name: "Neurosurgery reference", items: 24, fill: 85, icon: "bg-ink text-white", bar: "bg-ink" },
  { name: "Design inspiration", items: 41, fill: 90, icon: "bg-lav-soft text-lav-deep", bar: "bg-lav" },
  { name: "Team onboarding", items: 9, fill: 35, icon: "bg-pink-soft text-[#a0428c]", bar: "bg-pink" },
  { name: "Read later", items: 17, fill: 55, icon: "bg-lav-soft text-lav-deep", bar: "bg-lav" },
];

const FILTERS = ["All", "Videos", "Docs", "Links"] as const;
type Filter = (typeof FILTERS)[number];
const FILTER_KIND: Record<Filter, Kind | null> = { All: null, Videos: "Video", Docs: "Doc", Links: "Link" };

export function SavedPage() {
  const [filter, setFilter] = useState<Filter>("All");
  const [removed, setRemoved] = useState<number[]>([]);
  const kind = FILTER_KIND[filter];
  const saved = ITEMS.filter((it) => !removed.includes(it.id));
  const shown = saved.filter((it) => !kind || it.kind === kind);

  return (
    <div className="no-scrollbar h-full overflow-y-auto">
      <div className="flex items-start justify-between gap-6">
        <div>
          <h1 className="text-[30px] leading-tight">
            <RevealText text="Saved for later" />
          </h1>
          <BlurIn i={1} className="mt-1 text-[13px] text-muted">
            Videos, docs and links you bookmarked across the workspace.
          </BlurIn>
        </div>
        <BlurIn i={1} className="flex items-center gap-3 pt-2">
          <Segmented id="saved-filter" options={FILTERS} value={filter} onChange={setFilter} />
          <PillButton icon={Bookmark} tone={removed.length ? "ink" : "grey"} disabled={!removed.length} onClick={() => setRemoved([])}>
            Restore all
          </PillButton>
        </BlurIn>
      </div>

      <div className="mt-6 grid grid-cols-[74fr_26fr] items-start gap-5 pb-1">
        <motion.div layout className="grid grid-cols-3 gap-5">
          <AnimatePresence mode="popLayout">
            {shown.map((it, n) => (
              <SavedCard key={it.id} item={it} i={2 + n} onUnsave={() => setRemoved((r) => [...r, it.id])} />
            ))}
          </AnimatePresence>
        </motion.div>
        <CollectionsCard count={saved.length} />
      </div>
    </div>
  );
}

function SavedCard({ item, i, onUnsave }: { item: Item; i: number; onUnsave: () => void }) {
  const delay = useDelay(i);
  return (
    <motion.div
      layout
      initial={{ opacity: 0, filter: "blur(10px)", y: 10 }}
      animate={{ opacity: 1, filter: "blur(0px)", y: 0, transition: { duration: 0.5, delay, ease: EASE } }}
      exit={{ opacity: 0, scale: 0.94, filter: "blur(6px)", transition: { duration: 0.2 } }}
      transition={{ layout: { type: "spring", stiffness: 400, damping: 36 } }}
      className="rounded-[18px] bg-white p-2 transition-[translate] duration-200 hover:-translate-y-0.5"
    >
      <div className={`relative grid h-[104px] place-items-center overflow-hidden rounded-[14px] ${item.tile ?? ""}`}>
        {item.tone ? (
          <Blob tone={item.tone} seed={item.id * 3} className="absolute inset-0 size-full" />
        ) : (
          <span className="grid size-10 place-items-center rounded-xl bg-white/90 text-text">
            {item.kind === "Doc" ? <FileText size={17} strokeWidth={1.6} /> : <Globe size={17} strokeWidth={1.6} />}
          </span>
        )}
        <span className="absolute top-2 left-2 rounded-full bg-ink px-2 py-[3px] text-[9px] leading-none text-white">{item.kind}</span>
      </div>
      <div className="px-1.5 pt-2.5 pb-1">
        <p className="line-clamp-2 min-h-[32px] text-[12px] leading-[1.35] font-medium">{item.title}</p>
        <div className="mt-2 flex items-end justify-between gap-2">
          <div className="min-w-0 text-[10px] leading-snug text-muted">
            <div className="truncate text-text-2">{item.source}</div>
            <div>Saved {item.saved}</div>
          </div>
          <div className="flex shrink-0 gap-1.5">
            <button aria-label="Open" className="press grid size-7 place-items-center rounded-lg bg-track text-text-2">
              <ExternalLink size={12} strokeWidth={1.8} />
            </button>
            <button aria-label="Remove bookmark" onClick={onUnsave} className="press grid size-7 place-items-center rounded-lg bg-lav-soft text-lav-deep">
              <Bookmark size={12} strokeWidth={1.8} fill="currentColor" />
            </button>
          </div>
        </div>
      </div>
    </motion.div>
  );
}

function CollectionsCard({ count }: { count: number }) {
  return (
    <Card i={3}>
      <div className="flex items-center justify-between">
        <CardTitle>Collections</CardTitle>
        <PlusButton label="New collection" />
      </div>
      <ul className="mt-4 flex flex-col gap-4">
        {COLLECTIONS.map((c, n) => (
          <li key={c.name} className="flex items-center gap-3">
            <span className={`grid size-8 shrink-0 place-items-center rounded-[10px] ${c.icon}`}>
              <Folder size={14} strokeWidth={1.8} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between gap-2">
                <span className="truncate text-[12px] font-medium">{c.name}</span>
                <span className="shrink-0 text-[10px] text-muted">{c.items} items</span>
              </div>
              <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-track">
                <GrowBar size={c.fill} i={3} n={n} className={`h-full rounded-full ${c.bar}`} />
              </div>
            </div>
          </li>
        ))}
      </ul>
      <div className="mt-5 flex items-baseline gap-2 border-t border-track pt-4">
        <span className="text-[34px] leading-none font-medium tracking-[-0.02em]">
          <CountUp value={count} i={3} />
        </span>
        <span className="text-[11px] text-muted">items saved right now</span>
      </div>
    </Card>
  );
}
