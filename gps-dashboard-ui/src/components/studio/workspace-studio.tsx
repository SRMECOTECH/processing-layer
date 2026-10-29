"use client";

import { useEffect, useRef, useState } from "react";
import { DashboardPage } from "./pages/dashboard";
import { CloudPage } from "./pages/cloud";
import { PlaceholderPage } from "./pages/placeholder";
import { ProfilePage } from "./pages/profile";
import { SavedPage } from "./pages/saved";
import { BaseDelay, cx } from "./kit";
import { Sidebar, type PageId } from "./sidebar";
import s from "./workspace-studio.module.css";

// On app load the cards wait for the sidebar to grow and its icons to come in.
const LOAD_DELAY = 0.45;
// The old page blurs out for this long before the new one comes in.
const LEAVE_MS = 180;

/** Workspace Studio: lilac app frame, floating black sidebar, one page at a time. */
export function WorkspaceStudio() {
  const [page, setPage] = useState<PageId>("dashboard");
  const [shown, setShown] = useState<PageId>("dashboard");
  const [loaded, setLoaded] = useState(false);
  const leaving = page !== shown;
  const timer = useRef(0);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  function go(next: PageId) {
    if (next === page) return;
    setLoaded(true);
    setPage(next);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setShown(next), LEAVE_MS);
  }

  return (
    <div className={s.page}>
      <div className={s.frame}>
        <Sidebar page={page} onNavigate={go} />
        <main className={s.main}>
          <BaseDelay.Provider value={loaded ? 0 : LOAD_DELAY}>
            <div key={shown} className={cx(s.view, leaving && s.leaving)}>
              <Page id={shown} />
            </div>
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
