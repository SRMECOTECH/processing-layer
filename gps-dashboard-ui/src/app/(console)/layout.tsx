import type { Metadata, Viewport } from "next";
import { Geist_Mono, Inter } from "next/font/google";
import { Offline } from "@/components/ops/kit";
import { AppHeader } from "@/components/shell/app-header";
import { Rail } from "@/components/shell/rail";
import { Sidebar } from "@/components/shell/sidebar";
import { NotificationsProvider } from "@/lib/notifications";
import { OverviewProvider } from "@/lib/overview-context";
import { APPLY_SAVED_THEME, PreferencesProvider } from "@/lib/preferences";
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
 * The console sits in one rounded frame: the black rail of sections, the white menu of the current
 * section's pages, and the scrolling page with its top bar.
 */
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
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
                <Rail />
                <Sidebar />
                <div className="app-scroll">
                  <AppHeader />
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
