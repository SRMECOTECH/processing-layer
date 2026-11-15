import { NextResponse, type NextRequest } from "next/server";
import { HOME, SESSION_COOKIE, mayCall, mayOpen, readSession } from "@/lib/session";

/**
 * Sign-in gate in front of every page and /api call. Without a valid session cookie a page goes to /login
 * (and comes back after signing in) and an /api call gets 401. Signed-in visitors skip the sign-in page.
 *
 * Then the role: the GPS provider (DEVELOPER) may open everything; a transporter only /transporter/** and a consigner
 * only /consigner/**, and both only the portal API (never /api/portal/admin, never the engine's own API). A page of
 * another role sends them to their own home; an API call outside their part is refused with 403. The GPS service
 * checks the token again and cuts every answer to the user's company.
 */
export async function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const session = await readSession(request.cookies.get(SESSION_COOKIE)?.value);

  if (pathname === "/login") {
    return session ? NextResponse.redirect(new URL(HOME[session.role], request.url)) : NextResponse.next();
  }
  if (session) {
    if (pathname.startsWith("/api/")) {
      return mayCall(session.role, pathname)
        ? NextResponse.next()
        : NextResponse.json({ error: "Your role cannot use this." }, { status: 403, headers: { "cache-control": "no-store" } });
    }
    if (pathname === "/" || !mayOpen(session.role, pathname)) {
      return NextResponse.redirect(new URL(HOME[session.role], request.url));
    }
    return NextResponse.next();
  }

  if (pathname.startsWith("/api/")) {
    return NextResponse.json({ error: "Sign in to use the dashboard." }, { status: 401, headers: { "cache-control": "no-store" } });
  }
  const signIn = new URL("/login", request.url);
  if (pathname !== "/") signIn.searchParams.set("next", pathname + search);
  return NextResponse.redirect(signIn);
}

export const config = {
  // Everything except the built JS/CSS, images and fonts.
  matcher: ["/((?!_next/static|_next/image|favicon\\.ico|.*\\.(?:png|jpg|jpeg|gif|svg|ico|webp|woff2?)$).*)"],
};
