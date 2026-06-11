/**
 * OfflineBanner.jsx
 * BUG-15 FIX: Shows a fixed top banner when the backend is unreachable.
 *
 * Usage: mount once in App.jsx (or the root layout component):
 *   import OfflineBanner from "./components/OfflineBanner.jsx";
 *   ...
 *   <OfflineBanner />
 *   <Routes>...</Routes>
 *
 * The banner appears after 2 consecutive network/5xx failures and
 * disappears automatically on the next successful API response.
 * It does NOT appear for 4xx errors (those are application-level, not connectivity).
 */

import { useState, useEffect } from "react";
import { onBackendStatusChange, isBackendOffline } from "../api/client.js";

export default function OfflineBanner() {
  const [offline, setOffline] = useState(() => isBackendOffline());

  useEffect(() => {
    // Subscribe to the global offline state in client.js
    const unsub = onBackendStatusChange(setOffline);
    return unsub;
  }, []);

  if (!offline) return null;

  return (
    <div
      role="alert"
      aria-live="assertive"
      className="fixed top-0 inset-x-0 z-[9999] flex items-center justify-center gap-3
                 bg-red-950 border-b border-red-500/40 px-4 py-2.5 text-sm font-semibold
                 text-red-200 shadow-lg animate-slide-down"
    >
      {/* Pulse dot */}
      <span className="relative flex h-2.5 w-2.5 flex-shrink-0">
        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75" />
        <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-red-500" />
      </span>

      <span>
        Backend unreachable — dashboard data may be stale.
        <span className="ml-2 font-normal text-red-300/80">
          Checking every 10 seconds…
        </span>
      </span>

      {/* Manual retry */}
      <button
        onClick={() => window.location.reload()}
        className="ml-3 text-xs font-bold underline underline-offset-2
                   text-red-300 hover:text-white transition-colors"
      >
        Reload page
      </button>
    </div>
  );
}
