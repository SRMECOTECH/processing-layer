"use client";

import { AnimatePresence, motion } from "framer-motion";
import {
  Bookmark,
  Check,
  Clock,
  FolderKanban,
  Mail,
  MapPin,
  MessageCircle,
  PenLine,
  Upload,
  UserPlus,
  Users,
  Video,
} from "lucide-react";
import { useState } from "react";
import { Flower } from "../art";
import { Avatar, BlurIn, Card, CardTitle, CountUp, EASE, PillButton, PlusButton, useDelay } from "../kit";

const STATS = [
  { label: "Projects shipped", value: 36, caption: "8 this quarter", icon: FolderKanban },
  { label: "Videos published", value: 115, caption: "Top: Brain Aneurysm Clipping", icon: Video },
  { label: "Followers", value: 2240, caption: "+64 this month", icon: Users },
  { label: "Focus hours", value: 358, caption: "Since January", icon: Clock },
];

const SKILLS = ["Product strategy", "Design systems", "Prototyping", "User research", "Motion design", "Accessibility"];

const FOCUS = [
  { day: "Mon", fill: 50, color: "bg-pink" },
  { day: "Tue", fill: 55, color: "bg-pink" },
  { day: "Wed", fill: 50, color: "bg-lav", tip: "8.1h" },
  { day: "Thu", fill: 15, color: "bg-pink" },
  { day: "Fri", fill: 0, color: "bg-pink" },
  { day: "Sat", fill: 0, color: "bg-pink" },
  { day: "Sun", fill: 0, color: "bg-pink" },
];

const ACTIVITY = [
  { icon: Upload, text: 'Uploaded "Inside the Operating Room"', when: "2 hours ago", tint: "bg-lav-soft" },
  { icon: MessageCircle, text: 'Commented on "Q2 Design Review — Findings & Next Steps"', when: "Yesterday, 4:12 pm", tint: "bg-pink-soft" },
  { icon: Bookmark, text: 'Saved "Material motion guidelines"', when: "Apr 22", tint: "bg-sky" },
  { icon: UserPlus, text: "Added Daniel Turner to Workspace Studio", when: "Apr 18", tint: "bg-mint" },
];

const TEAM = [
  { name: "Sophia Brown", role: "Product Designer", img: 47 },
  { name: "Alex Johnson", role: "Frontend Engineer", img: 12 },
  { name: "Daniel Turner", role: "UX Researcher", img: 33 },
  { name: "Jennifer Campbell", role: "Engineering Manager", img: 44 },
];

export function ProfilePage() {
  return (
    <div className="no-scrollbar h-full overflow-y-auto">
      <div className="flex flex-col gap-5 pb-1">
        <HeaderCard />
        <div className="grid grid-cols-4 gap-5">
          {STATS.map((s, n) => (
            <Card key={s.label} i={1 + n}>
              <div className="flex items-center gap-2">
                <span className="grid size-7 place-items-center rounded-full bg-lav-soft text-lav-deep">
                  <s.icon size={13} strokeWidth={1.8} />
                </span>
                <span className="text-[12px] text-text-2">{s.label}</span>
              </div>
              <div className="mt-4 text-[34px] leading-none font-medium tracking-[-0.02em]">
                <CountUp value={s.value} i={1 + n} />
              </div>
              <div className="mt-2 truncate text-[11px] text-muted">{s.caption}</div>
            </Card>
          ))}
        </div>
        <div className="grid grid-cols-[3fr_2fr] gap-5">
          <AboutCard />
          <FocusCard />
        </div>
        <div className="grid grid-cols-[3fr_2fr] gap-5">
          <ActivityCard />
          <TeamCard />
        </div>
      </div>
    </div>
  );
}

function HeaderCard() {
  return (
    <BlurIn i={0} className="overflow-hidden rounded-card bg-white">
      <div className="relative h-[116px] overflow-hidden bg-[linear-gradient(90deg,#ccc1e8_0%,#e2d3f2_55%,#f2ceeb_100%)]">
        <Flower className="pointer-events-none absolute -top-[96px] right-[150px] size-[260px] opacity-90" />
      </div>
      <div className="flex items-end gap-5 px-6 pb-5">
        <div className="-mt-10 shrink-0 rounded-full bg-white p-1">
          <Avatar img={45} size={80} />
        </div>
        <div className="min-w-0 flex-1 pt-3">
          <h1 className="text-[24px] leading-tight">Hannah Brooks</h1>
          <div className="mt-0.5 text-[13px] text-text-2">Lead Product Designer · Workspace Studio</div>
          <div className="mt-2 flex items-center gap-3 text-[11px] text-muted">
            <span className="inline-flex items-center gap-1">
              <MapPin size={12} strokeWidth={1.7} />
              Lisbon, Portugal
            </span>
            <span>·</span>
            <span className="inline-flex items-center gap-1">
              <Mail size={12} strokeWidth={1.7} />
              hannah@workspace.studio
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2 pb-1">
          <span className="inline-flex h-9 items-center gap-2 rounded-full bg-mint px-4 text-[12px] font-medium">
            <span className="size-2 rounded-full bg-green" />
            Available
          </span>
          <PillButton icon={PenLine}>Edit profile</PillButton>
        </div>
      </div>
    </BlurIn>
  );
}

function AboutCard() {
  const [picked, setPicked] = useState<string[]>(["Product strategy"]);
  const toggle = (s: string) => setPicked((p) => (p.includes(s) ? p.filter((x) => x !== s) : [...p, s]));
  return (
    <Card i={5}>
      <CardTitle>About</CardTitle>
      <p className="mt-3 max-w-[620px] text-[13px] leading-[1.6] text-text-2">
        I lead design for our collaboration tools — turning messy team workflows into calm, focused spaces. Previously at
        two health-tech startups, so neurology videos feel like home.
      </p>
      <div className="mt-5 flex items-baseline gap-2">
        <span className="text-[13px] font-medium">Skills</span>
        <span className="text-[11px] text-muted">tap to highlight</span>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {SKILLS.map((s) => {
          const on = picked.includes(s);
          return (
            <motion.button
              key={s}
              layout
              onClick={() => toggle(s)}
              transition={{ type: "spring", stiffness: 400, damping: 32 }}
              className={`press inline-flex h-8 items-center gap-1.5 rounded-full px-3.5 text-[12px] ${on ? "bg-ink text-white" : "bg-track text-text-2"}`}
            >
              <AnimatePresence initial={false}>
                {on && (
                  <motion.span initial={{ width: 0, opacity: 0 }} animate={{ width: 13, opacity: 1 }} exit={{ width: 0, opacity: 0 }}>
                    <Check size={13} strokeWidth={2.2} />
                  </motion.span>
                )}
              </AnimatePresence>
              {s}
            </motion.button>
          );
        })}
      </div>
    </Card>
  );
}

function FocusCard() {
  const delay = useDelay(6) + 0.15;
  return (
    <Card i={6} className="flex flex-col">
      <div className="flex items-baseline justify-between">
        <CardTitle>Focus this week</CardTitle>
        <span className="text-[17px] font-medium">
          <CountUp value={34.8} i={6} format={(n) => `${n.toFixed(1)}h`} />
        </span>
      </div>
      <div className="mt-6 flex flex-1 justify-between px-1">
        {FOCUS.map((d, n) => (
          <div key={d.day} className="flex flex-col items-center gap-2">
            <div className="relative flex h-[150px] w-[26px] items-end rounded-full bg-track">
              <motion.div
                className={`relative w-full rounded-full ${d.color}`}
                initial={{ height: "0%" }}
                animate={{ height: `${d.fill}%` }}
                transition={{ duration: 0.7, delay: delay + n * 0.05, ease: EASE }}
              >
                {d.tip && (
                  <motion.span
                    className="absolute -top-8 left-1/2 -translate-x-1/2 rounded-lg bg-ink px-2 py-1 text-[10px] leading-none whitespace-nowrap text-white shadow-float"
                    initial={{ opacity: 0, y: 4 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: delay + 0.6 }}
                  >
                    {d.tip}
                  </motion.span>
                )}
              </motion.div>
            </div>
            <span className="text-[11px] text-muted">{d.day}</span>
          </div>
        ))}
      </div>
    </Card>
  );
}

function ActivityCard() {
  return (
    <Card i={7}>
      <CardTitle>Recent activity</CardTitle>
      <ul className="mt-3 flex flex-col">
        {ACTIVITY.map((a) => (
          <li key={a.text} className="flex items-center gap-3 border-b border-track py-2.5 last:border-0">
            <span className={`grid size-8 shrink-0 place-items-center rounded-full ${a.tint}`}>
              <a.icon size={14} strokeWidth={1.7} className="text-text" />
            </span>
            <span className="min-w-0 flex-1 truncate text-[12px]">{a.text}</span>
            <span className="shrink-0 text-[11px] text-muted">{a.when}</span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function TeamCard() {
  return (
    <Card i={8}>
      <div className="flex items-center justify-between">
        <CardTitle>Team</CardTitle>
        <PlusButton label="Invite teammate" />
      </div>
      <ul className="mt-3 flex flex-col gap-1">
        {TEAM.map((t) => (
          <li key={t.name} className="flex items-center gap-3 rounded-xl py-1.5">
            <Avatar img={t.img} size={32} />
            <div className="min-w-0 flex-1">
              <div className="text-[12px] font-medium">{t.name}</div>
              <div className="text-[11px] text-muted">{t.role}</div>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}
