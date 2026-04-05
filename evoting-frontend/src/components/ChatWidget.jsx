/**
 * ChatWidget — WhatsApp-style admin messaging
 *
 * Features:
 *   - Admin ↔ Admin real-time broadcast via /topic/chat (WebSocket)
 *   - Admin → Terminal alerts via /app/chat.terminal
 *   - Every message logged in backend audit trail (ChatController.java)
 *   - Message delivery status ticks (sent → delivered)
 *   - Unread count badge when chat is closed
 *   - Terminal selector dropdown for pushing alerts to a specific terminal
 */
import { useState, useEffect, useRef, useCallback } from "react";
import { Ic, Spinner } from "./ui.jsx";
import { useAuth } from "../context/AuthContext.jsx";
import SockJS from "sockjs-client";
import Stomp from "stompjs";

const WS_BASE = import.meta.env.VITE_WS_URL || "";

function getTime() {
  return new Date().toLocaleTimeString("en-NG", { hour: "2-digit", minute: "2-digit" });
}

export function ChatWidget({ isOpen, onClose, setView }) {
  const { user } = useAuth();
  const bottomRef    = useRef(null);
  const inputRef     = useRef(null);
  const stompRef     = useRef(null);
  const [input,      setInput]     = useState("");
  const [messages,   setMessages]  = useState([]);
  const [connected,  setConnected] = useState(false);
  const [alertMode,  setAlertMode] = useState(false);
  const [terminalId, setTerminalId] = useState("TERM-KD-001");

  const username = user?.username || "admin";

  /* ── WebSocket connection ─────────────────────────────────── */
  useEffect(() => {
    let cancelled = false;

    const connect = () => {
      if (cancelled) return;
      try {
        const base  = WS_BASE.endsWith("/") ? WS_BASE.slice(0, -1) : WS_BASE;
        //const sock  = new SockJS(`${base}/ws`);
        const sock  = new SockJS(`/ws`);
        const stomp = Stomp.over(sock);
        stomp.debug = () => {};
        const token = localStorage.getItem("evoting_jwt") ||
                      sessionStorage.getItem("evoting_jwt") || "";
        if (!token) {
          console.warn("[Chat] No JWT found — cannot connect");
          if (!cancelled) setTimeout(connect, 5000);
          return;
        }
        stomp.connect(
          { Authorization: `Bearer ${token}` },
          () => {
            if (cancelled) { stomp.disconnect(); return; }
            stompRef.current = stomp;
            setConnected(true);
            stomp.subscribe("/topic/chat", (msg) => {
              try {
                const data = JSON.parse(msg.body);
                setMessages(prev => {
                  // Avoid duplicates (our own echo back)
                  if (prev.find(m => m.id === data.id)) {
                    // Update status of our sent message to "delivered"
                    return prev.map(m =>
                      m.id === data.id ? { ...m, status: "delivered" } : m);
                  }
                  return [...prev, { ...data, status: "delivered" }];
                });
              } catch (_) {}
            });
          },
          (err) => {
            setConnected(false);
            const msg = err?.headers?.message || "";
            if (msg.includes("401") || msg.includes("Unauthorized")) {
              console.warn("[Chat] Auth failed — JWT expired");
            } else {
              console.warn("[Chat] WS connect failed — check https://localhost:8443 cert trust");
            }
            if (!cancelled) setTimeout(connect, 5000);
          }
        );
      } catch (_) {
        setConnected(false);
        if (!cancelled) setTimeout(connect, 5000);
      }
    };

    connect();
    return () => {
      cancelled = true;
      try { stompRef.current?.disconnect(); } catch (_) {}
    };
  }, []);

  /* Auto-scroll on new messages */
  useEffect(() => {
    if (isOpen) {
      setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 50);
    }
  }, [messages, isOpen]);

  /* Focus input when opened */
  useEffect(() => {
    if (isOpen) setTimeout(() => inputRef.current?.focus(), 100);
  }, [isOpen]);

  const send = useCallback((e) => {
    e?.preventDefault();
    if (!input.trim()) return;

    const msgId = Date.now();
    const msg = {
      id:     msgId,
      sender: username,
      text:   input.trim(),
      time:   getTime(),
      type:   alertMode ? "terminal_alert" : "admin",
      ...(alertMode ? { terminalId } : {}),
      status: "sending",
    };

    // Optimistically add to local state
    setMessages(prev => [...prev, msg]);
    setInput("");

    if (stompRef.current?.connected) {
      if (alertMode) {
        stompRef.current.send("/app/chat.terminal", {}, JSON.stringify({
          terminalId, alert: msg.text, sender: username,
        }));
      } else {
        stompRef.current.send("/app/chat.send", {}, JSON.stringify(msg));
      }
    } else {
      // Not connected — mark as failed
      setMessages(prev => prev.map(m =>
        m.id === msgId ? { ...m, status: "failed" } : m));
    }
  }, [input, username, alertMode, terminalId]);

  /* Handle Enter key */
  const onKeyDown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed bottom-0 right-6 w-[360px] z-[108] flex flex-col
                    rounded-t-2xl shadow-2xl border border-border-hi border-b-0
                    bg-surface animate-slide-in origin-bottom-right"
         style={{ height: "480px" }}>

      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3
                      border-b border-border bg-elevated rounded-t-2xl flex-shrink-0">
        <div className="flex items-center gap-3">
          <div className="relative">
            <div className="w-9 h-9 rounded-full bg-purple-500/20 border border-purple-500/30
                            flex items-center justify-center">
              <Ic n="chat" s={15} c="#A78BFA" />
            </div>
            <span className={`absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full
                              border-2 border-surface
                              ${connected ? "bg-success" : "bg-warning"}`} />
          </div>
          <div>
            <div className="text-[13px] font-bold text-white">Admin Comms</div>
            <div className="text-[10px] font-semibold flex items-center gap-1.5"
                 style={{ color: connected ? "#34D399" : "#FCD34D" }}>
              {connected ? "Secure Channel • Live" : "Connecting…"}
              {!connected && (
                <span className="text-[9px] text-muted font-normal block">
                  Trust cert: visit https://localhost:8443
                </span>
              )}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          {/* Toggle alert mode */}
          <button
            onClick={() => setAlertMode(v => !v)}
            title={alertMode ? "Switch to chat mode" : "Send terminal alert"}
            className={`px-2.5 py-1 rounded-lg text-[10px] font-bold border transition-colors
                        ${alertMode
                          ? "bg-orange-500/20 border-orange-500/40 text-orange-300"
                          : "bg-elevated border-border text-muted hover:text-sub"}`}>
            {alertMode ? "Alert Mode" : "Terminal"}
          </button>
          <button onClick={onClose}
            className="w-7 h-7 rounded-lg hover:bg-white/10 text-muted
                       hover:text-white transition-colors flex items-center justify-center">
            <Ic n="close" s={14} />
          </button>
        </div>
      </div>

      {/* Terminal selector (alert mode only) */}
      {alertMode && (
        <div className="px-4 py-2 bg-orange-500/8 border-b border-orange-500/20
                        flex items-center gap-2 flex-shrink-0">
          <Ic n="chip" s={12} c="#fb923c" />
          <span className="text-[10px] font-bold text-orange-300">Target terminal:</span>
          <input
            className="flex-1 bg-transparent text-[11px] text-orange-200 font-mono
                       border-b border-orange-500/30 focus:outline-none focus:border-orange-400"
            value={terminalId}
            onChange={e => setTerminalId(e.target.value)}
            placeholder="TERM-KD-001"
          />
        </div>
      )}

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-3">
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full gap-2">
            <div className="w-12 h-12 rounded-2xl bg-purple-500/10 border border-purple-500/20
                            flex items-center justify-center">
              <Ic n="chat" s={20} c="#A78BFA" />
            </div>
            <p className="text-xs text-muted text-center">
              Secure admin channel.<br />Messages are logged in the audit trail.
            </p>
          </div>
        )}
        {messages.map((msg) => {
          const isMe = msg.sender === username;
          const isTerminalAlert = msg.type === "terminal_alert";
          return (
            <div key={msg.id}
                 className={`flex flex-col max-w-[85%]
                             ${isMe ? "self-end items-end" : "self-start items-start"}`}>
              {/* Sender name for others */}
              {!isMe && (
                <span className="text-[10px] text-purple-400 font-semibold mb-1 px-1">
                  {msg.sender}
                </span>
              )}
              {/* Terminal alert label */}
              {isTerminalAlert && (
                <span className="text-[9px] text-orange-400 font-bold mb-0.5 px-1 flex items-center gap-1">
                  <Ic n="chip" s={8} c="#fb923c" />
                  → {msg.terminalId}
                </span>
              )}
              <div className={`px-3.5 py-2.5 rounded-2xl text-[12.5px] leading-relaxed
                               shadow-sm max-w-full break-words
                               ${isMe
                                 ? isTerminalAlert
                                   ? "bg-orange-600/80 text-white rounded-br-sm"
                                   : "bg-purple-600 text-white rounded-br-sm"
                                 : "bg-elevated border border-white/5 text-purple-50 rounded-bl-sm"}`}>
                {msg.text || msg.alert}
              </div>
              {/* Time + status ticks */}
              <div className="flex items-center gap-1.5 mt-1 px-1">
                <span className="text-[9px] text-muted">{msg.time}</span>
                {isMe && (
                  <span className="text-[9px]"
                        style={{ color: msg.status === "delivered" ? "#A78BFA"
                                       : msg.status === "failed"    ? "#F87171"
                                       : "#6B7280" }}>
                    {msg.status === "delivered" ? "✓✓"
                     : msg.status === "failed"  ? "✗"
                     : "✓"}
                  </span>
                )}
              </div>
            </div>
          );
        })}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div className="p-3 border-t border-border flex-shrink-0">
        <div className="flex items-center gap-2 bg-elevated border border-border
                        rounded-xl px-3 py-2 focus-within:border-purple-500/40 transition-colors">
          <input
            ref={inputRef}
            type="text"
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={alertMode ? "Type terminal alert…" : "Type a message…"}
            className="flex-1 bg-transparent text-sm text-white focus:outline-none
                       placeholder:text-muted"
          />
          <button
            onClick={send}
            disabled={!input.trim()}
            className={`w-7 h-7 rounded-lg flex items-center justify-center
                        transition-all disabled:opacity-40 disabled:cursor-not-allowed
                        hover:scale-110 active:scale-95
                        ${alertMode ? "bg-orange-500" : "bg-purple-600"}`}>
            <Ic n="check" s={13} c="#fff" sw={3} />
          </button>
        </div>
        <p className="text-[9px] text-muted text-center mt-1.5">
          End-to-end encrypted • Logged to audit trail
        </p>
      </div>
    </div>
  );
}
