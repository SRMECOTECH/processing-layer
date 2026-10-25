"use client";

import { AnimatePresence, motion } from "framer-motion";
import { useState } from "react";
import { DashboardPage } from "./pages/dashboard";
import { CloudPage } from "./pages/cloud";
import { PlaceholderPage } from "./pages/placeholder";
import { ProfilePage } from "./pages/profile";
import { SavedPage } from "./pages/saved";
import { BaseDelay } from "./kit";
import { Sidebar, type PageId } from "./sidebar";

// On app load the cards wait for the sidebar to grow and its icons to come in.
const LOAD_DELAY = 0.45;

/** Workspace Studio: lilac app frame, floating black sidebar, one page at a time. */
export function WorkspaceStudio() {
  const [page, setPage] = useState<PageId>("dashboard");
  const [loaded, setLoaded] = useState(false);

  function go(next: PageId) {
    if (next === page) return;
    setLoaded(true);
    setPage(next);
  }

  return (
    <div className="h-dvh min-h-[760px] min-w-[1180px] bg-white p-10">
      <div className="flex h-full gap-5 rounded-frame bg-canvas p-5">
        <Sidebar page={page} onNavigate={go} />
        <main className="relative min-w-0 flex-1">
          <BaseDelay.Provider value={loaded ? 0 : LOAD_DELAY}>
            <AnimatePresence mode="wait" initial>
              <motion.div
                key={page}
                className="h-full"
                exit={{ opacity: 0, filter: "blur(6px)", transition: { duration: 0.18 } }}
              >
                <Page id={page} />
              </motion.div>
            </AnimatePresence>
          </BaseDelay.Provider>
        </main>
      </div>
    </div>
  );
}

function Page({ id }: { id: PageId }) {
  switch (id) {
    case "dashboard":
      return <DashboardPage />;
    case "profile":
      return <ProfilePage />;
    case "saved":
      return <SavedPage />;
    case "cloud":
      return <CloudPage />;
    default:
      return <PlaceholderPage id={id} />;
  }
}
