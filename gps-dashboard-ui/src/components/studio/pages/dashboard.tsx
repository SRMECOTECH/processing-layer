"use client";

import { useState } from "react";
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
  type IconComponent,
} from "@/components/icons";
import { SlideMarker } from "@/components/slide-marker";
import { Blob, Flower, Spiral, type BlobTone } from "../art";
import { Avatar, BlurIn, Card, CardTitle, CountUp, GrowBar, PillButton, PlusButton, RevealText, Segmented, after, cx, kit, useDelay } from "../kit";
import s from "./dashboard.module.css";

const MEMBERS = ["Sophia Brown", "Alex Johnson", "Daniel Turner", "Jennifer Campbell", "Sarah Thompson", "Michael Reed"];

const DATA_BARS = [
  { label: "9h39m", share: 50, change: "+50%", color: kit.fillInk },
  { label: "2h27m", share: 33, change: "+33%", color: kit.fillLav },
  { label: "42m", share: 17, change: "+17%", color: kit.fillPink },
];

const IMPACT = [
  { size: 42, color: kit.fillPink },
  { size: 92, color: kit.fillLav },
  { size: 64, color: kit.fillPink },
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
  { who: "Jennifer Campbell", what: "Invites You to a Call: 'Personal Development Strategies'", kind: "Invite", when: "30 min ago" },
  { who: "Sarah Thompson", what: "Left a Comment on Your Video: 'Mastering the Art of Photography'", kind: "Comment", when: "1 hour ago" },
  { who: "Daniel Turner", what: "Watched Your Video: 'Inside the Operating Room'", kind: "View", when: "3 hours ago" },
];

const TABS = ["Overview", "Shared", "Comments"] as const;

export function DashboardPage() {
  return (
    <div className={s.page}>
      <MainColumn />
      <RightColumn />
    </div>
  );
}

function MainColumn() {
  return (
    <div className={s.main}>
      <Hero />
      <div className={s.topRow}>
        <MembersCard />
        <DataCard />
        <ImpactCard />
      </div>
      <div className={s.bottomRow}>
        <StatisticsCard />
        <div className={s.stack}>
          <EventCard />
          <UpgradeCard />
        </div>
      </div>
    </div>
  );
}

function Hero() {
  return (
    <BlurIn i={0} className={s.hero}>
      <h1 className={s.heroTitle}>
        <RevealText text="Work's always better together" i={0} />
      </h1>
      <div className={s.heroRow}>
        <p className={s.heroText}>
          Add teammates and you&apos;ll be able to collaborate and quickly get a sense of what&apos;s happening at work.
        </p>
        <PillButton icon={CirclePlus}>Add member</PillButton>
      </div>
      <Flower className={s.heroFlower} />
    </BlurIn>
  );
}

function MembersCard() {
  const delay = useDelay(1) + 0.1;
  return (
    <Card i={1} className={s.members}>
      <div className={kit.row}>
        <CardTitle>Members</CardTitle>
        <PlusButton label="Add member" />
      </div>
      <div className={cx(s.memberGrid, kit.scroll)}>
        {MEMBERS.map((name, n) => {
          const [first, last] = name.split(" ");
          return (
            <div key={name} className={s.member} style={after(delay + n * 0.05)}>
              <Avatar name={name} size={52} />
              <div className={s.memberName}>
                {first}
                <br />
                {last}
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

function DataCard() {
  return (
    <Card i={2} className={s.column}>
      <div className={kit.row}>
        <span className={s.label}>Data</span>
        <ArrowRight size={16} strokeWidth={1.6} className={s.dim} />
      </div>
      <div className={s.dataValue}>
        <CountUp value={9.7} i={2} format={(n) => `${n.toFixed(1).replace(".", ",")}h`} />
      </div>
      <div className={s.bars}>
        {DATA_BARS.map((b, n) => (
          <div key={b.label} style={{ flexGrow: b.share }} className={s.barColumn}>
            <div className={s.barLabel}>{b.label}</div>
            <GrowBar size={100} i={2} n={n} className={cx(s.bar, b.color)} />
            <div className={s.barChange}>
              <span className={cx(s.dot, b.color)} />
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
    <Card i={3} className={s.column}>
      <span className={s.label}>Impact</span>
      <div className={s.impactBars}>
        {IMPACT.map((b, n) => (
          <div key={n} className={s.impactColumn}>
            <GrowBar size={b.size} axis="y" i={3} n={n} className={cx(s.impactBar, b.color)} />
          </div>
        ))}
      </div>
    </Card>
  );
}

function StatisticsCard() {
  const [range, setRange] = useState<keyof typeof STATS>("Weekly");
  const stats = STATS[range];
  return (
    <Card i={4} className={s.statistics}>
      <div className={kit.row}>
        <CardTitle>Statistics</CardTitle>
        <Segmented id="stats" options={["Weekly", "Monthly"] as const} value={range} onChange={setRange} />
      </div>
      <div className={s.statsRow}>
        <div className={s.statsNumbers}>
          <CountUp value={stats.done} i={4} />
          <span className={s.statsDivider} />
          <CountUp value={stats.total} i={4} />
        </div>
        <span className={s.statsCaption}>tasks complete</span>
      </div>
      <div className={s.tiles}>
        <StatTile icon={Copy} value={stats.complete} label="Project complete" />
        <StatTile icon={Blend} value={stats.progress} label="Project in progress" />
      </div>
    </Card>
  );
}

function StatTile({ icon: Icon, value, label }: { icon: IconComponent; value: number; label: string }) {
  return (
    <div className={s.tile}>
      <Icon size={30} strokeWidth={1.2} className={s.dim} />
      <div className={s.tileValue}>
        <CountUp value={value} i={4} />
      </div>
      <div className={s.tileLabel}>{label}</div>
    </div>
  );
}

function EventCard() {
  const [open, setOpen] = useState(true);
  return (
    <BlurIn i={5} className={s.event}>
      <div className={s.eventPill} data-open={open}>
        <span className={s.sparkle}>
          <Sparkles size={14} strokeWidth={1.8} />
        </span>
        <div className={s.eventText}>
          <div className={s.eventTitle}>Breakthrough Brainstorm</div>
          <div className={s.eventTime}>12:45 pm</div>
        </div>
        <button aria-label={open ? "Dismiss" : "Restore"} onClick={() => setOpen((o) => !o)} className={cx(kit.press, s.round, s.dim)}>
          <CircleX size={20} strokeWidth={1.5} />
        </button>
      </div>
    </BlurIn>
  );
}

function UpgradeCard() {
  return (
    <Card i={6} className={s.upgrade}>
      <div className={s.upgradeArt}>
        <Spiral className={s.spiral} />
      </div>
      <div className={s.upgradeText}>
        <h3 className={s.upgradeTitle}>Unleash Your Potential</h3>
        <p className={s.upgradeLead}>Indulge in the ultimate subscription</p>
        <PillButton icon={CircleArrowRight} tone="lav" className={s.upgradeButton}>
          Upgrade plan
        </PillButton>
      </div>
    </Card>
  );
}

function RightColumn() {
  const [day, setDay] = useState(17);
  const [tab, setTab] = useState<(typeof TABS)[number]>("Overview");
  const notifications = NOTIFICATIONS.filter((n) => tab === "Overview" || (tab === "Comments" ? n.kind === "Comment" : n.kind === "Invite"));
  return (
    <div className={cx(s.right, kit.scroll)}>
      <BlurIn i={1} className={kit.row}>
        <h2 className={s.month}>
          <RevealText text="May, 2023" i={1} />
        </h2>
        <div className={s.monthNav}>
          {[ChevronLeft, ChevronRight].map((Icon, n) => (
            <button key={n} aria-label={n ? "Next month" : "Previous month"} className={cx(kit.press, s.round, s.surfaceButton)}>
              <Icon size={15} strokeWidth={1.8} />
            </button>
          ))}
        </div>
      </BlurIn>

      <BlurIn i={2} className={s.week}>
        {WEEK.map((d) => (
          <button key={d.date} onClick={() => setDay(d.date)} aria-pressed={d.date === day} className={s.day}>
            {d.date === day && <SlideMarker id="week-day" className={s.dayPill} duration={360} />}
            <span className={s.dayName}>{d.day}</span>
            <span className={s.dayDate}>{d.date}</span>
          </button>
        ))}
      </BlurIn>

      <BlurIn i={3} className={cx(kit.row, s.videosHead)}>
        <CardTitle>Last videos</CardTitle>
        <PlusButton label="Add video" />
      </BlurIn>
      <div className={cx(s.videos, kit.scroll)}>
        {VIDEOS.map((v, n) => (
          <VideoCard key={v.title} video={v} i={4 + n} />
        ))}
      </div>

      <BlurIn i={6} className={cx(kit.row, s.notificationsHead)}>
        <CardTitle>Notifications</CardTitle>
        <div className={s.bells}>
          <button aria-label="Notifications" className={cx(kit.press, s.round, kit.tintInk)}>
            <Bell size={14} strokeWidth={1.8} />
          </button>
          <button aria-label="Mute notifications" className={cx(kit.press, s.round, s.muted)}>
            <BellOff size={15} strokeWidth={1.6} />
          </button>
        </div>
      </BlurIn>
      <BlurIn i={7} className={s.tabs}>
        {TABS.map((t) => (
          <button key={t} role="tab" aria-selected={t === tab} onClick={() => setTab(t)} className={s.tab}>
            {t}
            {t === tab && <SlideMarker id="notification-tab" className={s.tabLine} duration={320} />}
          </button>
        ))}
      </BlurIn>
      <div className={s.notifications}>
        {notifications.map((n, k) => (
          <BlurIn key={n.who} i={8 + k} className={cx(s.notification, kit.lift)}>
            <Avatar name={n.who} size={30} />
            <div className={s.notificationBody}>
              <div className={s.notificationTop}>
                <p className={s.notificationText}>
                  <span className={s.strong}>{n.who}</span> {n.what}
                </p>
                <button className={cx(kit.press, s.link)}>
                  <Link2 size={11} strokeWidth={2} />
                  Link
                </button>
              </div>
              <div className={s.notificationMeta}>
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
    <BlurIn i={i} className={cx(s.video, kit.lift)}>
      <div className={s.thumb}>
        <Blob tone={video.tone} seed={video.seed} className={s.fill} />
        <span className={s.length}>{video.length}</span>
      </div>
      <div className={s.videoBody}>
        <p className={s.videoTitle}>{video.title}</p>
        <div className={s.videoMeta}>
          <span className={s.inline}>
            <CalendarDays size={11} strokeWidth={1.6} />
            {video.date}
          </span>
          <Eye size={11} strokeWidth={1.6} />
          <span className={s.inline}>
            <MessageSquare size={11} strokeWidth={1.6} />
            {video.comments}
          </span>
        </div>
      </div>
    </BlurIn>
  );
}
