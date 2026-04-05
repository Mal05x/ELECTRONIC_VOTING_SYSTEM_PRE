/** Shared UI primitives — used across all pages. */
import { useState } from "react";

/* ── Icon set (inline SVG, no deps) ── */
const P = {
  dashboard:  "M3 3h7v7H3zm11 0h7v7h-7zM3 14h7v7H3zm11 3.5a3.5 3.5 0 107 0 3.5 3.5 0 00-7 0",
  ballot:     "M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8zm-4 11H8m4-4H8m6-6v4h4",
  voters:     "M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2M9 7a4 4 0 100 8 4 4 0 000-8m14 14v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75",
  enroll:     "M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2M9 7a4 4 0 100 8 4 4 0 000-8m13 4h-6m3-3v6",
  tally:      "M18 20V10M12 20V4M6 20v-6",
  audit:      "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10",
  logout:     "M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4m7 14l5-5-5-5m5 5H9",
  plus:       "M12 5v14M5 12h14",
  search:     "M21 21l-4.35-4.35M17 11A6 6 0 105 11a6 6 0 0012 0",
  close:      "M18 6L6 18M6 6l12 12",
  check:      "M20 6L9 17l-5-5",
  lock:       "M19 11H5a2 2 0 00-2 2v7a2 2 0 002 2h14a2 2 0 002-2v-7a2 2 0 00-2-2zM7 11V7a5 5 0 0110 0v4",
  unlock:     "M19 11H5a2 2 0 00-2 2v7a2 2 0 002 2h14a2 2 0 002-2v-7a2 2 0 00-2-2zM7 11V7a5 5 0 019.9-1",
  bell:       "M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 01-3.46 0",
  shield:     "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10",
  chip:       "M9 3H5a2 2 0 00-2 2v4m6-6h10a2 2 0 012 2v4M9 3v18m0 0h10a2 2 0 002-2V9M9 21H5a2 2 0 01-2-2V9m0 0h18",
  refresh:    "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15",
  chevronL:   "M15 18l-6-6 6-6",
  vote:       "M9 11l3 3L22 4M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11",
  trend:      "M23 6l-9.5 9.5-5-5L1 18M17 6h6v6",
  flag:       "M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1zM4 22v-7",
  eye:        "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8zm11-3a3 3 0 100 6 3 3 0 000-6",
  warning:    "M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0zM12 9v4m0 4h.01",
  menu:       "M3 6h18M3 12h18M3 18h18",
  chat:       "M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2v10z",
  sun:        "M12 8a4 4 0 100 8 4 4 0 000-8zM12 2v2m0 16v2M4.22 4.22l1.42 1.42m12.72 12.72l1.42 1.42M2 12h2m16 0h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42",
  moon:       "M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z",
  settings:   "M12 15a3 3 0 100-6 3 3 0 000 6zm7.94-2a7.07 7.07 0 00.06-1 7.07 7.07 0 00-.06-1l2.03-1.58a.5.5 0 00.12-.64l-1.92-3.32a.5.5 0 00-.61-.22l-2.39.96a7.14 7.14 0 00-1.73-1l-.36-2.54A.484.484 0 0014 2h-4a.484.484 0 00-.48.41l-.36 2.54a7.14 7.14 0 00-1.73 1l-2.39-.96a.488.488 0 00-.61.22L2.51 8.53a.477.477 0 00.12.64L4.66 10.7A7.34 7.34 0 004.6 12a7.34 7.34 0 00.06.95l-2.03 1.58a.477.477 0 00-.12.64l1.92 3.32c.12.22.37.3.61.22l2.39-.96c.54.4 1.12.72 1.73 1l.36 2.54c.06.28.31.48.6.48h4c.29 0 .54-.2.59-.47l.36-2.54a7.14 7.14 0 001.73-1l2.39.96c.23.09.49 0 .61-.22l1.92-3.32a.477.477 0 00-.12-.64l-2.03-1.63z",
  save:       "M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2zm-7-1v-8H7v8m7 0v-5h-4",
  mail:       "M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2zm16 2l-8 5-8-5",
  terminal:   "M4 17l6-6-6-6m8 14h8",
  database:   "M12 2C6.48 2 2 4.02 2 6.5v11C2 19.98 6.48 22 12 22s10-2.02 10-4.5v-11C22 4.02 17.52 2 12 2zm0 2c4.97 0 8 1.8 8 2.5S16.97 9 12 9 4 7.2 4 6.5 7.03 4 12 4zm0 16c-4.97 0-8-1.8-8-2.5v-2.23C5.56 16.37 8.59 17 12 17s6.44-.63 8-1.73V17.5c0 .7-3.03 2.5-8 2.5z",
  info:       "M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10zm0-14v4m0 4h.01",
};

export function Ic({ n, s = 18, c = "currentColor", sw = 1.8, cls = "" }) {
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"
      stroke={c} strokeWidth={sw} strokeLinecap="round" strokeLinejoin="round"
      className={`block flex-shrink-0 ${cls}`}>
      {(P[n] || "").split("M").filter(Boolean).map((seg, i) => (
        <path key={i} d={"M" + seg} />
      ))}
    </svg>
  );
}

/* ── Status badge ── */
export function StatusBadge({ status }) {
  const map = {
    ACTIVE:    ["badge-green",  "● ACTIVE"],
    PENDING:   ["badge-amber",  "◌ PENDING"],
    CLOSED:    ["badge-grey",   "✕ CLOSED"],
    COMPLETED: ["badge-green",  "✓ DONE"],
    FAILED:    ["badge-red",    "✕ FAILED"],
    voted:     ["badge-purple", "✓ Voted"],
    locked:    ["badge-red",    "⚑ Locked"],
    open:      ["badge-grey",   "○ Open"],
  };
  const [cls, label] = map[status] || ["badge-grey", status];
  return <span className={`badge ${cls}`}>{label}</span>;
}

/* ── Stat card ── */
const ACCENT_STYLES = {
  purple: { icon: "bg-purple-500/15 border-purple-500/25", iconColor: "#A78BFA", glow: "hover:shadow-purple-sm" },
  green:  { icon: "bg-green-500/15  border-green-500/25",  iconColor: "#34D399", glow: "" },
  red:    { icon: "bg-red-500/15    border-red-500/25",    iconColor: "#F87171", glow: "" },
  amber:  { icon: "bg-yellow-500/15 border-yellow-500/25", iconColor: "#FCD34D", glow: "" },
  blue:   { icon: "bg-blue-500/15   border-blue-500/25",   iconColor: "#60A5FA", glow: "" },
};

export function StatCard({ label, value, sub, icon, accent = "purple", delay = 0 }) {
  const a = ACCENT_STYLES[accent] || ACCENT_STYLES.purple;
  return (
    <div className={`c-card p-6 animate-fade-up ${a.glow}`}
      style={{ animationDelay: `${delay}ms` }}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="sect-lbl mb-2.5">{label}</div>
          <div className="mono text-[28px] font-bold text-ink leading-none tracking-tight animate-ticker">
            {value}
          </div>
          {sub && <div className="text-xs font-medium text-sub mt-2">{sub}</div>}
        </div>
        <div className={`w-11 h-11 rounded-2xl flex items-center justify-center flex-shrink-0 border ${a.icon}`}>
          <Ic n={icon} s={20} c={a.iconColor} />
        </div>
      </div>
    </div>
  );
}

/* ── Progress bar ── */
export function Pbar({ pct, color = "#8B5CF6", fat = false, glow = true }) {
  return (
    <div className={fat ? "pbar-fat" : "pbar"}>
      <div className="pbar-fill" style={{
        width: `${Math.min(Math.max(pct, 0), 100)}%`,
        background: `linear-gradient(90deg, ${color}bb, ${color})`,
        boxShadow: glow ? `0 0 10px ${color}55` : "none",
      }} />
    </div>
  );
}

/* ── Section header ── */
export function SectionHeader({ title, sub, action }) {
  return (
    <div className="flex items-start justify-between gap-4 mb-5 flex-wrap">
      <div>
        <h2 className="text-base font-extrabold text-ink tracking-tight">{title}</h2>
        {sub && <p className="text-xs font-medium text-sub mt-1">{sub}</p>}
      </div>
      {action && <div className="flex-shrink-0">{action}</div>}
    </div>
  );
}

/* ── Modal ── */
export function Modal({ title, onClose, children, wide = false }) {
  return (
    <div className="modal-bg" onClick={e => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? "max-w-2xl" : "max-w-lg"}`}>
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-extrabold text-ink">{title}</h3>
          <button className="btn btn-ghost btn-sm !px-2.5 !py-2" onClick={onClose}>
            <Ic n="close" s={15} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

/* ── Form label ── */
export function Label({ children }) {
  return <label className="block text-xs font-semibold text-sub mb-1.5">{children}</label>;
}

/* ── Toast notification ── */
export function Toast({ message, type = "success", onClose }) {
  const colors = { success: "text-success", error: "text-danger", info: "text-purple-300" };
  const icons  = { success: "check", error: "warning", info: "bell" };
  return (
    <div className="toast">
      <Ic n={icons[type]} s={16} />
      <span className={colors[type]}>{message}</span>
      <button onClick={onClose} className="ml-2 text-muted hover:text-sub">
        <Ic n="close" s={13} />
      </button>
    </div>
  );
}

/* ── Custom recharts tooltip ── */
export function ChartTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-elevated border border-border-hi rounded-xl px-3.5 py-2.5 text-xs font-semibold shadow-card">
      <div className="text-ink font-bold mb-1.5">{label}</div>
      {payload.map(p => (
        <div key={p.name} className="flex items-center gap-2 text-sub">
          <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ background: p.color }} />
          <span>{p.name}:</span>
          <span className="mono text-ink">
            {typeof p.value === "number" && p.value > 10000
              ? (p.value / 1e6).toFixed(2) + "M"
              : p.value?.toLocaleString()}
          </span>
        </div>
      ))}
    </div>
  );
}

/* ── Skeleton loader ── */
export function Skeleton({ h = "h-10", w = "w-full", className = "" }) {
  return <div className={`skel ${h} ${w} ${className}`} />;
}

/* ── Empty state ── */
export function EmptyState({ icon = "search", title, sub }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-center">
      <div className="w-12 h-12 rounded-2xl bg-purple-500/10 flex items-center justify-center">
        <Ic n={icon} s={22} c="#8B5CF6" />
      </div>
      <div className="text-sm font-bold text-sub">{title}</div>
      {sub && <div className="text-xs text-muted max-w-xs">{sub}</div>}
    </div>
  );
}

/* ── Spinner ── */
export function Spinner({ s = 20 }) {
  return (
    <div style={{ width: s, height: s }}
      className="border-2 border-purple-500/30 border-t-purple-400 rounded-full animate-spin" />
  );
}
