"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { HOME, REMEMBER_DAYS, ROLES, SESSION_COOKIE, SESSION_HOURS, checkAccount, createSession, mayOpen, type Role, type Session } from "./session";

/** What the form shows after a failed try: the message, the user name, role and checkbox to keep, and a counter (shake again). */
export type SignInState = { error: string; username: string; role: Role; remember: boolean; attempt: number } | null;

const SERVICE_URL = (process.env.GPS_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");

type Login =
  | { ok: true; session: Omit<Session, "expires"> }
  | { ok: false; error: string; unreachable?: boolean };

/** Asks the GPS service, which keeps the users of all three roles, and hands back its token. */
async function login(role: Role, username: string, password: string, remember: boolean): Promise<Login> {
  try {
    const answer = await fetch(`${SERVICE_URL}/api/auth/login`, {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ role, username, password, remember }),
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });
    const body = (await answer.json().catch(() => ({}))) as {
      error?: string;
      token?: string;
      user?: { username: string; role: Role; displayName?: string; tenantCode?: string; tenantName?: string };
    };
    if (!answer.ok || !body.token || !body.user) {
      // a 5xx is the service failing, not the password being wrong
      return { ok: false, error: body.error ?? "That user id and password don't match for this role.", unreachable: answer.status >= 500 };
    }
    return {
      ok: true,
      session: {
        user: body.user.username,
        role: body.user.role,
        name: body.user.displayName,
        tenant: body.user.tenantCode ?? undefined,
        tenantName: body.user.tenantName ?? undefined,
        token: body.token,
      },
    };
  } catch {
    return { ok: false, error: "The GPS service is not reachable, so sign-in can't be checked.", unreachable: true };
  }
}

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
  const asked = String(form.get("role") ?? "DEVELOPER").toUpperCase() as Role;
  const role: Role = ROLES.includes(asked) ? asked : "DEVELOPER";
  const attempt = (previous?.attempt ?? 0) + 1;
  const who = await visitor();

  const locked = failures.get(who);
  if (locked && locked.until > Date.now()) {
    const seconds = Math.ceil((locked.until - Date.now()) / 1000);
    return { error: `Too many attempts. Try again in ${seconds} seconds.`, username, role, remember, attempt };
  }
  if (!username || !password) {
    return { error: "Enter your user id and password.", username, role, remember, attempt };
  }
  let result = await login(role, username, password, remember);
  // The service is down: the GPS provider can still get into the console (to see that it is down) with the
  // break-glass account from the environment. Transporters and consigners need the service.
  if (!result.ok && result.unreachable && role === "DEVELOPER" && (await checkAccount(username, password))) {
    result = { ok: true, session: { user: username, role: "DEVELOPER", name: "GPS provider (service offline)" } };
  }
  if (!result.ok) {
    if (!result.unreachable) {
      // Counting starts again once a lock has run out.
      const count = (locked && !locked.until ? locked.count : 0) + 1;
      failures.set(who, { count, until: count >= MAX_FAILURES ? Date.now() + LOCK_SECONDS * 1000 : 0 });
      // A short pause makes guessing slow without bothering someone who mistyped.
      await new Promise((done) => setTimeout(done, 400));
    }
    return { error: result.error, username, role, remember, attempt };
  }

  failures.delete(who);
  const seconds = remember ? REMEMBER_DAYS * 86_400 : SESSION_HOURS * 3_600;
  (await cookies()).set(SESSION_COOKIE, await createSession(result.session, seconds), {
    httpOnly: true,
    sameSite: "lax",
    secure: await isHttps(),
    path: "/",
    // Without "Keep me signed in" the cookie ends with the browser (and the session after SESSION_HOURS anyway).
    maxAge: remember ? seconds : undefined,
  });
  redirect(safeNext(form.get("next"), result.session.role));
}

/** Ends the session and shows the sign-in page. */
export async function signOut() {
  (await cookies()).delete(SESSION_COOKIE);
  redirect("/login");
}

/**
 * Only a path on this site, so the sign-in page cannot be used to send someone elsewhere - and only one the role may
 * open; otherwise the role's own home.
 */
function safeNext(value: FormDataEntryValue | null, role: Role): string {
  const next = typeof value === "string" ? value : "";
  if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\") || next.startsWith("/login") || next === "/") return HOME[role];
  return mayOpen(role, next.split("?")[0]) ? next : HOME[role];
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
