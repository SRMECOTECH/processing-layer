"use client";

import { motion } from "framer-motion";
import {
  ArrowRight,
  Bell,
  BellOff,
  Blend,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  CircleArrowRight,
  CirclePlus,
  CircleX,
  Copy,
  Eye,
  Link2,
  MessageSquare,
  Sparkles,
} from "lucide-react";
import { useState } from "react";
import { Blob, Flower, Spiral, type BlobTone } from "../art";
import { Avatar, BlurIn, Card, CardTitle, CountUp, GrowBar, PillButton, PlusButton, RevealText, Segmented, useDelay } from "../kit";

const MEMBERS = [
  { first: "Sophia", last: "Brown", img: 47 },
  { first: "Alex", last: "Johnson", img: 12 },
  { first: "Daniel", last: "Turner", img: 33 },
  { first: "Jennifer", last: "Campbell", img: 44 },
  { first: "Sarah", last: "Thompson", img: 32 },
  { first: "Michael", last: "Reed", img: 59 },
];

const DATA_BARS = [
  { label: "9h39m", share: 50, change: "+50%", color: "bg-ink" },
  { label: "2h27m", share: 33, change: "+33%", color: "bg-lav" },
  { label: "42m", share: 17, change: "+17%", color: "bg-pink" },
];

const IMPACT = [
  { size: 42, color: "bg-pink" },
  { size: 92, color: "bg-lav" },
  { size: 64, color: "bg-pink" },
];

const STATS = {
  Weekly: { done: 42, total: 64, complete: 16, progress: 8 },
  Monthly: { done: 168, total: 240, complete: 58, progress: 21 },
} as const;

const WEEK = [
  { day: "Mon", date: 15 },
  { day: "Tue", date: 16 },
  { day: "Wed", date: 17 },
  { day: "Thu", date: 18 },
  { day: "Fri", date: 19 },
  { day: "Sat", date: 20 },
];

const VIDEOS: { title: string; length: string; date: string; comments: number; tone: BlobTone; seed: number }[] = [
  { title: "Brain Aneurysm Clipping with the Leica M530 OH6 Surgical Microscope", length: "4 min", date: "Mar 2", comments: 15, tone: "violet", seed: 3 },
  { title: "About the National Hospital for Neurology and Neurosurgery", length: "8 min", date: "Mar 30", comments: 12, tone: "blue", seed: 8 },
  { title: "Endoscopic Pituitary Surgery Walkthrough", length: "6 min", date: "Apr 4", comments: 9, tone: "teal", seed: 5 },
];

const NOTIFICATIONS = [
  { who: "Jennifer Campbell", what: "Invites You to a Call: 'Personal Development Strategies'", kind: "Invite", when: "30 min ago", img: 44 },
  { who: "Sarah Thompson", what: "Left a Comment on Your Video: 'Mastering the Art of Photography'", kind: "Comment", when: "1 hour ago", img: 32 },
  { who: "Daniel Turner", what: "Watched Your Video: 'Inside the Operating Room'", kind: "View", when: "3 hours ago", img: 33 },
];

export function DashboardPage() {
  return (
    <div className="grid h-full grid-cols-[64fr_36fr] gap-6">
      <MainColumn />
      <RightColumn />
    </div>
  );
}

function MainColumn() {
  return (
    <div className="flex min-h-0 flex-col gap-5">
      <Hero />
      <div className="grid h-[205px] shrink-0 grid-cols-[44fr_33fr_23fr] gap-5">
        <MembersCard />
        <DataCard />
        <ImpactCard />
      </div>
      <div className="grid min-h-0 flex-1 grid-cols-[55fr_45fr] gap-5">
        <StatisticsCard />
        <div className="flex min-h-0 flex-col gap-5">
          <EventCard />
          <UpgradeCard />
        </div>
      </div>
    </div>
  );
}

function Hero() {
  return (
    <BlurIn i={0} className="relative h-[140px] shrink-0 overflow-hidden rounded-card bg-lav px-7 py-7">
      <h1 className="text-[28px] leading-none">
        <RevealText text="Work's always better together" i={0} />
      </h1>
      <div className="mt-4 flex items-center gap-6">
        <p className="max-w-[360px] text-[13px] leading-[1.45] text-[#5e5674]">
          Add teammates and you&apos;ll be able to collaborate and quickly get a sense of what&apos;s happening at work.
        </p>
        <PillButton icon={CirclePlus}>Add member</PillButton>
      </div>
      <Flower className="pointer-events-none absolute -top-[70px] -right-[46px] size-[270px]" />
    </BlurIn>
  );
}

function MembersCard() {
  const delay = useDelay(1) + 0.1;
  return (
    <Card i={1} className="flex min-h-0 flex-col overflow-hidden">
      <div className="flex items-center justify-between">
        <CardTitle>Members</CardTitle>
        <PlusButton label="Add member" />
      </div>
      <div className="no-scrollbar mt-3 -mb-[18px] grid flex-1 grid-cols-3 gap-x-2 gap-y-3 overflow-y-auto pb-4">
        {MEMBERS.map((m, n) => (
          <motion.div
            key={m.first}
            className="flex flex-col items-center text-center"
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 0.4, delay: delay + n * 0.05 }}
          >
            <Avatar img={m.img} size={52} />
            <div className="mt-1.5 text-[11px] leading-tight text-text-2">
              {m.first}
              <br />
              {m.last}
            </div>
          </motion.div>
        ))}
      </div>
    </Card>
  );
}

function DataCard() {
  return (
    <Card i={2} className="flex flex-col">
      <div className="flex items-center justify-between">
        <span className="text-[12px] text-muted">Data</span>
        <ArrowRight size={16} strokeWidth={1.6} className="text-text-2" />
      </div>
      <div className="mt-1 text-[36px] leading-none font-medium tracking-[-0.02em]">
        <CountUp value={9.7} i={2} format={(n) => `${n.toFixed(1).replace(".", ",")}h`} />
      </div>
      <div className="mt-auto flex gap-1.5">
        {DATA_BARS.map((b, n) => (
          <div key={b.label} style={{ flexGrow: b.share }} className="min-w-0 basis-0">
            <div className="border-l border-[#dcd8e0] pb-1.5 pl-1 text-[9px] leading-none text-muted">{b.label}</div>
            <GrowBar size={100} i={2} n={n} className={`h-2 rounded-full ${b.color}`} />
            <div className="mt-2 flex items-center gap-1 text-[10px] leading-none text-text-2">
              <span className={`size-1.5 rounded-full ${b.color}`} />
              {b.change}
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

function ImpactCard() {
  return (
    <Card i={3} className="flex flex-col">
      <span className="text-[12px] text-muted">Impact</span>
      <div className="mt-2 flex flex-1 items-end justify-center gap-2.5">
        {IMPACT.map((b, n) => (
          <div key={n} className="flex h-full w-[22px] items-end">
            <GrowBar size={b.size} axis="y" i={3} n={n} className={`w-full rounded-full ${b.color}`} />
          </div>
        ))}
      </div>
    </Card>
  );
}

function StatisticsCard() {
  const [range, setRange] = useState<keyof typeof STATS>("Weekly");
  const s = STATS[range];
  return (
    <Card i={4} className="flex min-h-0 flex-col">
      <div className="flex items-center justify-between">
        <CardTitle>Statistics</CardTitle>
        <Segmented id="stats" options={["Weekly", "Monthly"] as const} value={range} onChange={setRange} />
      </div>
      <div className="mt-4 flex items-end gap-3">
        <div className="flex items-center gap-3 text-[40px] leading-none font-medium tracking-[-0.02em]">
          <CountUp value={s.done} i={4} />
          <span className="h-8 w-px bg-[#d9d5de]" />
          <CountUp value={s.total} i={4} />
        </div>
        <span className="pb-1 text-[12px] text-muted">tasks complete</span>
      </div>
      <div className="mt-4 grid min-h-[110px] flex-1 grid-cols-2 gap-3">
        <StatTile icon={Copy} value={s.complete} label="Project complete" />
        <StatTile icon={Blend} value={s.progress} label="Project in progress" />
      </div>
    </Card>
  );
}

function StatTile({ icon: Icon, value, label }: { icon: typeof Copy; value: number; label: string }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-tile bg-tile text-center">
      <Icon size={30} strokeWidth={1.2} className="text-text-2" />
      <div className="mt-4 text-[17px] leading-none font-medium">
        <CountUp value={value} i={4} />
      </div>
      <div className="mt-1.5 text-[11px] text-muted">{label}</div>
    </div>
  );
}

function EventCard() {
  const [open, setOpen] = useState(true);
  return (
    <BlurIn i={5} className="flex h-[92px] shrink-0 items-center rounded-card bg-ink p-4">
      <motion.div
        className="flex w-full items-center gap-3 rounded-full bg-white py-2 pr-2 pl-2 shadow-float"
        animate={open ? { opacity: 1, scale: 1 } : { opacity: 0.55, scale: 0.97 }}
        transition={{ duration: 0.25 }}
      >
        <span className="grid size-8 shrink-0 place-items-center rounded-full bg-lav">
          <Sparkles size={14} strokeWidth={1.8} className="text-lav-deep" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[12px] leading-tight font-medium">Breakthrough Brainstorm</div>
          <div className="text-[10px] leading-tight text-muted">12:45 pm</div>
        </div>
        <button
          aria-label={open ? "Dismiss" : "Restore"}
          onClick={() => setOpen((o) => !o)}
          className="press grid size-8 place-items-center rounded-full text-text-2"
        >
          <CircleX size={20} strokeWidth={1.5} />
        </button>
      </motion.div>
    </BlurIn>
  );
}

function UpgradeCard() {
  return (
    <Card i={6} className="flex min-h-0 flex-1 items-center gap-2 overflow-hidden">
      <div className="flex h-full w-1/2 items-center justify-center">
        <Spiral className="h-full max-h-[260px] w-auto" />
      </div>
      <div className="w-1/2 pr-1">
        <h3 className="text-[20px] leading-[1.15]">Unleash Your Potential</h3>
        <p className="mt-2 text-[12px] leading-snug text-text-2">Indulge in the ultimate subscription</p>
        <PillButton icon={CircleArrowRight} tone="lav" className="mt-5">
          Upgrade plan
        </PillButton>
      </div>
    </Card>
  );
}

function RightColumn() {
  const [day, setDay] = useState(17);
  const [tab, setTab] = useState<"Overview" | "Shared" | "Comments">("Overview");
  return (
    <div className="no-scrollbar min-h-0 overflow-y-auto">
      <BlurIn i={1} className="flex items-center justify-between">
        <h2 className="text-[26px] leading-none">
          <RevealText text="May, 2023" i={1} />
        </h2>
        <div className="flex gap-1.5">
          {[ChevronLeft, ChevronRight].map((Icon, n) => (
            <button key={n} aria-label={n ? "Next month" : "Previous month"} className="press grid size-8 place-items-center rounded-full bg-white text-text-2">
              <Icon size={15} strokeWidth={1.8} />
            </button>
          ))}
        </div>
      </BlurIn>

      <BlurIn i={2} className="mt-4 flex justify-between">
        {WEEK.map((d) => (
          <button key={d.date} onClick={() => setDay(d.date)} className="relative flex h-14 w-11 flex-col items-center justify-center gap-1.5 rounded-[14px]">
            {d.date === day && (
              <motion.span layoutId="week-day" transition={{ type: "spring", stiffness: 400, damping: 32 }} className="absolute inset-0 rounded-[14px] bg-ink" />
            )}
            <span className={`relative text-[11px] leading-none ${d.date === day ? "text-white/60" : "text-muted"}`}>{d.day}</span>
            <span className={`relative text-[13px] leading-none font-medium ${d.date === day ? "text-white" : "text-text"}`}>{d.date}</span>
          </button>
        ))}
      </BlurIn>

      <BlurIn i={3} className="mt-5 flex items-center justify-between">
        <CardTitle>Last videos</CardTitle>
        <PlusButton label="Add video" />
      </BlurIn>
      <div className="no-scrollbar -mr-5 mt-3 flex gap-3 overflow-x-auto pr-5">
        {VIDEOS.map((v, n) => (
          <VideoCard key={v.title} video={v} i={4 + n} />
        ))}
      </div>

      <BlurIn i={6} className="mt-6 flex items-center justify-between">
        <CardTitle>Notifications</CardTitle>
        <div className="flex items-center gap-2">
          <button aria-label="Notifications" className="press grid size-8 place-items-center rounded-full bg-ink text-white">
            <Bell size={14} strokeWidth={1.8} />
          </button>
          <button aria-label="Mute notifications" className="press grid size-8 place-items-center rounded-full text-muted">
            <BellOff size={15} strokeWidth={1.6} />
          </button>
        </div>
      </BlurIn>
      <BlurIn i={7} className="mt-2 flex gap-5 text-[12px]">
        {(["Overview", "Shared", "Comments"] as const).map((t) => (
          <button key={t} onClick={() => setTab(t)} className={`relative pb-1.5 ${t === tab ? "text-text" : "text-muted hover:text-text-2"}`}>
            {t}
            {t === tab && <motion.span layoutId="notif-tab" className="absolute inset-x-0 bottom-0 h-[1.5px] rounded-full bg-ink" />}
          </button>
        ))}
      </BlurIn>
      <div className="mt-3 flex flex-col gap-2 pb-2">
        {NOTIFICATIONS.filter((n) => tab === "Overview" || (tab === "Comments" ? n.kind === "Comment" : n.kind === "Invite")).map((n, k) => (
          <BlurIn key={n.who} i={8 + k} className="flex gap-3 rounded-[14px] bg-white p-3 transition-[translate] duration-200 hover:-translate-y-0.5">
            <Avatar img={n.img} size={30} />
            <div className="min-w-0 flex-1">
              <div className="flex items-start gap-3">
                <p className="flex-1 text-[12px] leading-snug">
                  <span className="font-medium">{n.who}</span> {n.what}
                </p>
                <button className="press inline-flex h-6 shrink-0 items-center gap-1 rounded-full bg-ink px-2.5 text-[10px] text-white">
                  <Link2 size={11} strokeWidth={2} />
                  Link
                </button>
              </div>
              <div className="mt-2 flex justify-between text-[10px] text-muted">
                <span>{n.kind}</span>
                <span>{n.when}</span>
              </div>
            </div>
          </BlurIn>
        ))}
      </div>
    </div>
  );
}

function VideoCard({ video, i }: { video: (typeof VIDEOS)[number]; i: number }) {
  return (
    <BlurIn i={i} className="w-[158px] shrink-0 rounded-tile bg-white p-1.5 transition-[translate] duration-200 hover:-translate-y-0.5">
      <div className="relative h-[100px] overflow-hidden rounded-[12px]">
        <Blob tone={video.tone} seed={video.seed} className="size-full" />
        <span className="absolute right-1.5 bottom-1.5 rounded-full bg-ink px-1.5 py-0.5 text-[9px] leading-none text-white">{video.length}</span>
      </div>
      <div className="px-1 pt-2 pb-1">
        <p className="line-clamp-3 text-[11px] leading-snug font-medium">{video.title}</p>
        <div className="mt-2 flex items-center gap-2.5 text-[10px] text-muted">
          <span className="inline-flex items-center gap-1">
            <CalendarDays size={11} strokeWidth={1.6} />
            {video.date}
          </span>
          <Eye size={11} strokeWidth={1.6} />
          <span className="inline-flex items-center gap-1">
            <MessageSquare size={11} strokeWidth={1.6} />
            {video.comments}
          </span>
        </div>
      </div>
    </BlurIn>
  );
}
