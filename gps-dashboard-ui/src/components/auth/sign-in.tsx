"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import {
  ArrowRight,
  BellRing,
  Check,
  Eye,
  EyeOff,
  LoaderCircle,
  LockKeyhole,
  MapPin,
  Moon,
  ShieldCheck,
  Sun,
  TriangleAlert,
  User,
  Waves,
  Workflow,
} from "@/components/icons";
import { Flower } from "@/components/studio/art";
import { RevealText, after, cx } from "@/components/studio/kit";
import { useTheme } from "@/components/studio/use-theme";
import { signIn, type SignInState } from "@/lib/auth-actions";
import s from "./sign-in.module.css";

const FEATURES = [
  { icon: MapPin, label: "Live fleet map", note: "Every vehicle, as it moves" },
  { icon: Workflow, label: "Stream processors", note: "Speed, stoppage, geofence, distance" },
  { icon: BellRing, label: "Alerts that matter", note: "Checked twice before they reach you" },
];

const SHAKE: Keyframe[] = [
  { transform: "translateX(0)" },
  { transform: "translateX(-8px)" },
  { transform: "translateX(7px)" },
  { transform: "translateX(-5px)" },
  { transform: "translateX(3px)" },
  { transform: "translateX(0)" },
];

/**
 * The sign-in page: a lavender panel about the console on the left, the form on the right. The form posts to
 * the signIn server action, so it works before the page's script has loaded; with it, a wrong password shakes
 * the card and keeps the user name.
 */
export function SignIn({ next }: { next: string }) {
  const [state, action, pending] = useActionState<SignInState, FormData>(signIn, null);
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [theme, toggleTheme] = useTheme();
  const card = useRef<HTMLDivElement>(null);
  const password = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!state) return;
    if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) card.current?.animate(SHAKE, { duration: 420, easing: "ease-out" });
    if (state.username) password.current?.focus();
  }, [state]);

  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => setCapsLock(e.getModifierState("CapsLock"));
  const ThemeIcon = theme === "dark" ? Sun : Moon;

  return (
    <div className={s.page}>
      <div className={s.frame}>
        <section className={s.hero}>
          <div className={s.brand}>
            <span className={s.brandMark}>
              <Waves size={17} strokeWidth={2.2} />
            </span>
            PulseStream IoT
          </div>

          <div className={s.heroCopy}>
            <h2 className={s.heroTitle}>
              <RevealText text="Every vehicle, every event, live." i={1} />
            </h2>
            <p className={s.heroLead} style={after(0.35)}>
              The operations console for your GPS streaming platform: the live fleet, the stream processors, alerts and
              storage in one calm place.
            </p>
          </div>

          <ul className={s.features}>
            {FEATURES.map((f, n) => (
              <li key={f.label} className={s.feature} style={{ animationDelay: `${0.45 + n * 0.08}s, ${1.2 + n * 0.7}s` }}>
                <span className={s.featureIcon}>
                  <f.icon size={15} strokeWidth={1.8} />
                </span>
                <span>
                  <span className={s.featureLabel}>{f.label}</span>
                  <span className={s.featureNote}>{f.note}</span>
                </span>
              </li>
            ))}
          </ul>

          <Flower className={s.flowerBig} />
          <Flower className={s.flowerSmall} />
        </section>

        <main className={s.panel}>
          <button type="button" className={s.themeButton} onClick={toggleTheme} aria-label={theme === "dark" ? "Light mode" : "Dark mode"} title={theme === "dark" ? "Light mode" : "Dark mode"}>
            <ThemeIcon size={16} strokeWidth={1.8} />
          </button>

          <div ref={card} className={s.card}>
            <span className={s.mark}>
              <Waves size={20} strokeWidth={2.2} />
            </span>
            <h1 className={s.title}>
              <RevealText text="Welcome back" i={2} />
            </h1>
            <p className={s.lead}>Sign in to continue to the console.</p>

            <form action={action} className={s.form}>
              <input type="hidden" name="next" value={next} />

              <label className={s.field}>
                <span className={s.label}>Username</span>
                <span className={s.control}>
                  <User size={16} strokeWidth={1.8} />
                  <input
                    name="username"
                    autoComplete="username"
                    autoCapitalize="none"
                    spellCheck={false}
                    autoFocus
                    required
                    placeholder="Your username"
                    defaultValue={state?.username}
                    className={s.input}
                  />
                </span>
              </label>

              <label className={s.field}>
                <span className={s.labelRow}>
                  <span className={s.label}>Password</span>
                  {capsLock && <span className={s.caps}>Caps Lock is on</span>}
                </span>
                <span className={s.control}>
                  <LockKeyhole size={16} strokeWidth={1.8} />
                  <input
                    ref={password}
                    name="password"
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    required
                    placeholder="Your password"
                    onKeyDown={onKey}
                    onKeyUp={onKey}
                    className={s.input}
                  />
                  <button
                    type="button"
                    className={s.reveal}
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    aria-pressed={showPassword}
                  >
                    {showPassword ? <EyeOff size={16} strokeWidth={1.8} /> : <Eye size={16} strokeWidth={1.8} />}
                  </button>
                </span>
              </label>

              <label className={s.remember}>
                <input type="checkbox" name="remember" defaultChecked={state?.remember} className={s.checkbox} />
                <span className={s.box}>
                  <Check size={12} strokeWidth={3} />
                </span>
                Keep me signed in for 30 days
              </label>

              {state?.error && (
                <p role="alert" className={s.error}>
                  <TriangleAlert size={15} strokeWidth={1.9} />
                  {state.error}
                </p>
              )}

              <button type="submit" disabled={pending} className={s.submit}>
                {pending ? (
                  <>
                    <LoaderCircle size={16} strokeWidth={2} className={s.spinner} />
                    Signing in…
                  </>
                ) : (
                  <>
                    Sign in
                    <ArrowRight size={16} strokeWidth={2} className={s.arrow} />
                  </>
                )}
              </button>
            </form>

            <p className={s.note}>
              <ShieldCheck size={13} strokeWidth={1.8} />
              Your session is signed and ends after 12 hours
            </p>
          </div>
        </main>
      </div>
    </div>
  );
}
