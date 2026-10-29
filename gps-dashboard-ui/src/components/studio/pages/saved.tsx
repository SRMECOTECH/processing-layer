"use client";

import { useState } from "react";
import { Bookmark, ExternalLink, FileText, Folder, Globe } from "@/components/icons";
import { Blob, type BlobTone } from "../art";
import { BlurIn, Card, CardTitle, CountUp, GrowBar, PageTop, PillButton, PlusButton, Segmented, after, cx, kit, useDelay } from "../kit";
import s from "./saved.module.css";

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
  { id: 2, kind: "Doc", title: "Q2 Design Review — Findings & Next Steps", source: "Workspace Studio", saved: "Apr 18", tile: s.thumbLav },
  { id: 3, kind: "Link", title: "Material motion guidelines", source: "m3.material.io", saved: "Apr 22", tile: s.thumbPink },
  { id: 4, kind: "Video", title: "Awake Craniotomy: Language Mapping", source: "Library", saved: "May 10", tone: "crimson" },
  { id: 5, kind: "Doc", title: "Onboarding checklist for new teammates", source: "People Ops", saved: "May 12", tile: s.thumbMint },
  { id: 6, kind: "Link", title: "Inclusive design patterns cheat sheet", source: "a11yproject.com", saved: "May 13", tile: s.thumbSky },
  { id: 7, kind: "Video", title: "Endoscopic Pituitary Surgery Walkthrough", source: "Library", saved: "May 15", tone: "teal" },
];

const COLLECTIONS = [
  { name: "Neurosurgery reference", items: 24, fill: 85, icon: kit.tintInk, bar: kit.fillInk },
  { name: "Design inspiration", items: 41, fill: 90, icon: kit.tintLav, bar: kit.fillLav },
  { name: "Team onboarding", items: 9, fill: 35, icon: kit.tintPink, bar: kit.fillPink },
  { name: "Read later", items: 17, fill: 55, icon: kit.tintLav, bar: kit.fillLav },
];

const FILTERS = ["All", "Videos", "Docs", "Links"] as const;
type Filter = (typeof FILTERS)[number];
const FILTER_KIND: Record<Filter, Kind | null> = { All: null, Videos: "Video", Docs: "Doc", Links: "Link" };

// How long a removed card takes to shrink away before it leaves the grid.
const LEAVE_MS = 200;

export function SavedPage() {
  const [filter, setFilter] = useState<Filter>("All");
  const [removed, setRemoved] = useState<number[]>([]);
  const kind = FILTER_KIND[filter];
  const saved = ITEMS.filter((it) => !removed.includes(it.id));
  const shown = saved.filter((it) => !kind || it.kind === kind);

  return (
    <div className={kit.scroll}>
      <PageTop title="Saved for later" lead="Videos, docs and links you bookmarked across the workspace.">
        <Segmented id="saved-filter" options={FILTERS} value={filter} onChange={setFilter} />
        <PillButton icon={Bookmark} tone={removed.length ? "ink" : "grey"} disabled={!removed.length} onClick={() => setRemoved([])}>
          Restore all
        </PillButton>
      </PageTop>

      <div className={s.body}>
        <div className={s.grid}>
          {shown.map((it, n) => (
            <SavedCard key={it.id} item={it} i={2 + n} onUnsave={() => setRemoved((r) => [...r, it.id])} />
          ))}
          {!shown.length && (
            <BlurIn className={s.empty}>
              <Bookmark size={22} strokeWidth={1.5} />
              Nothing saved here. Bookmark a video, doc or link and it shows up on this page.
            </BlurIn>
          )}
        </div>
        <CollectionsCard count={saved.length} />
      </div>
    </div>
  );
}

function SavedCard({ item, i, onUnsave }: { item: Item; i: number; onUnsave: () => void }) {
  const delay = useDelay(i);
  const [leaving, setLeaving] = useState(false);

  function unsave() {
    setLeaving(true);
    window.setTimeout(onUnsave, LEAVE_MS);
  }

  // Blurs in like every card, then swaps that animation for the shrink-away one when unsaved.
  return (
    <div className={cx(leaving ? s.leaving : kit.blurIn, s.item, kit.lift)} style={after(delay)}>
      <div className={cx(s.thumb, item.tile)}>
        {item.tone ? (
          <Blob tone={item.tone} seed={item.id * 3} className={s.thumbBlob} />
        ) : (
          <span className={s.docIcon}>{item.kind === "Doc" ? <FileText size={17} strokeWidth={1.6} /> : <Globe size={17} strokeWidth={1.6} />}</span>
        )}
        <span className={s.kind}>{item.kind}</span>
      </div>
      <div className={s.itemBody}>
        <p className={s.itemTitle}>{item.title}</p>
        <div className={s.itemFoot}>
          <div className={s.itemMeta}>
            <div className={s.source}>{item.source}</div>
            <div>Saved {item.saved}</div>
          </div>
          <div className={s.itemButtons}>
            <button aria-label="Open" className={cx(kit.press, s.square, s.open)}>
              <ExternalLink size={12} strokeWidth={1.8} />
            </button>
            <button aria-label="Remove bookmark" onClick={unsave} className={cx(kit.press, s.square, kit.tintLav)}>
              <Bookmark size={12} strokeWidth={1.8} fill="currentColor" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function CollectionsCard({ count }: { count: number }) {
  return (
    <Card i={3}>
      <div className={kit.row}>
        <CardTitle>Collections</CardTitle>
        <PlusButton label="New collection" />
      </div>
      <ul className={s.collections}>
        {COLLECTIONS.map((c, n) => (
          <li key={c.name} className={s.collection}>
            <span className={cx(s.folder, c.icon)}>
              <Folder size={14} strokeWidth={1.8} />
            </span>
            <div className={s.collectionBody}>
              <div className={s.collectionTop}>
                <span className={s.collectionName}>{c.name}</span>
                <span className={s.collectionCount}>{c.items} items</span>
              </div>
              <div className={s.collectionTrack}>
                <GrowBar size={c.fill} i={3} n={n} className={cx(s.collectionBar, c.bar)} />
              </div>
            </div>
          </li>
        ))}
      </ul>
      <div className={s.total}>
        <span className={s.totalNumber}>
          <CountUp value={count} i={3} />
        </span>
        <span className={s.totalLabel}>items saved right now</span>
      </div>
    </Card>
  );
}
