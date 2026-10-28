import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { Geist_Mono, Inter } from "next/font/google";
import { Offline } from "@/components/ops/kit";
import { AppHeader } from "@/components/shell/app-header";
import { Sidebar } from "@/components/shell/sidebar";
import { NotificationsProvider } from "@/lib/notifications";
import { OverviewProvider } from "@/lib/overview-context";
import { APPLY_SAVED_THEME, PreferencesProvider } from "@/lib/preferences";
import { SESSION_COOKIE, readSession } from "@/lib/session";
import "./globals.css";

const sans = Inter({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "PulseStream IoT",
  description: "Operations console for the GPS streaming platform: live fleet, stream processors, alerts, storage and notifications.",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0c0b0f" },
  ],
};

/**
 * The console sits in one rounded frame: the top bar with the sections across the top, then the white menu of the
 * current section's pages on the left and the scrolling page beside it.
 */
export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  // src/proxy.ts sends visitors without a session to /login; checked again here so the console never renders without one.
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);
  if (!session) redirect("/login");

  return (
    <html
      lang="en"
      className={`${sans.variable} ${mono.variable}`}
      data-theme="light"
      data-bg="lilac"
      data-accent="lavender"
      data-density="comfortable"
      data-sidebar="open"
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: APPLY_SAVED_THEME }} />
      </head>
      <body>
        <PreferencesProvider>
          <OverviewProvider>
            <NotificationsProvider>
              <div className="frame">
                <AppHeader user={session.user} />
                <Sidebar />
                <div className="app-scroll">
                  <main className="app-main">
                    <Offline />
                    {children}
                  </main>
                </div>
              </div>
            </NotificationsProvider>
          </OverviewProvider>
        </PreferencesProvider>
      </body>
    </html>
  );
}
