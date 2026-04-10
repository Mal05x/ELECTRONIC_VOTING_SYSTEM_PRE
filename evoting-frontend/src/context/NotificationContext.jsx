/**
 * NotificationContext — real event notifications from the backend.
 *
 * Sources:
 *   1. On mount: loads last 20 audit log entries as history
 *   2. Live: subscribes to /topic/notifications via WebSocket (AuditLogService broadcasts)
 *   3. No demo data, no fake timers
 *
 * Each notification has: { id, type, title, body, time, read, navTo, eventType }
 */
import { createContext, useContext, useState, useCallback, useEffect, useRef } from "react";
import SockJS from "sockjs-client";
import Stomp from "stompjs";
import { getAuditLog } from "../api/audit.js";

const NotificationContext = createContext({ refreshNotifications: () => {} });

const WS_BASE = import.meta.env.VITE_WS_URL || "";
let _nextId = 1;

/* Map audit eventType → notification severity */
function auditTypeToNotif(eventType = "") {
  const map = {
    success: ["AUTH_SUCCESS","VOTE_CAST","ENROLLMENT_COMPLETED","ELECTION_CREATED",
               "ELECTION_ACTIVATED","ELECTION_CLOSED","ADMIN_LOGIN","LIVENESS_PASS",
               "POLLING_UNIT_CREATED","CANDIDATE_ADDED","ADMIN_USER_CREATED",
               "MERKLE_ROOT_PUBLISHED","PROFILE_UPDATED","SETTINGS_UPDATED",
               "PASSWORD_CHANGED","PARTY_CREATED"],
    warning: ["TERMINAL_TAMPER_ALERT","HIGH_VOTE_RATE","VOTE_INTERVAL_ANOMALY",
               "ANOMALY_DETECTED","AUTH_FAIL_LIVENESS","LIVENESS_FALLBACK",
               "ADMIN_LOGOUT","TAMPER_ALERT_RESOLVED"],
    error:   ["AUTH_FAIL_CARD_LOCKED","AUTH_FAIL_ALREADY_VOTED","AUTH_FAIL_SIGNATURE",
               "VOTE_FAIL_BURN_PROOF","ADMIN_DEACTIVATED","AUTH_FAIL_NOT_REGISTERED"],
  };
  for (const [type, events] of Object.entries(map)) {
    if (events.includes(eventType)) return type;
  }
  return "info";
}

/* Map eventType → which view to navigate to when notification is clicked */
function auditTypeToNav(eventType = "") {
  if (["VOTE_CAST","ELECTION_CREATED","ELECTION_ACTIVATED","ELECTION_CLOSED",
       "CANDIDATE_ADDED","PARTY_CREATED"].includes(eventType)) return "elections";
  if (["AUTH_SUCCESS","AUTH_FAIL_LIVENESS","AUTH_FAIL_SIGNATURE",
       "AUTH_FAIL_CARD_LOCKED","AUTH_FAIL_ALREADY_VOTED",
       "VOTE_FAIL_BURN_PROOF","LIVENESS_PASS","LIVENESS_FAIL"].includes(eventType))
    return "audit";
  if (["TERMINAL_TAMPER_ALERT","HIGH_VOTE_RATE","VOTE_INTERVAL_ANOMALY",
       "ANOMALY_DETECTED","TAMPER_ALERT_RESOLVED"].includes(eventType)) return "terminals";
  if (["ENROLLMENT_COMPLETED","POLLING_UNIT_CREATED"].includes(eventType)) return "enrollment";
  if (["ADMIN_USER_CREATED","ADMIN_DEACTIVATED","PROFILE_UPDATED",
       "SETTINGS_UPDATED","PASSWORD_CHANGED"].includes(eventType)) return "settings";
  if (["MERKLE_ROOT_PUBLISHED"].includes(eventType)) return "tally";
  return null;
}

function humanTitle(eventType = "") {
  const titles = {
    AUTH_SUCCESS:          "Voter Authenticated",
    VOTE_CAST:             "Vote Cast",
    ADMIN_LOGIN:           "Admin Login",
    ADMIN_LOGOUT:          "Admin Logout",
    ELECTION_CREATED:      "Election Created",
    ELECTION_ACTIVATED:    "Election Activated",
    ELECTION_CLOSED:       "Election Closed",
    ENROLLMENT_COMPLETED:  "Enrollment Completed",
    TERMINAL_TAMPER_ALERT: "⚠ Tamper Alert",
    HIGH_VOTE_RATE:        "⚠ High Vote Rate",
    ANOMALY_DETECTED:      "⚠ Anomaly Detected",
    LIVENESS_PASS:         "Liveness Confirmed",
    LIVENESS_FAIL:         "Liveness Failed",
    AUTH_FAIL_LIVENESS:    "Auth Failed — Liveness",
    AUTH_FAIL_SIGNATURE:   "Auth Failed — Signature",
    AUTH_FAIL_CARD_LOCKED: "Card Locked",
    MERKLE_ROOT_PUBLISHED: "Merkle Root Published",
    PROFILE_UPDATED:       "Profile Updated",
    PASSWORD_CHANGED:      "Password Changed",
    SETTINGS_UPDATED:      "Settings Saved",
    ADMIN_USER_CREATED:    "New Admin Created",
    ADMIN_DEACTIVATED:     "Admin Deactivated",
    POLLING_UNIT_CREATED:  "Polling Unit Created",
    CANDIDATE_ADDED:       "Candidate Added",
    PARTY_CREATED:         "Party Created",
    TAMPER_ALERT_RESOLVED: "Tamper Resolved",
  };
  return titles[eventType] || eventType.replace(/_/g, " ");
}

function relativeTime(isoString) {
  if (!isoString) return "just now";
  const diff = Date.now() - new Date(isoString).getTime();
  if (diff < 60_000)         return "just now";
  if (diff < 3_600_000)      return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000)     return `${Math.floor(diff / 3_600_000)} hr ago`;
  return new Date(isoString).toLocaleDateString("en-NG", { day:"2-digit", month:"short" });
}

function auditToNotif(entry) {
  return {
    id:        entry.id || ++_nextId,
    type:      auditTypeToNotif(entry.eventType),
    title:     entry.title || humanTitle(entry.eventType),
    body:      entry.body  || `${entry.actor}: ${entry.eventType}`,
    time:      relativeTime(entry.time || entry.createdAt),
    read:      false,
    navTo:     auditTypeToNav(entry.eventType),
    eventType: entry.eventType,
  };
}

export function NotificationProvider({ children }) {
  const [notifications, setNotifications] = useState([]);
  const stompRef = useRef(null);
  // 1. ADD THIS: A separate state just for the phone-style banners
    const [popups, setPopups] = useState([]);

  const push = useCallback((notif) => {
    setNotifications(p => {
      if (p.find(n => n.id === notif.id)) return p;
      return [notif, ...p].slice(0, 100);
    });

// 2. ADD THIS: Push it to the popup banner list
    setPopups(p => [notif, ...p]);

    // 3. ADD THIS: Auto-dismiss the popup after 6 seconds (just like a phone!)
    setTimeout(() => {
      setPopups(p => p.filter(n => n.id !== notif.id));
    }, 6000);

    // Browser notification for security/error alerts (tamper, anomaly, auth failures)
    if (notif.type === "error" || notif.type === "warning") {
      if ("Notification" in window) {
        const fire = () => new Notification(notif.title || "Security Alert", {
          body: notif.body || "",
          icon: "/favicon.ico",
          tag:  notif.id,
          requireInteraction: notif.type === "error",
        });
        if (Notification.permission === "granted") {
          fire();
        } else if (Notification.permission !== "denied") {
          Notification.requestPermission().then(p => { if (p === "granted") fire(); });
        }
      }
    }
  }, []);

// Provide a way to manually swipe/close a popup
  const dismissPopup = useCallback((id) => {
    setPopups(p => p.filter(n => n.id !== id));
  }, []);

  /* Load recent audit history — runs on mount AND when refreshTick changes.
   * refreshTick is bumped by refreshNotifications(), which AuthContext calls
   * immediately after login so displayName and notifications load at the same time. */
  const [refreshTick, setRefreshTick] = useState(0);
  const refreshNotifications = useCallback(() => {
    setRefreshTick(t => t + 1);
  }, []);

  useEffect(() => {
    const token = localStorage.getItem("evoting_jwt") ||
                  sessionStorage.getItem("evoting_jwt");
    if (!token) return; // not logged in — skip
    getAuditLog({ page: 0, size: 20 }).then(entries => {
      const notifs = entries
        .map(e => auditToNotif({
          id:        e.id,
          eventType: e.eventType,
          actor:     e.actor,
          body:      `${e.actor || "system"}`,
          time:      e.createdAt,
        }))
        .filter(n => n.type !== "info" ||
          ["ELECTION_CREATED","ELECTION_ACTIVATED","MERKLE_ROOT_PUBLISHED",
           "ADMIN_USER_CREATED","VOTE_CAST","AUTH_SUCCESS"].includes(n.eventType));
      setNotifications(notifs);
    }).catch(() => {});
  }, [refreshTick]); // re-runs after login via refreshNotifications()

  /* WebSocket: reconnect when refreshTick changes (i.e. after login) */
  useEffect(() => {
    let cancelled = false;

    const connect = () => {
      if (cancelled) return;
      try {
        const base = WS_BASE.endsWith("/") ? WS_BASE.slice(0, -1) : WS_BASE;
        const sock  = new SockJS(`/ws`);
        const stomp = Stomp.over(sock);
        stomp.debug = () => {};
        const token = localStorage.getItem("evoting_jwt") ||
                      sessionStorage.getItem("evoting_jwt") || "";
        stomp.connect(
          { Authorization: `Bearer ${token}` },
          () => {
            if (cancelled) { stomp.disconnect(); return; }
            stompRef.current = stomp;
            stomp.subscribe("/topic/notifications", (msg) => {
              try {
                const data = JSON.parse(msg.body);
                push(auditToNotif(data));
              } catch (_) {}
            });
          },
          () => {
            if (!cancelled) setTimeout(connect, 8000);
          }
        );
      } catch (_) {
        if (!cancelled) setTimeout(connect, 8000);
      }
    };

    connect();
    return () => {
      cancelled = true;
      try { stompRef.current?.disconnect(); } catch (_) {}
    };
  }, [push, refreshTick]); // re-runs after login to pick up new JWT

  const markRead    = useCallback(id =>
    setNotifications(p => p.map(n => n.id === id ? { ...n, read: true } : n)), []);
  const markUnread  = useCallback(id =>
    setNotifications(p => p.map(n => n.id === id ? { ...n, read: false } : n)), []);
  const toggleRead  = useCallback(id =>
    setNotifications(p => p.map(n => n.id === id ? { ...n, read: !n.read } : n)), []);
  const markAllRead = useCallback(() =>
    setNotifications(p => p.map(n => ({ ...n, read: true }))), []);
  const clearAll    = useCallback(() => setNotifications([]), []);
  const unread      = notifications.filter(n => !n.read).length;

  return (
    <NotificationContext.Provider value={{
      notifications, popups, dismissPopup, push, markRead, markUnread, toggleRead, markAllRead, clearAll, unread,
    }}>
      {children}
    </NotificationContext.Provider>
  );
}

export const useNotifications = () => useContext(NotificationContext);
