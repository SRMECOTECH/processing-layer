// Sign-in session for the dashboard, with no auth package: the cookie holds the user name and an expiry time,
// signed with HMAC-SHA256 (Web Crypto). The server keeps no session store, so a restart keeps people signed in.
// Used by src/proxy.ts (every request), the API forwarder and the sign-in actions (src/lib/auth-actions.ts).

export const SESSION_COOKIE = "pulsestream_session";
/** A normal sign-in lasts this long; "Keep me signed in" lasts REMEMBER_DAYS. */
export const SESSION_HOURS = 12;
export const REMEMBER_DAYS = 30;

export type Session = { user: string; expires: number };

/** The one account that may sign in: DASHBOARD_USERNAME / DASHBOARD_PASSWORD, or "abc" / "abc" until they are set. */
function account() {
  return {
    username: process.env.DASHBOARD_USERNAME || "abc",
    password: process.env.DASHBOARD_PASSWORD || "abc",
  };
}

const encoder = new TextEncoder();
let cached: { secret: string; key: Promise<CryptoKey> } | undefined;

/**
 * The signing key. AUTH_SECRET when set; otherwise it is derived from the account (PBKDF2, so a stolen cookie
 * does not reveal the password quickly). Either way only the server can make a valid cookie, and changing
 * the password or AUTH_SECRET signs everyone out.
 */
function signingKey(): Promise<CryptoKey> {
  const { username, password } = account();
  const secret = process.env.AUTH_SECRET || `account:${username}:${password}`;
  if (cached?.secret !== secret) {
    cached = { secret, key: deriveKey(secret, !process.env.AUTH_SECRET) };
  }
  return cached.key;
}

async function deriveKey(secret: string, stretch: boolean): Promise<CryptoKey> {
  const usage: KeyUsage[] = ["sign", "verify"];
  const hmac = { name: "HMAC", hash: "SHA-256", length: 256 };
  if (!stretch) return crypto.subtle.importKey("raw", encoder.encode(secret), hmac, false, usage);
  const base = await crypto.subtle.importKey("raw", encoder.encode(secret), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt: encoder.encode("pulsestream-dashboard-session"), iterations: 210_000 },
    base,
    hmac,
    false,
    usage,
  );
}

/** A signed cookie value for `user`, valid for `seconds`. */
export async function createSession(user: string, seconds: number): Promise<string> {
  const session: Session = { user, expires: Math.floor(Date.now() / 1000) + seconds };
  const payload = toBase64Url(encoder.encode(JSON.stringify(session)));
  const signature = await crypto.subtle.sign("HMAC", await signingKey(), encoder.encode(payload));
  return `${payload}.${toBase64Url(new Uint8Array(signature))}`;
}

/** The session in a cookie value, or null when it is missing, tampered with or expired. */
export async function readSession(token: string | undefined): Promise<Session | null> {
  if (!token) return null;
  const [payload, signature, extra] = token.split(".");
  if (!payload || !signature || extra !== undefined) return null;
  try {
    const valid = await crypto.subtle.verify("HMAC", await signingKey(), fromBase64Url(signature), encoder.encode(payload));
    if (!valid) return null;
    const session = JSON.parse(new TextDecoder().decode(fromBase64Url(payload))) as Session;
    if (typeof session.user !== "string" || typeof session.expires !== "number") return null;
    return session.expires > Date.now() / 1000 ? session : null;
  } catch {
    return null;
  }
}

/** True when the user name and password match the account. Takes the same time whichever part is wrong. */
export async function checkAccount(username: string, password: string): Promise<boolean> {
  const expected = account();
  const [a, b] = await Promise.all([
    digest(`${username}\u0000${password}`),
    digest(`${expected.username}\u0000${expected.password}`),
  ]);
  let difference = 0;
  for (let n = 0; n < a.length; n++) difference |= a[n] ^ b[n];
  return difference === 0;
}

async function digest(text: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(text)));
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text: string): Uint8Array<ArrayBuffer> {
  const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = new Uint8Array(binary.length);
  for (let n = 0; n < binary.length; n++) bytes[n] = binary.charCodeAt(n);
  return bytes;
}
