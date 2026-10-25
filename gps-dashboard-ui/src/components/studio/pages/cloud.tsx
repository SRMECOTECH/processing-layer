"use client";

import { motion } from "framer-motion";
import { FileText, Film, Folder, Image as ImageIcon, Presentation, Search, Upload } from "lucide-react";
import { useState } from "react";
import { Avatar, BlurIn, Card, CardTitle, CountUp, EASE, PillButton, RevealText, useDelay } from "../kit";

// Arc lengths are a share of the ring (drawn for legibility), the legend carries the sizes.
const STORAGE = [
  { label: "Videos", gb: 6.2, arc: 0.2, stroke: "#111014", dot: "bg-ink" },
  { label: "Images", gb: 2.1, arc: 0.06, stroke: "#cbbfea", dot: "bg-lav" },
  { label: "Documents", gb: 1.2, arc: 0.045, stroke: "#f5ceec", dot: "bg-pink" },
  { label: "Other", gb: 0.6, arc: 0.03, stroke: "#bfbcc0", dot: "bg-[#bfbcc0]" },
];

const FOLDERS = [
  { name: "Surgery footage", files: 128, size: "24.1 GB", tint: "bg-lav-soft text-lav-deep" },
  { name: "Lecture slides", files: 64, size: "2.6 GB", tint: "bg-pink-soft text-[#a0428c]" },
  { name: "Brand assets", files: 118, size: "9.1 GB", tint: "bg-mint text-[#2f7a4c]" },
  { name: "Team recordings", files: 46, size: "6.8 GB", tint: "bg-sky text-[#3d5bb0]" },
];

const FILES = [
  { name: "brain-aneurysm-clipping.mp4", icon: Film, tint: "bg-lav-soft text-lav-deep", size: "1.2 GB", modified: "May 17, 2023", shared: [47, 12, 33] },
  { name: "q2-design-review.pdf", icon: FileText, tint: "bg-pink-soft text-[#a0428c]", size: "4.8 MB", modified: "May 16, 2023", shared: [44, 32] },
  { name: "lecture-03-neuroanatomy.key", icon: Presentation, tint: "bg-sky text-[#3d5bb0]", size: "86 MB", modified: "May 15, 2023", shared: [12, 59, 47, 33] },
  { name: "brand-guidelines-v4.png", icon: ImageIcon, tint: "bg-mint text-[#2f7a4c]", size: "32 MB", modified: "May 12, 2023", shared: [32] },
  { name: "team-sync-may-10.mp4", icon: Film, tint: "bg-lav-soft text-lav-deep", size: "640 MB", modified: "May 10, 2023", shared: [44, 12, 33] },
  { name: "onboarding-checklist.pdf", icon: FileText, tint: "bg-pink-soft text-[#a0428c]", size: "220 KB", modified: "May 8, 2023", shared: [59, 47] },
];

export function CloudPage() {
  return (
    <div className="no-scrollbar h-full overflow-y-auto">
      <div className="flex items-start justify-between gap-6">
        <div>
          <h1 className="text-[30px] leading-tight">
            <RevealText text="Cloud storage" />
          </h1>
          <BlurIn i={1} className="mt-1 text-[13px] text-muted">
            Everything your team has uploaded, organised and backed up.
          </BlurIn>
        </div>
        <BlurIn i={1} className="pt-2">
          <PillButton icon={Upload}>Upload file</PillButton>
        </BlurIn>
      </div>

      <div className="mt-6 grid grid-cols-[45fr_55fr] gap-5">
        <StorageCard />
        <div>
          <BlurIn i={3} className="flex items-baseline justify-between px-1">
            <CardTitle>Folders</CardTitle>
            <span className="text-[11px] text-muted">4 folders</span>
          </BlurIn>
          <div className="mt-3 grid grid-cols-2 gap-3">
            {FOLDERS.map((f, n) => (
              <Card key={f.name} i={4 + n} className="flex items-center gap-3 !rounded-tile !p-4">
                <span className={`grid size-10 shrink-0 place-items-center rounded-xl ${f.tint}`}>
                  <Folder size={17} strokeWidth={1.7} />
                </span>
                <div className="min-w-0">
                  <div className="truncate text-[13px] font-medium">{f.name}</div>
                  <div className="mt-0.5 text-[11px] text-muted">
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
    <Card i={2} className="flex flex-col">
      <CardTitle>Storage</CardTitle>
      <div className="mt-3 flex flex-1 items-center gap-8">
        <div className="relative size-[150px] shrink-0">
          <svg viewBox="0 0 130 130" className="size-full -rotate-90">
            <circle cx="65" cy="65" r={r} fill="none" stroke="#f1eff3" strokeWidth="13" />
            {STORAGE.map((s, n) => {
              const offset = start;
              start += s.arc + gap;
              const length = s.arc * circumference;
              return (
                <motion.circle
                  key={s.label}
                  cx="65"
                  cy="65"
                  r={r}
                  fill="none"
                  stroke={s.stroke}
                  strokeWidth="13"
                  strokeLinecap="round"
                  strokeDasharray={`${length} ${circumference}`}
                  transform={`rotate(${offset * 360} 65 65)`}
                  initial={{ strokeDashoffset: length }}
                  animate={{ strokeDashoffset: 0 }}
                  transition={{ duration: 0.9, delay: delay + n * 0.08, ease: EASE }}
                />
              );
            })}
          </svg>
          <div className="absolute inset-0 grid place-content-center text-center">
            <div className="text-[28px] leading-none font-medium tracking-[-0.02em]">
              <CountUp value={10.1} i={2} format={(n) => n.toFixed(1)} />
            </div>
            <div className="mt-1 text-[10px] text-muted">of 100 GB used</div>
          </div>
        </div>
        <ul className="flex flex-1 flex-col gap-3">
          {STORAGE.map((s) => (
            <li key={s.label} className="flex items-center gap-2 text-[12px]">
              <span className={`size-2 rounded-full ${s.dot}`} />
              <span className="flex-1 text-text-2">{s.label}</span>
              <span className="font-medium">{s.gb} GB</span>
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
    <Card i={8} className="mt-5">
      <div className="flex items-center justify-between gap-4">
        <CardTitle>Recent files</CardTitle>
        <label className="flex h-9 w-[240px] items-center gap-2 rounded-full bg-track px-3.5 text-muted focus-within:ring-2 focus-within:ring-lav">
          <Search size={14} strokeWidth={1.8} />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search files"
            className="w-full bg-transparent text-[12px] text-text outline-none placeholder:text-muted"
          />
        </label>
      </div>
      <div className="mt-3 grid grid-cols-[minmax(0,3fr)_1fr_1.3fr_1.2fr] gap-x-4 border-b border-track pb-2 text-[11px] text-muted">
        <span>Name</span>
        <span>Size</span>
        <span>Modified</span>
        <span>Shared with</span>
      </div>
      <ul>
        {files.map((f) => (
          <li key={f.name} className="grid grid-cols-[minmax(0,3fr)_1fr_1.3fr_1.2fr] items-center gap-x-4 border-b border-track py-2.5 text-[12px] last:border-0">
            <span className="flex min-w-0 items-center gap-3">
              <span className={`grid size-8 shrink-0 place-items-center rounded-[10px] ${f.tint}`}>
                <f.icon size={14} strokeWidth={1.7} />
              </span>
              <span className="truncate font-medium">{f.name}</span>
            </span>
            <span className="text-text-2">{f.size}</span>
            <span className="text-text-2">{f.modified}</span>
            <span className="flex -space-x-2">
              {f.shared.map((img) => (
                <Avatar key={img} img={img} size={24} ring />
              ))}
            </span>
          </li>
        ))}
        {!files.length && <li className="py-6 text-center text-[12px] text-muted">No files match “{query}”.</li>}
      </ul>
    </Card>
  );
}
