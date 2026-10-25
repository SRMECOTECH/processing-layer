"use client";

import { Flower } from "../art";
import { BlurIn, RevealText } from "../kit";
import { NAV, type PageId } from "../sidebar";

/** Videos, Messages, Settings and Calls are in the menu but not designed yet. */
export function PlaceholderPage({ id }: { id: PageId }) {
  const item = NAV.find((n) => n.id === id)!;
  return (
    <div className="grid h-full place-items-center">
      <BlurIn i={0} className="flex w-[380px] flex-col items-center rounded-card bg-white px-8 pt-6 pb-8 text-center">
        <Flower className="size-[120px]" />
        <h1 className="mt-2 text-[26px] leading-tight">
          <RevealText text={item.label} i={1} />
        </h1>
        <p className="mt-2 text-[13px] text-muted">This space is still being designed. Check back soon.</p>
      </BlurIn>
    </div>
  );
}
