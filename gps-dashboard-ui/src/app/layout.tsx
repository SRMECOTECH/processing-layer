import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { AppHeader } from "@/components/shell/app-header";
import { Sidebar } from "@/components/shell/sidebar";
import { OverviewProvider } from "@/lib/overview-context";
import { APPLY_SAVED_THEME, PreferencesProvider } from "@/lib/preferences";
import "./globals.css";

const sans = Geist({ subsets: ["latin"], variable: "--font-sans", display: "swap" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-mono", display: "swap" });

export const metadata: Metadata = {
  title: "PulseStream IoT",
  description: "Operations console for the GPS streaming platform: stream processors, business rules, ingestion and analytics.",
};

export const viewport: Viewport = {
  themeColor: "#ffffff",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      className={`${sans.variable} ${mono.variable}`}
      data-bg="offwhite"
      data-accent="navy"
      data-sidebar="open"
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: APPLY_SAVED_THEME }} />
      </head>
      <body>
        <PreferencesProvider>
          <OverviewProvider>
            <AppHeader />
            <div className="app-body">
              <Sidebar />
              <main className="app-main">{children}</main>
            </div>
          </OverviewProvider>
        </PreferencesProvider>
      </body>
    </html>
  );
}
