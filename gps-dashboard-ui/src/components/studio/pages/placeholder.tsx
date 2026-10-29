"use client";

import { Flower } from "../art";
import { BlurIn, RevealText } from "../kit";
import { NAV, type PageId } from "../sidebar";
import s from "./placeholder.module.css";

/** Videos, Messages, Settings and Calls are in the menu but not designed yet. */
export function PlaceholderPage({ id }: { id: PageId }) {
  const item = NAV.find((n) => n.id === id)!;
  return (
    <div className={s.center}>
      <BlurIn i={0} className={s.box}>
        <Flower className={s.flower} />
        <h1 className={s.title}>
          <RevealText text={item.label} i={1} />
        </h1>
        <p className={s.lead}>This space is still being designed. Check back soon.</p>
      </BlurIn>
    </div>
  );
}
