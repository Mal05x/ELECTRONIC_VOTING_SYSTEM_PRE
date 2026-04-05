import { createContext, useContext, useState, useCallback, useRef } from "react";
import { useAuth } from "./AuthContext.jsx";

const MessageContext = createContext(null);

/*
 * Admin-to-admin messaging context.
 *
 * Architecture:
 *   In production this would connect to a WebSocket endpoint, e.g.
 *   STOMP topic /topic/admin-chat or a dedicated /api/admin/messages REST API.
 *
 *   For now the context runs in-memory with a seeded conversation history so the
 *   UI is fully functional during demo/dev. Swap the `send` function body to POST
 *   to /api/admin/messages and subscribe to a STOMP topic to go live.
 *
 * Concepts:
 *   - Thread  : a conversation between a set of admins (identified by threadId)
 *   - Message : { id, threadId, from, body, time, read }
 *   - Admin   : known admins seeded below — in prod fetch from GET /api/admin/users
 */

const KNOWN_ADMINS = [
  { username:"superadmin",  role:"SUPER_ADMIN", online:true  },
  { username:"MAL_05X",     role:"SUPER_ADMIN", online:true  },
  { username:"observer01",  role:"OBSERVER",    online:false },
  { username:"stateadmin",  role:"ADMIN",       online:true  },
  { username:"returnoff02", role:"ADMIN",       online:false },
];

// Seed messages so the inbox isn't empty on first load
const _now  = () => new Date().toLocaleTimeString("en-NG", { hour:"2-digit", minute:"2-digit" });
const _seed = [
  { id:1,  threadId:"superadmin", from:"superadmin",  body:"Presidential election is live. All terminals check in?",    time:"09:02", read:true  },
  { id:2,  threadId:"superadmin", from:"MAL_05X",     body:"Lagos cluster — 18,000 terminals responding. All green.",   time:"09:04", read:true  },
  { id:3,  threadId:"superadmin", from:"superadmin",  body:"Good. Notify me if any state goes below 90% terminal uptime.",time:"09:06", read:true  },
  { id:4,  threadId:"superadmin", from:"MAL_05X",     body:"Kano just hit 98.4%. Transmission rate normal.",            time:"09:15", read:true  },
  { id:5,  threadId:"superadmin", from:"superadmin",  body:"Liveness failure rate?",                                    time:"09:20", read:true  },
  { id:6,  threadId:"superadmin", from:"MAL_05X",     body:"0.3% — within expected range. No systemic issue.",          time:"09:21", read:false },
  { id:7,  threadId:"stateadmin", from:"stateadmin",  body:"Rivers State: 2 terminals offline at PU 44 and PU 87.",     time:"08:50", read:true  },
  { id:8,  threadId:"stateadmin", from:"MAL_05X",     body:"Noted. Dispatching field agent.",                           time:"08:52", read:true  },
  { id:9,  threadId:"stateadmin", from:"stateadmin",  body:"PU 44 back online. PU 87 still unreachable.",               time:"09:30", read:false },
];

let _nextId = 200;

export function MessageProvider({ children }) {
  const { user } = useAuth();
  const [messages,       setMessages]       = useState(_seed);
  const [activeThread,   setActiveThread]   = useState("superadmin");
  const [admins]                            = useState(KNOWN_ADMINS);

  const send = useCallback((threadId, body) => {
    if (!body.trim()) return;
    const msg = {
      id: ++_nextId,
      threadId,
      from: user?.username || "me",
      body: body.trim(),
      time: _now(),
      read: true,
    };
    setMessages(p => [...p, msg]);

    /*
     * In production, replace the above with:
     *
     *   client.post("/admin/messages", { toUsername: threadId, body })
     *     .then(res => setMessages(p => [...p, res.data]));
     *
     * And subscribe to:
     *   stompClient.subscribe(`/topic/admin-chat/${user.username}`, frame => {
     *     const incoming = JSON.parse(frame.body);
     *     setMessages(p => [...p, { ...incoming, read: false }]);
     *   });
     */

    // To enable real-time replies, subscribe to STOMP topic:
    // stompClient.subscribe(`/topic/admin-chat/${user.username}`, (frame) => {
    //   const msg = JSON.parse(frame.body);
    //   setMessages(p => [...p, { ...msg, read: false }]);
    // });
  }, [user]);

  const markThreadRead = useCallback((threadId) => {
    setMessages(p => p.map(m =>
      m.threadId === threadId ? { ...m, read: true } : m
    ));
  }, []);

  // Messages for a given thread
  const threadMessages = useCallback((threadId) =>
    messages.filter(m => m.threadId === threadId),
  [messages]);

  // Last message preview per thread
  const lastMessage = useCallback((threadId) => {
    const msgs = messages.filter(m => m.threadId === threadId);
    return msgs[msgs.length - 1] || null;
  }, [messages]);

  // Count unread messages not sent by me
  const unreadCount = messages.filter(m =>
    !m.read && m.from !== (user?.username || "me")
  ).length;

  // Unread per thread
  const unreadFor = useCallback((threadId) =>
    messages.filter(m => m.threadId === threadId && !m.read && m.from !== (user?.username || "me")).length,
  [messages, user]);

  return (
    <MessageContext.Provider value={{
      admins, messages, activeThread, setActiveThread,
      send, markThreadRead, threadMessages, lastMessage,
      unreadCount, unreadFor,
    }}>
      {children}
    </MessageContext.Provider>
  );
}

export const useMessages = () => useContext(MessageContext);
