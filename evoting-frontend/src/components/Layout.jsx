import { useState, useCallback, useRef, useEffect } from "react";
import { useAuth, getAvatarColor, getGreeting } from "../context/AuthContext.jsx";
import { useNotifications } from "../context/NotificationContext.jsx";
import { useTheme } from "../context/ThemeContext.jsx";
import { Ic } from "./ui.jsx";
import { useKeypair } from "../context/KeypairContext.jsx";
import { ChatWidget } from "./ChatWidget.jsx";

const NAV = [
  { id: "dashboard",  icon: "dashboard", label: "Dashboard" },
  { id: "elections",  icon: "ballot",    label: "Elections" },
  { id: "voters",     icon: "voters",    label: "Voter Registry" },
  { id: "terminals",  icon: "chip",      label: "Terminals" },
  { id: "enrollment", icon: "enroll",    label: "Enrollment" },
  { id: "biometric",  icon: "voters",    label: "Biometric Capture" },
  { id: "tally",      icon: "tally",     label: "Live Tally", live: true },
  { id: "audit",      icon: "audit",     label: "Audit Log" },
  { id: "receipt",    icon: "check",     label: "Receipt Tracker" },
  { id: "adminusers", icon: "lock",      label: "Admin Users", superOnly: true },
  { id: "import",       icon: "enroll",    label: "Import Candidates" },
  { id: "registration", icon: "voters",    label: "Registration",   superOnly: false },
  { id: "approvals",    icon: "shield",    label: "Approvals",      superOnly: false },
  { id: "settings",   icon: "settings",  label: "Settings" },
];

const TITLES = {
  dashboard:    "Dashboard",
  elections:    "Elections",
  voters:       "Voter Registry",
  terminals:    "Hardware Terminals",
  enrollment:   "Enrollment Queue",
  biometric:    "Biometric Capture",
  tally:        "Live Tally",
  audit:        "Audit Log",
  receipt:      "Receipt Tracker",
  adminusers:   "Admin Users",
  import:       "Import Candidates",
  registration: "Voter Registration",
  approvals:    "Pending Approvals",
  settings:     "Settings",
};

const NOTIF_ICONS  = { info:"bell", warning:"warning", success:"check", error:"shield" };
const NOTIF_COLORS = { info:"#A78BFA", warning:"#FCD34D", success:"#34D399", error:"#F87171" };

/* ─── Sidebar ──────────────────────────────────────────────── */
export function Sidebar({ active, setActive, collapsed, setCollapsed }) {
  const { user, logout }    = useAuth();
  const { needsSetup }      = useKeypair() || {};
  const [pendingCount, setPendingCount] = useState(0);

  // Poll pending approvals count every 30s for SUPER_ADMIN
  useEffect(() => {
    if (user?.role !== "SUPER_ADMIN") return;
    const fetchCount = () => {
      import("../api/multisig.js").then(({ getPendingStateChanges }) =>
        getPendingStateChanges()
          .then(d => setPendingCount((d?.pending || []).filter(c => c.canSign).length))
          .catch(() => {})
      );
    };
    fetchCount();
    const t = setInterval(fetchCount, 30000);
    return () => clearInterval(t);
  }, [user?.role]);
  const username    = user?.displayName || user?.username || "Admin";
  const avatarColor = getAvatarColor(username);

  return (
    <aside style={{ width: collapsed ? 68 : 228 }}
      className="flex-shrink-0 bg-surface border-r border-border flex flex-col
                 sticky top-0 h-screen overflow-hidden transition-[width] duration-300 ease-out">

      <button onClick={() => setCollapsed(!collapsed)}
        className="flex items-center gap-3 p-4 border-b border-border w-full
                   hover:bg-purple-500/5 transition-colors duration-150"
        style={{ justifyContent: collapsed ? "center" : "flex-start" }}>
        <div className="w-9 h-9 rounded-xl bg-purple-gradient flex items-center justify-center
                        flex-shrink-0 shadow-purple-sm animate-glow">
          <Ic n="vote" s={17} c="#fff" sw={2.5} />
        </div>
        {!collapsed && (
          <div className="flex flex-col text-left overflow-hidden">
            <span className="text-xs text-sub font-medium">Welcome back,</span>
            <span className="text-sm text-white font-bold capitalize truncate">{username}</span>
          </div>
        )}
      </button>

      <nav className="flex-1 p-2.5 flex flex-col gap-1 overflow-y-auto">
        {!collapsed && <div className="sect-lbl px-2 py-1.5 mt-1 mb-0.5">Navigation</div>}
        {NAV.filter(n => !n.superOnly || user?.role === "SUPER_ADMIN" || n.id === "approvals").map(n => {
          const locked = n.superOnly && user?.role !== "SUPER_ADMIN";
          return (
          <button key={n.id}
            className={`nav-link w-full ${active === n.id ? "active" : ""} ${locked ? "opacity-40 cursor-not-allowed" : ""}`}
            style={{ justifyContent: collapsed ? "center" : "flex-start" }}
            onClick={() => !locked && setActive(n.id)}
            title={collapsed ? n.label : locked ? "SUPER_ADMIN only" : undefined}>
            <Ic n={n.icon} s={18} />
            {!collapsed && <span className="flex-1 text-left">{n.label}</span>}
            {!collapsed && n.live && <span className="live-dot" />}
                {!collapsed && n.id === "approvals" && (
              <div className="flex items-center gap-1">
                {pendingCount > 0 && (
                  <span className="min-w-[18px] h-[18px] rounded-full bg-purple-500
                                   text-white text-[9px] font-extrabold flex items-center
                                   justify-center px-1">
                    {pendingCount > 9 ? "9+" : pendingCount}
                  </span>
                )}
              </div>
            )}
            {!collapsed && n.id === "settings" && needsSetup && (
              <span className="w-2 h-2 rounded-full bg-purple-400 flex-shrink-0"
                    title="Signing key not set up" />
            )}
          </button>
          );
        })}
      </nav>

      <div className="p-2.5 border-t border-border">
        {!collapsed && (
          <div className="bg-elevated rounded-xl p-3 mb-2 flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg flex items-center justify-center
                            text-sm font-extrabold text-white flex-shrink-0 shadow-purple-sm"
                 style={{ backgroundColor: avatarColor }}>
              {username.charAt(0).toUpperCase()}
            </div>
            <div className="overflow-hidden flex-1 min-w-0">
              <div className="text-[13px] font-bold text-ink truncate capitalize">{username}</div>
              <span className="badge badge-purple text-[9px] px-1.5">{user?.role || "ADMIN"}</span>
            </div>
          </div>
        )}
        <button onClick={logout}
          className="nav-link w-full text-muted hover:text-danger hover:bg-red-500/5"
          style={{ justifyContent: collapsed ? "center" : "flex-start" }}>
          <Ic n="logout" s={16} />
          {!collapsed && <span className="text-[13px]">Sign out</span>}
        </button>
      </div>
    </aside>
  );
}

export function PhoneStyleNotificationBar() {
  const { popups, dismissPopup, markRead } = useNotifications();

  if (popups.length === 0) return null;

  return (
    // 1. The Container: Fixed to the top-right, allows SCROLLING if there are too many!
    // pointer-events-none allows you to click the app behind the empty space.
    <div className="fixed top-4 right-4 z-[9999] flex flex-col gap-3 max-h-[90vh] overflow-y-auto p-2 w-80 sm:w-96 pointer-events-none custom-scrollbar">

      {popups.map((n) => (
        // 2. The Banner itself: Clickable, pointer cursor, mimicking a phone alert
        <div
          key={n.id}
          className="bg-card border border-purple-500/30 shadow-2xl rounded-2xl p-4
                     pointer-events-auto cursor-pointer hover:bg-elevated transition-all
                     animate-fade-up" // Make sure you have an animation class!
          onClick={() => {
             markRead(n.id);     // Mark as read in your global state
             dismissPopup(n.id); // Immediately close this specific popup
             // Optional: Add navigation logic here if you want clicking it to take you to a page
          }}
        >
          {/* iOS/Android style header */}
          <div className="flex items-center gap-2 mb-1.5">
             <div className="w-5 h-5 rounded bg-purple-500/20 flex items-center justify-center">
               <Ic n="bell" s={12} c="#A78BFA" />
             </div>
             <span className="text-xs font-bold text-ink">{n.title}</span>
             <span className="text-[10px] text-muted ml-auto">now</span>
          </div>

          {/* Notification Body */}
          <p className="text-xs text-sub leading-relaxed pl-7">{n.body}</p>
        </div>
      ))}
    </div>
  );
}

/* ─── Topbar ───────────────────────────────────────────────── */
export function Topbar({ view, setView }) {
  const { user }                                                    = useAuth();
  const { notifications, markRead, toggleRead, markAllRead, clearAll, unread } = useNotifications();
  const { isDark, toggleTheme }                                     = useTheme();

  const [clock, setClock] = useState(new Date());

  /*
   * SINGLE openPanel state — value: null | "chat" | "notif" | "profile"
   * Clicking any icon sets this. The previously open panel closes
   * automatically in the same render — zero overlap, zero flicker.
   */
  const [openPanel, setOpenPanel] = useState(null);
  const [notifFilter, setNotifFilter] = useState("all");
  const toggle = useCallback(name =>
    setOpenPanel(prev => prev === name ? null : name), []);
  const closeAll = useCallback(() => setOpenPanel(null), []);

  const isChat    = openPanel === "chat";
  const isNotif   = openPanel === "notif";
  const isProfile = openPanel === "profile";

  const username    = user?.displayName || user?.username || "Admin";
  const avatarColor = getAvatarColor(username);
  const greeting    = getGreeting(username);

  useEffect(() => {
    const t = setInterval(() => setClock(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

const CATS = {
    security: ["error","warning"],
    election: [],  // navTo === "elections" or "tally"
    system:   ["info","success"],
  };

  const filteredNotifs = notifFilter === "all"
    ? notifications
    : notifFilter === "election"
      ? notifications.filter(n => ["elections","tally","audit"].includes(n.navTo))
      : notifications.filter(n => CATS[notifFilter]?.includes(n.type));

  return (
    <>
    <PhoneStyleNotificationBar />
      <header className="bg-surface border-b border-border h-[60px] flex items-center
                         justify-between px-6 sticky top-0 z-50 flex-shrink-0">

        {/* Title + clock */}
        <div>
          <h1 className="text-[17px] font-extrabold text-ink tracking-tight leading-none">
            {TITLES[view] || "Dashboard"}
          </h1>
          <div className="mono text-[10px] text-muted mt-0.5">
            {clock.toLocaleString("en-NG", {
              weekday:"short", day:"2-digit", month:"short", year:"numeric",
              hour:"2-digit", minute:"2-digit", second:"2-digit",
            })}
          </div>
        </div>

        <div className="flex items-center gap-2">
          {view === "tally" && (
            <div className="flex items-center gap-2 bg-green-500/10 border border-green-500/20
                            rounded-xl px-3 py-1.5">
              <span className="live-dot" />
              <span className="text-xs font-bold text-success">Live</span>
            </div>
          )}

          {/* Theme toggle — no dropdown, excluded from accordion */}
          <button onClick={toggleTheme}
            className="w-9 h-9 rounded-xl border bg-elevated border-border
                       hover:border-purple-500/30 flex items-center justify-center
                       transition-colors"
            title={isDark ? "Switch to light mode" : "Switch to dark mode"}>
            <Ic n={isDark ? "sun" : "moon"} s={16} c="#8B7FA8" />
          </button>

          {/* ── Chat ── */}
          <div className="relative">
            <button
              onClick={() => toggle("chat")}
              className={`relative w-9 h-9 rounded-xl border flex items-center justify-center
                          transition-all duration-150
                          ${isChat
                            ? "bg-purple-500/20 border-purple-500/40 scale-95"
                            : "bg-elevated border-border hover:border-purple-500/30 hover:scale-95"}`}>
              <Ic n="chat" s={16} c={isChat ? "#A78BFA" : "#8B7FA8"} />
              <div className="absolute top-1.5 right-1.5 w-1.5 h-1.5 rounded-full
                              bg-success border border-surface" />
            </button>
          </div>

          {/* ── Notifications ── */}
          <div className="relative">
            <button
              onClick={() => toggle("notif")}
              className={`relative w-9 h-9 rounded-xl border flex items-center justify-center
                          transition-all duration-150
                          ${isNotif
                            ? "bg-purple-500/20 border-purple-500/40 scale-95"
                            : "bg-elevated border-border hover:border-purple-500/30 hover:scale-95"}`}>
              <Ic n="bell" s={16} c={isNotif ? "#A78BFA" : "#8B7FA8"} />
              {unread > 0 && (
                <div className="absolute -top-1 -right-1 min-w-[16px] h-4 rounded-full
                                bg-danger border-2 border-surface flex items-center justify-center
                                text-[9px] font-extrabold text-white px-1">
                  {unread > 9 ? "9+" : unread}
                </div>
              )}
            </button>

            {isNotif && (
              <div className="absolute top-[calc(100%+8px)] right-0 w-80
                              bg-surface border border-border-hi rounded-2xl shadow-card
                              z-[120] overflow-hidden animate-fade-in origin-top-right
                              flex flex-col" style={{ maxHeight: "480px" }}>

                <div className="p-4 border-b border-border bg-elevated flex-shrink-0">
                  <div className="flex justify-between items-center mb-3">
                    <div>
                      <span className="font-bold text-sm text-ink">Notifications</span>
                      {unread > 0 && (
                        <span className="ml-2 badge badge-purple text-[9px]">{unread} new</span>
                      )}
                    </div>
                    <div className="flex items-center gap-3">
                      {unread > 0 && (
                        <button onClick={markAllRead}
                          className="text-[10px] text-purple-400 font-semibold
                                     hover:text-purple-300 transition-colors">
                          Mark all read
                        </button>
                      )}
                      <button onClick={clearAll}
                        className="text-[10px] text-muted font-semibold
                                   hover:text-sub transition-colors">
                        Clear
                      </button>
                    </div>
                  </div>
                  {/* Category filter tabs */}
                  <div className="flex gap-1">
                    {["all","security","election","system"].map(cat => (
                      <button key={cat}
                        onClick={() => setNotifFilter(cat)}
                        className={`text-[10px] font-semibold px-2.5 py-1 rounded-lg capitalize
                                    transition-colors
                                    ${notifFilter === cat
                                      ? "bg-purple-500/20 text-purple-300 border border-purple-500/30"
                                      : "text-muted hover:text-sub hover:bg-white/5"}`}>
                        {cat}
                      </button>
                    ))}
                  </div>
                </div>

              {/* Scrollable list */}
             <div className="overflow-y-auto overscroll-contain divide-y divide-border/40 flex-1 min-h-0">

             {filteredNotifs.map(n => (
               <div key={n.id}
                 className={`flex gap-3 px-4 py-3 hover:bg-white/5
                             transition-colors group border-b border-border/30
                             ${n.read ? "opacity-60" : ""}`}>

                 {/* Icon */}
                 <div className="mt-0.5 flex-shrink-0 pt-0.5">
                   <Ic n={NOTIF_ICONS[n.type] || "bell"} s={14}
                       c={NOTIF_COLORS[n.type] || "#A78BFA"} />
                 </div>

                 {/* Body — clickable for navigation AND marking read */}
                 <button
                   className="flex-1 min-w-0 text-left cursor-pointer" // <-- Added cursor-pointer here
                   onClick={() => {
                     markRead(n.id);
                     if (n.navTo) { setView(n.navTo); closeAll(); }
                   }}>
                   <div className="flex items-center gap-2">
                     <p className="text-xs font-bold text-ink truncate">{n.title}</p>
                     {!n.read && (
                       <div className="w-1.5 h-1.5 rounded-full bg-purple-400 flex-shrink-0" />
                     )}
                   </div>
                   <p className="text-[11px] text-sub mt-0.5 leading-relaxed select-text">{n.body}</p>
                   <p className="text-[10px] text-muted mt-1 mono">{n.time}</p>
                 </button>

                 {/* Read/unread toggle — visible on hover */}
                 <button
                   title={n.read ? "Mark as unread" : "Mark as read"}
                   onClick={e => { e.stopPropagation(); toggleRead(n.id); }}
                   className="opacity-0 group-hover:opacity-100 transition-opacity
                              flex-shrink-0 mt-0.5 w-5 h-5 rounded-md cursor-pointer
                              hover:bg-white/10 flex items-center justify-center">
                   <div className={`w-2 h-2 rounded-full border
                     ${n.read
                       ? "border-muted bg-transparent"
                       : "border-purple-400 bg-purple-400"}`} />
                 </button>
               </div>
               ))}
              </div>

                <div className="p-3 border-t border-border bg-elevated flex-shrink-0">
                  <button
                    onClick={() => { setView("audit"); closeAll(); }}
                    className="w-full text-[11px] font-semibold text-purple-400
                               hover:text-purple-300 text-center transition-colors">
                    View full audit log →
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* ── Profile ── */}
          <div className="relative">
            <button
              onClick={() => toggle("profile")}
              style={{ backgroundColor: avatarColor }}
              className={`w-9 h-9 rounded-xl flex items-center justify-center
                          text-[13px] font-extrabold text-white
                          shadow-purple-sm capitalize
                          transition-all duration-150
                          ${isProfile
                            ? "scale-90 ring-2 ring-purple-500/50"
                            : "hover:scale-105"}`}>
              {username.charAt(0).toUpperCase()}
            </button>

            {isProfile && (
              <div className="absolute top-[calc(100%+8px)] right-0 w-64
                              bg-surface border border-border-hi rounded-2xl shadow-card
                              z-[120] overflow-hidden animate-fade-in origin-top-right">

                <div className="p-4 border-b border-border bg-elevated flex items-center gap-3">
                  <div className="w-11 h-11 rounded-xl flex items-center justify-center
                                  text-base font-extrabold text-white flex-shrink-0 shadow-purple-sm"
                       style={{ backgroundColor: avatarColor }}>
                    {username.charAt(0).toUpperCase()}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-bold text-ink truncate select-text">{greeting}</div>
                    <div className="flex items-center gap-1.5 mt-0.5">
                      <span className="badge badge-purple text-[9px]">{user?.role || "ADMIN"}</span>
                    </div>
                    {user?.email && (
                      <div className="text-[10px] text-muted mt-1 truncate select-text">{user.email}</div>
                    )}
                    {user?.lastLogin && (
                      <div className="text-[9px] text-muted mt-0.5 mono">
                        Last login: {new Date(user.lastLogin).toLocaleString("en-NG",{
                          day:"2-digit",month:"short",hour:"2-digit",minute:"2-digit"
                        })}
                      </div>
                    )}
                  </div>
                </div>

                <div className="p-2 flex flex-col gap-0.5">
                  <button
                    onClick={() => { setView("settings"); closeAll(); }}
                    className="flex items-center gap-2.5 px-3 py-2.5 rounded-lg text-xs
                               font-semibold text-sub hover:bg-white/5 hover:text-ink
                               transition-colors w-full text-left cursor-pointer">
                    <Ic n="settings" s={14} /> Account Settings
                  </button>
                  <button
                    onClick={() => { toggleTheme(); closeAll(); }}
                    className="flex items-center gap-2.5 px-3 py-2.5 rounded-lg text-xs
                               font-semibold text-sub hover:bg-white/5 hover:text-ink
                               transition-colors w-full text-left">
                    <Ic n={isDark ? "sun" : "moon"} s={14} />
                    {isDark ? "Light Mode" : "Dark Mode"}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Single backdrop — closes whatever panel is open */}
      {openPanel && openPanel !== "chat" && (
        <div className="fixed inset-0 z-40" onClick={closeAll} />
      )}

      {/* Chat widget — separate panel below topbar */}
      <ChatWidget
        isOpen={isChat}
        onClose={closeAll}
        setView={setView}
      />
    </>
  );
}
