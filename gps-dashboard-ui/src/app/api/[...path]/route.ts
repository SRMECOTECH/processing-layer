// Forwards /api/* from the browser to gps-streaming-service, so the browser only ever talks to this
// server (no CORS) and the service address can change per environment:
//   local:  GPS_API_URL unset           -> http://localhost:8080
//   docker: GPS_API_URL=http://gps-service:8080
// Only for a signed-in browser, and only the paths its role may call: src/proxy.ts already turns others away, and
// this checks again in case it is skipped. Portal calls carry the user's GPS-service token (Authorization: Bearer),
// so the service - not the browser - decides whose data an answer holds.

import { cookies } from "next/headers";
import { SESSION_COOKIE, mayCall, readSession } from "@/lib/session";

const SERVICE_URL = (process.env.GPS_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
// The demo reset takes about 6 s; everything else answers in milliseconds.
const TIMEOUT_MILLIS = 30_000;

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ path: string[] }> };

const NO_STORE = { "cache-control": "no-store" };

async function forward(request: Request, context: Context): Promise<Response> {
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);
  if (!session) {
    return Response.json({ error: "Sign in to use the dashboard." }, { status: 401, headers: NO_STORE });
  }
  const { path } = await context.params;
  const apiPath = `/api/${path.map(encodeURIComponent).join("/")}`;
  if (!mayCall(session.role, apiPath)) {
    return Response.json({ error: "Your role cannot use this." }, { status: 403, headers: NO_STORE });
  }
  const portal = apiPath.startsWith("/api/portal/");
  if (portal && !session.token) {
    return Response.json(
      { error: "You signed in while the GPS service was down, so the transport pages are not available. Sign out and in again." },
      { status: 503, headers: NO_STORE },
    );
  }
  const query = new URL(request.url).search;
  const target = `${SERVICE_URL}${apiPath}${query}`;

  const hasBody = request.method === "PUT" || request.method === "POST";
  // bytes, not text: an uploaded Excel file must arrive exactly as it was
  const body = hasBody ? await request.arrayBuffer() : undefined;
  try {
    const answer = await fetch(target, {
      method: request.method,
      headers: {
        accept: request.headers.get("accept") ?? "application/json",
        ...(body && body.byteLength > 0 ? { "content-type": request.headers.get("content-type") ?? "application/json" } : {}),
        ...(portal ? { authorization: `Bearer ${session.token}` } : {}),
      },
      body: body && body.byteLength > 0 ? body : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MILLIS),
    });
    const headers: Record<string, string> = {
      "content-type": answer.headers.get("content-type") ?? "application/json",
      "cache-control": "no-store",
    };
    const disposition = answer.headers.get("content-disposition");
    if (disposition) headers["content-disposition"] = disposition;
    return new Response(answer.body, { status: answer.status, headers });
  } catch {
    return Response.json({ error: `The GPS service is not reachable at ${SERVICE_URL}` }, { status: 502, headers: NO_STORE });
  }
}

export { forward as GET, forward as POST, forward as PUT, forward as DELETE };
