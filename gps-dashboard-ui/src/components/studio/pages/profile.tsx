"use client";

import { useState } from "react";
import { Bookmark, Check, Clock, FolderKanban, Mail, MapPin, MessageCircle, PenLine, Upload, UserPlus, Users, Video } from "@/components/icons";
import { Flower } from "../art";
import { Avatar, BlurIn, Card, CardTitle, CountUp, GrowBar, PillButton, PlusButton, after, cx, kit, useDelay } from "../kit";
import s from "./profile.module.css";

const STATS = [
  { label: "Projects shipped", value: 36, caption: "8 this quarter", icon: FolderKanban },
  { label: "Videos published", value: 115, caption: "Top: Brain Aneurysm Clipping", icon: Video },
  { label: "Followers", value: 2240, caption: "+64 this month", icon: Users },
  { label: "Focus hours", value: 358, caption: "Since January", icon: Clock },
];

const SKILLS = ["Product strategy", "Design systems", "Prototyping", "User research", "Motion design", "Accessibility"];

const FOCUS = [
  { day: "Mon", fill: 50, color: kit.fillPink },
  { day: "Tue", fill: 55, color: kit.fillPink },
  { day: "Wed", fill: 50, color: kit.fillLav, tip: "8.1h" },
  { day: "Thu", fill: 15, color: kit.fillPink },
  { day: "Fri", fill: 0, color: kit.fillPink },
  { day: "Sat", fill: 0, color: kit.fillPink },
  { day: "Sun", fill: 0, color: kit.fillPink },
];

const ACTIVITY = [
  { icon: Upload, text: 'Uploaded "Inside the Operating Room"', when: "2 hours ago", tint: kit.tintLav },
  { icon: MessageCircle, text: 'Commented on "Q2 Design Review — Findings & Next Steps"', when: "Yesterday, 4:12 pm", tint: kit.tintPink },
  { icon: Bookmark, text: 'Saved "Material motion guidelines"', when: "Apr 22", tint: kit.tintSky },
  { icon: UserPlus, text: "Added Daniel Turner to Workspace Studio", when: "Apr 18", tint: kit.tintMint },
];

const TEAM = [
  { name: "Sophia Brown", role: "Product Designer" },
  { name: "Alex Johnson", role: "Frontend Engineer" },
  { name: "Daniel Turner", role: "UX Researcher" },
  { name: "Jennifer Campbell", role: "Engineering Manager" },
];

export function ProfilePage() {
  return (
    <div className={kit.scroll}>
      <div className={s.column}>
        <HeaderCard />
        <div className={s.stats}>
          {STATS.map((stat, n) => (
            <Card key={stat.label} i={1 + n}>
              <div className={s.statHead}>
                <span className={cx(s.chip, kit.tintLav)}>
                  <stat.icon size={13} strokeWidth={1.8} />
                </span>
                <span className={s.statLabel}>{stat.label}</span>
              </div>
              <div className={s.statValue}>
                <CountUp value={stat.value} i={1 + n} />
              </div>
              <div className={s.caption}>{stat.caption}</div>
            </Card>
          ))}
        </div>
        <div className={s.split}>
          <AboutCard />
          <FocusCard />
        </div>
        <div className={s.split}>
          <ActivityCard />
          <TeamCard />
        </div>
      </div>
    </div>
  );
}

function HeaderCard() {
  return (
    <BlurIn i={0} className={s.header}>
      <div className={s.banner}>
        <Flower className={s.bannerFlower} />
      </div>
      <div className={s.identity}>
        <div className={s.avatarRing}>
          <Avatar name="Hannah Brooks" size={80} />
        </div>
        <div className={s.who}>
          <h1 className={s.name}>Hannah Brooks</h1>
          <div className={s.role}>Lead Product Designer · Workspace Studio</div>
          <div className={s.contact}>
            <span className={s.inline}>
              <MapPin size={12} strokeWidth={1.7} />
              Lisbon, Portugal
            </span>
            <span>·</span>
            <span className={s.inline}>
              <Mail size={12} strokeWidth={1.7} />
              hannah@workspace.studio
            </span>
          </div>
        </div>
        <div className={s.actions}>
          <span className={s.available}>
            <span className={s.greenDot} />
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
  const toggle = (skill: string) => setPicked((p) => (p.includes(skill) ? p.filter((x) => x !== skill) : [...p, skill]));
  return (
    <Card i={5}>
      <CardTitle>About</CardTitle>
      <p className={s.about}>
        I lead design for our collaboration tools — turning messy team workflows into calm, focused spaces. Previously at two
        health-tech startups, so neurology videos feel like home.
      </p>
      <div className={s.skillsHead}>
        <span className={s.skillsTitle}>Skills</span>
        <span className={s.hint}>tap to highlight</span>
      </div>
      <div className={s.skills}>
        {SKILLS.map((skill) => (
          <button key={skill} onClick={() => toggle(skill)} aria-pressed={picked.includes(skill)} className={cx(kit.press, s.skill)}>
            <span className={s.check}>
              <Check size={13} strokeWidth={2.2} />
            </span>
            {skill}
          </button>
        ))}
      </div>
    </Card>
  );
}

function FocusCard() {
  const delay = useDelay(6) + 0.15;
  return (
    <Card i={6} className={s.focus}>
      <div className={s.focusHead}>
        <CardTitle>Focus this week</CardTitle>
        <span className={s.focusTotal}>
          <CountUp value={34.8} i={6} format={(n) => `${n.toFixed(1)}h`} />
        </span>
      </div>
      <div className={s.focusBars}>
        {FOCUS.map((d, n) => (
          <div key={d.day} className={s.focusDay}>
            <div className={s.focusTrack}>
              <GrowBar size={d.fill} axis="y" i={6} n={n} className={cx(s.focusFill, d.color)}>
                {d.tip && (
                  <span className={s.focusTip} style={after(delay + 0.6)}>
                    {d.tip}
                  </span>
                )}
              </GrowBar>
            </div>
            <span className={s.focusName}>{d.day}</span>
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
      <ul className={s.list}>
        {ACTIVITY.map((a) => (
          <li key={a.text} className={s.activity}>
            <span className={cx(s.activityIcon, a.tint)}>
              <a.icon size={14} strokeWidth={1.7} />
            </span>
            <span className={s.activityText}>{a.text}</span>
            <span className={s.when}>{a.when}</span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function TeamCard() {
  return (
    <Card i={8}>
      <div className={kit.row}>
        <CardTitle>Team</CardTitle>
        <PlusButton label="Invite teammate" />
      </div>
      <ul className={s.team}>
        {TEAM.map((t) => (
          <li key={t.name} className={s.teammate}>
            <Avatar name={t.name} size={32} />
            <div>
              <div className={s.teammateName}>{t.name}</div>
              <div className={s.teammateRole}>{t.role}</div>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  );
}
