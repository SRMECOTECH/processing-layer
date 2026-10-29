"use client";

import { useState } from "react";
import { FileText, Film, Folder, Image as ImageIcon, Presentation, Search, Upload } from "@/components/icons";
import { Avatar, BlurIn, Card, CardTitle, CountUp, PageTop, PillButton, after, cx, kit, useDelay } from "../kit";
import s from "./cloud.module.css";

// Arc lengths are a share of the ring (drawn for legibility), the legend carries the sizes.
const STORAGE = [
  { label: "Videos", gb: 6.2, arc: 0.2, stroke: "var(--ink)", dot: kit.fillInk },
  { label: "Images", gb: 2.1, arc: 0.06, stroke: "var(--lav)", dot: kit.fillLav },
  { label: "Documents", gb: 1.2, arc: 0.045, stroke: "var(--pink)", dot: kit.fillPink },
  { label: "Other", gb: 0.6, arc: 0.03, stroke: "var(--grey)", dot: kit.fillGrey },
];

const FOLDERS = [
  { name: "Surgery footage", files: 128, size: "24.1 GB", tint: kit.tintLav },
  { name: "Lecture slides", files: 64, size: "2.6 GB", tint: kit.tintPink },
  { name: "Brand assets", files: 118, size: "9.1 GB", tint: kit.tintMint },
  { name: "Team recordings", files: 46, size: "6.8 GB", tint: kit.tintSky },
];

const FILES = [
  { name: "brain-aneurysm-clipping.mp4", icon: Film, tint: kit.tintLav, size: "1.2 GB", modified: "May 17, 2023", shared: ["Sophia Brown", "Alex Johnson", "Daniel Turner"] },
  { name: "q2-design-review.pdf", icon: FileText, tint: kit.tintPink, size: "4.8 MB", modified: "May 16, 2023", shared: ["Jennifer Campbell", "Sarah Thompson"] },
  { name: "lecture-03-neuroanatomy.key", icon: Presentation, tint: kit.tintSky, size: "86 MB", modified: "May 15, 2023", shared: ["Alex Johnson", "Michael Reed", "Sophia Brown", "Daniel Turner"] },
  { name: "brand-guidelines-v4.png", icon: ImageIcon, tint: kit.tintMint, size: "32 MB", modified: "May 12, 2023", shared: ["Sarah Thompson"] },
  { name: "team-sync-may-10.mp4", icon: Film, tint: kit.tintLav, size: "640 MB", modified: "May 10, 2023", shared: ["Jennifer Campbell", "Alex Johnson", "Daniel Turner"] },
  { name: "onboarding-checklist.pdf", icon: FileText, tint: kit.tintPink, size: "220 KB", modified: "May 8, 2023", shared: ["Michael Reed", "Sophia Brown"] },
];

export function CloudPage() {
  return (
    <div className={kit.scroll}>
      <PageTop title="Cloud storage" lead="Everything your team has uploaded, organised and backed up.">
        <PillButton icon={Upload}>Upload file</PillButton>
      </PageTop>

      <div className={s.split}>
        <StorageCard />
        <div>
          <BlurIn i={3} className={s.foldersHead}>
            <CardTitle>Folders</CardTitle>
            <span className={s.count}>4 folders</span>
          </BlurIn>
          <div className={s.folders}>
            {FOLDERS.map((f, n) => (
              <Card key={f.name} i={4 + n} tile className={s.folderCard}>
                <span className={cx(s.folderIcon, f.tint)}>
                  <Folder size={17} strokeWidth={1.7} />
                </span>
                <div className={s.folderText}>
                  <div className={s.folderName}>{f.name}</div>
                  <div className={s.folderMeta}>
                    {f.files} files · {f.size}
                  </div>
                </div>
              </Card>
            ))}
          </div>
        </div>
      </div>

      <RecentFiles />
    </div>
  );
}

function StorageCard() {
  const delay = useDelay(2) + 0.15;
  const r = 52;
  const circumference = 2 * Math.PI * r;
  const gap = 0.035; // between segments, as a share of the ring (round caps eat into it)
  let start = 0;
  return (
    <Card i={2} className={s.storage}>
      <CardTitle>Storage</CardTitle>
      <div className={s.storageBody}>
        <div className={s.ring}>
          <svg viewBox="0 0 130 130" className={s.ringSvg}>
            <circle cx="65" cy="65" r={r} fill="none" strokeWidth="13" style={{ stroke: "var(--track)" }} />
            {STORAGE.map((seg, n) => {
              const offset = start;
              start += seg.arc + gap;
              const length = seg.arc * circumference;
              return (
                <circle
                  key={seg.label}
                  cx="65"
                  cy="65"
                  r={r}
                  fill="none"
                  strokeWidth="13"
                  strokeLinecap="round"
                  strokeDasharray={`${length} ${circumference}`}
                  transform={`rotate(${offset * 360} 65 65)`}
                  className={s.segment}
                  style={after(delay + n * 0.08, { stroke: seg.stroke, "--length": length } as React.CSSProperties)}
                />
              );
            })}
          </svg>
          <div className={s.ringCenter}>
            <div className={s.ringValue}>
              <CountUp value={10.1} i={2} format={(n) => n.toFixed(1)} />
            </div>
            <div className={s.ringLabel}>of 100 GB used</div>
          </div>
        </div>
        <ul className={s.legend}>
          {STORAGE.map((seg) => (
            <li key={seg.label} className={s.legendRow}>
              <span className={cx(s.legendDot, seg.dot)} />
              <span className={s.legendLabel}>{seg.label}</span>
              <span className={s.legendValue}>{seg.gb} GB</span>
            </li>
          ))}
        </ul>
      </div>
    </Card>
  );
}

function RecentFiles() {
  const [query, setQuery] = useState("");
  const files = FILES.filter((f) => f.name.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <Card i={8} className={s.recent}>
      <div className={s.recentHead}>
        <CardTitle>Recent files</CardTitle>
        <label className={s.search}>
          <Search size={14} strokeWidth={1.8} />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search files" aria-label="Search files" className={s.searchInput} />
        </label>
      </div>
      <div className={cx(s.columns, s.columnHead)}>
        <span>Name</span>
        <span>Size</span>
        <span>Modified</span>
        <span>Shared with</span>
      </div>
      <ul>
        {files.map((f) => (
          <li key={f.name} className={cx(s.columns, s.file)}>
            <span className={s.fileName}>
              <span className={cx(s.fileIcon, f.tint)}>
                <f.icon size={14} strokeWidth={1.7} />
              </span>
              <span className={s.fileText}>{f.name}</span>
            </span>
            <span className={s.dim}>{f.size}</span>
            <span className={s.dim}>{f.modified}</span>
            <span className={s.shared}>
              {f.shared.map((name) => (
                <Avatar key={name} name={name} size={24} ring />
              ))}
            </span>
          </li>
        ))}
        {!files.length && <li className={s.empty}>No files match “{query}”.</li>}
      </ul>
    </Card>
  );
}
