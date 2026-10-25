import type { Metadata, Viewport } from "next";
import { Geist_Mono, Inter } from "next/font/google";
import { AppHeader } from "@/components/shell/app-header";
import { Rail } from "@/components/shell/rail";
import { Sidebar } from "@/components/shell/sidebar";
import { OverviewProvider } from "@/lib/overview-context";
import { APPLY_SAVED_THEME, PreferencesProvider } from "@/lib/preferences";
import "./globals.css";

const sans = Inter({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "PulseStream IoT",
  description: "Operations console for the GPS streaming platform: stream processors, business rules, ingestion and analytics.",
};

export const viewport: Viewport = {
  themeColor: "#ffffff",
};

/**
 * The console sits in one rounded lilac frame: the black section rail, the white menu of sub-pages,
 * and the scrolling page with its top bar.
 */
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      className={`${sans.variable} ${mono.variable}`}
      data-bg="lilac"
      data-accent="lavender"
      data-sidebar="open"
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: APPLY_SAVED_THEME }} />
      </head>
      <body>
        <PreferencesProvider>
          <OverviewProvider>
            <div className="frame">
              <Rail />
              <Sidebar />
              <div className="app-scroll">
                <AppHeader />
                <main className="app-main">{children}</main>
              </div>
            </div>
          </OverviewProvider>
        </PreferencesProvider>
      </body>
    </html>
  );
}
