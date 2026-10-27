import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import { APPLY_STUDIO_THEME } from "@/components/studio/theme";
import "../(studio)/studio.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter", display: "swap" });

export const metadata: Metadata = {
  title: "Sign in · PulseStream IoT",
  description: "Sign in to the PulseStream operations console.",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0c0b0f" },
  ],
};

/** The sign-in page has its own root layout: the Studio colours and theme, none of the console around it. */
export default function AuthLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={inter.variable} data-theme="light" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: APPLY_STUDIO_THEME }} />
      </head>
      <body>{children}</body>
    </html>
  );
}
