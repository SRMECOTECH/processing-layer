// Studio theme: the same saved preference as the console (localStorage "pulsestream.preferences", field `theme`),
// so switching to dark here also opens the console in dark, and back.

export type StudioTheme = "light" | "dark";

export const PREFERENCES_KEY = "pulsestream.preferences";

/**
 * Runs in the page head before anything is drawn (see app/(studio)/layout.tsx), so a dark page never flashes white.
 * Kept as a string because it is inlined into the head.
 */
export const APPLY_STUDIO_THEME = `try{var p=JSON.parse(localStorage.getItem("${PREFERENCES_KEY}")||"{}");var t=p.theme||"light";if(t==="system")t=matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light";document.documentElement.dataset.theme=t;}catch(e){}`;
