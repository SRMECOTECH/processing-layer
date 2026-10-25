import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";
import "./studio.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter", display: "swap" });

export const metadata: Metadata = {
  title: "Workspace Studio",
  description: "Team workspace: members, videos, notifications, saved items and cloud storage.",
};

export const viewport: Viewport = {
  themeColor: "#ffffff",
};

/** A root layout of its own: the studio does not share the console's header, sidebar or CSS. */
export default function StudioLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="font-sans">{children}</body>
    </html>
  );
}
