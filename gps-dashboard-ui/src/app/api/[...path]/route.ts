// Forwards /api/* from the browser to gps-streaming-service, so the browser only ever talks to this
// server (no CORS) and the service address can change per environment:
//   local:  GPS_API_URL unset           -> http://localhost:8080
//   docker: GPS_API_URL=http://gps-service:8080

const SERVICE_URL = (process.env.GPS_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
// The demo reset takes about 6 s; everything else answers in milliseconds.
const TIMEOUT_MILLIS = 30_000;

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ path: string[] }> };

async function forward(request: Request, context: Context): Promise<Response> {
  const { path } = await context.params;
  const query = new URL(request.url).search;
  const target = `${SERVICE_URL}/api/${path.map(encodeURIComponent).join("/")}${query}`;

  const hasBody = request.method === "PUT" || request.method === "POST";
  const body = hasBody ? await request.text() : undefined;
  try {
    const answer = await fetch(target, {
      method: request.method,
      headers: {
        accept: "application/json",
        ...(body ? { "content-type": request.headers.get("content-type") ?? "application/json" } : {}),
      },
      body: body || undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MILLIS),
    });
    return new Response(answer.body, {
      status: answer.status,
      headers: {
        "content-type": answer.headers.get("content-type") ?? "application/json",
        "cache-control": "no-store",
      },
    });
  } catch {
    return Response.json(
      { error: `The GPS service is not reachable at ${SERVICE_URL}` },
      { status: 502, headers: { "cache-control": "no-store" } },
    );
  }
}

export { forward as GET, forward as POST, forward as PUT, forward as DELETE };
