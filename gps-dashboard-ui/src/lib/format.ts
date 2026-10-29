// Plain-text formatting used everywhere on the dashboard.

const whole = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
const oneDecimal = new Intl.NumberFormat("en-US", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "–";
  return whole.format(value);
}

export function formatDecimal(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "–";
  return oneDecimal.format(value);
}

/** 0.0 to 9.9 with one decimal, 10 and above as whole numbers. */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "–";
  return value < 10 ? oneDecimal.format(value) : whole.format(value);
}

export function formatDistance(meters: number | null | undefined): string {
  if (meters === null || meters === undefined || Number.isNaN(meters)) return "–";
  if (meters < 1000) return `${whole.format(meters)} m`;
  return `${oneDecimal.format(meters / 1000)} km`;
}

export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return "–";
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return `${s} s`;
  const minutes = Math.floor(s / 60);
  if (minutes < 60) return `${minutes} min ${s % 60} s`;
  const hours = Math.floor(minutes / 60);
  return `${hours} h ${minutes % 60} min`;
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "–";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "–";
  return date.toLocaleTimeString("en-GB", { hour12: false });
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "–";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "–";
  return `${date.toLocaleDateString("en-GB", { day: "2-digit", month: "short" })} ${date.toLocaleTimeString("en-GB", { hour12: false, hour: "2-digit", minute: "2-digit" })}`;
}

/** "12 s ago", "4 min ago", "2 h ago". */
export function formatAgo(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return "never";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "–";
  const seconds = Math.max(0, Math.round((now - then) / 1000));
  if (seconds < 2) return "just now";
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  return `${Math.round(minutes / 60)} h ago`;
}

export function formatCoordinate(latitude: number | string | undefined, longitude: number | string | undefined): string {
  const lat = Number(latitude);
  const lon = Number(longitude);
  if (latitude === undefined || longitude === undefined || Number.isNaN(lat) || Number.isNaN(lon)) return "–";
  return `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
}

export function formatPercent(fraction: number | null | undefined): string {
  if (fraction === null || fraction === undefined || Number.isNaN(fraction) || !Number.isFinite(fraction)) return "–";
  return `${oneDecimal.format(fraction * 100)}%`;
}

/** "gps-position-storage-service" stays as is; "RAW_FRAME_PROCESSING" becomes "Raw frame processing". */
export function humanize(value: string): string {
  if (!value.includes("_")) return value;
  const words = value.toLowerCase().split("_").join(" ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function vehicleName(registrationNo: string | null | undefined, imei: string): string {
  return registrationNo && registrationNo.length > 0 ? registrationNo : imei;
}

/** "512 B", "3.4 MB", "1.2 GB". */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return "–";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return unit === 0 ? `${whole.format(value)} B` : `${oneDecimal.format(value)} ${units[unit]}`;
}

/** Milliseconds as whole days ("31 days"); -1 means kept forever. */
export function formatRetention(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "–";
  if (ms < 0) return "forever";
  const days = ms / 86_400_000;
  return Number.isInteger(days) ? `${days} days` : `${oneDecimal.format(days)} days`;
}
