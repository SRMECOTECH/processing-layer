"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { REMEMBER_DAYS, SESSION_COOKIE, SESSION_HOURS, checkAccount, createSession } from "./session";

/** What the form shows after a failed try: the message, the user name and checkbox to keep, and a counter (shake again). */
export type SignInState = { error: string; username: string; remember: boolean; attempt: number } | null;

// Wrong passwords per browser address: after MAX_FAILURES within the window, sign-in waits LOCK_SECONDS.
const MAX_FAILURES = 5;
const LOCK_SECONDS = 60;
const failures: Map<string, { count: number; until: number }> = ((globalThis as Record<string, unknown>).__pulsestreamSignInFailures ??= new Map()) as Map<
  string,
  { count: number; until: number }
>;

/** Checks the form against the account; on success sets the session cookie and goes to the page asked for. */
export async function signIn(previous: SignInState, form: FormData): Promise<SignInState> {
  const username = String(form.get("username") ?? "").trim();
  const password = String(form.get("password") ?? "");
  const remember = form.get("remember") === "on";
  const attempt = (previous?.attempt ?? 0) + 1;
  const who = await visitor();

  const locked = failures.get(who);
  if (locked && locked.until > Date.now()) {
    const seconds = Math.ceil((locked.until - Date.now()) / 1000);
    return { error: `Too many attempts. Try again in ${seconds} seconds.`, username, remember, attempt };
  }
  if (!username || !password) {
    return { error: "Enter your username and password.", username, remember, attempt };
  }
  if (!(await checkAccount(username, password))) {
    // Counting starts again once a lock has run out.
    const count = (locked && !locked.until ? locked.count : 0) + 1;
    failures.set(who, { count, until: count >= MAX_FAILURES ? Date.now() + LOCK_SECONDS * 1000 : 0 });
    // A short pause makes guessing slow without bothering someone who mistyped.
    await new Promise((done) => setTimeout(done, 400));
    return { error: "That username and password don't match.", username, remember, attempt };
  }

  failures.delete(who);
  const seconds = remember ? REMEMBER_DAYS * 86_400 : SESSION_HOURS * 3_600;
  (await cookies()).set(SESSION_COOKIE, await createSession(username, seconds), {
    httpOnly: true,
    sameSite: "lax",
    secure: await isHttps(),
    path: "/",
    // Without "Keep me signed in" the cookie ends with the browser (and the session after SESSION_HOURS anyway).
    maxAge: remember ? seconds : undefined,
  });
  redirect(safeNext(form.get("next")));
}

/** Ends the session and shows the sign-in page. */
export async function signOut() {
  (await cookies()).delete(SESSION_COOKIE);
  redirect("/login");
}

/** Only a path on this site, so the sign-in page cannot be used to send someone elsewhere. */
function safeNext(value: FormDataEntryValue | null): string {
  const next = typeof value === "string" ? value : "";
  if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\") || next.startsWith("/login")) return "/";
  return next;
}

async function visitor(): Promise<string> {
  const h = await headers();
  return h.get("x-forwarded-for")?.split(",")[0].trim() || h.get("x-real-ip") || "local";
}

/** Secure cookies only over HTTPS (directly or behind a proxy that says so), so plain http://host:3000 still works. */
async function isHttps(): Promise<boolean> {
  if (process.env.AUTH_COOKIE_SECURE) return process.env.AUTH_COOKIE_SECURE === "true";
  return (await headers()).get("x-forwarded-proto") === "https";
}
