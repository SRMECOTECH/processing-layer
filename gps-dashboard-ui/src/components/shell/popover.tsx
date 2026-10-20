"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

type Props = {
  label: string;
  button: ReactNode;
  children: (close: () => void) => ReactNode;
  width?: number;
  buttonClassName?: string;
};

/** A button that opens a small panel below it; closes on outside click or Escape. */
export function Popover({ label, button, children, width = 320, buttonClassName = "icon-button" }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      if (!ref.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div className="popover-anchor" ref={ref}>
      <button
        type="button"
        className={buttonClassName}
        aria-label={label}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {button}
      </button>
      <div className={`popover${open ? " open" : ""}`} style={{ width }} role="dialog" aria-label={label} hidden={!open}>
        {open && children(() => setOpen(false))}
      </div>
    </div>
  );
}
