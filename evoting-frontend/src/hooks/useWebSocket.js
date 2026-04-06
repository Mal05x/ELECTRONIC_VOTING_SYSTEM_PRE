import { useEffect, useRef, useCallback, useState } from "react";
import SockJS from "sockjs-client";
import Stomp from "stompjs";

// 1. Grab the Render URL from Vercel's environment variables (same as Axios)
const WS_BASE = import.meta.env.VITE_API_URL || import.meta.env.VITE_WS_URL || "";
const DEMO    = import.meta.env.VITE_DEMO_MODE === "true";

export function useWebSocket(topic, onMessage) {
  const stompRef  = useRef(null);
  const subRefs   = useRef([]);
  const [connected, setConnected] = useState(false);

  const disconnect = useCallback(() => {
    subRefs.current.forEach(sub => sub?.unsubscribe());
    subRefs.current = [];
    if (stompRef.current?.connected) {
      try { stompRef.current.disconnect(); } catch (_) {}
    }
    stompRef.current = null;
    setConnected(false);
  }, []);

  const sendMessage = useCallback((destination, body) => {
    if (stompRef.current?.connected) {
      stompRef.current.send(destination, {}, JSON.stringify(body));
    } else {
      console.warn("[WS] Cannot send, not connected");
    }
  }, []);

  useEffect(() => {
    if (!topic) return;
    let cancelled = false;

    const connect = () => {
      try {
        if (cancelled) return;

        // 2. Format the URL safely
        const cleanUrl = WS_BASE.endsWith('/') ? WS_BASE.slice(0, -1) : WS_BASE;

        // 3. THE FIX: Dynamically route to Render in production, localhost in dev
        const sockPath = cleanUrl ? `${cleanUrl}/ws` : "/ws";
        const sock  = new SockJS(sockPath);
        const stomp = Stomp.over(sock);

        stomp.debug = () => {};

        stomp.connect(
          { Authorization: `Bearer ${localStorage.getItem("evoting_jwt") || ""}` },
          () => {
            if (cancelled) { stomp.disconnect(); return; }
            stompRef.current = stomp;
            setConnected(true);
            console.log("🟢 WebSocket Connected to topic:", topic);

            if (topic === "chat") {
              const sub = stomp.subscribe('/topic/chat', (msg) => {
                try { onMessage?.(JSON.parse(msg.body)); } catch (_) {}
              });
              subRefs.current.push(sub);
            }
            else if (topic === "terminals") {
              const sub = stomp.subscribe('/topic/terminals', (msg) => {
                try { onMessage?.(JSON.parse(msg.body)); } catch (_) {}
              });
              subRefs.current.push(sub);
            }
            else {
              const resSub = stomp.subscribe(`/topic/results/${topic}`, (msg) => {
                try { onMessage?.(JSON.parse(msg.body)); } catch (_) {}
              });
              const mkSub = stomp.subscribe(`/topic/merkle/${topic}`, (msg) => {
                try { onMessage?.({ type: "MERKLE_UPDATE", ...JSON.parse(msg.body) }); } catch (_) {}
              });
              subRefs.current.push(resSub, mkSub);
            }
          },
          (err) => {
            console.warn("🔴 STOMP Connection lost. Retrying in 5s...", err);
            setConnected(false);
            if (!cancelled) setTimeout(connect, 5000);
          }
        );
      } catch (err) {
        console.warn("[WS] Could not connect:", err);
        setConnected(false);
      }
    };

    connect();
    return () => {
      cancelled = true;
      disconnect();
    };
  }, [topic, onMessage, disconnect]);

  return { connected, sendMessage };
}

// Keep useTallyPolling exactly as it was below this line...
export function useTallyPolling(electionId, fetchFn, interval = 5000) {
  const [data,  setData]  = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!electionId) return;
    let active = true;

    const poll = async () => {
      try {
        const result = await fetchFn(electionId);
        if (active) setData(result);
      } catch (err) {
        if (active) setError(err);
      }
    };

    poll();
    const timer = setInterval(poll, interval);
    return () => { active = false; clearInterval(timer); };
  }, [electionId, interval]);

  return { data, error };
}