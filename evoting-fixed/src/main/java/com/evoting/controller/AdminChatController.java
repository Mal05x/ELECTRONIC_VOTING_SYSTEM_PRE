package com.evoting.controller;

import com.evoting.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket chat controller — admin ↔ admin messaging and admin → terminal alerts.
 *
 * Destinations (must match ChatWidget.jsx exactly):
 *   Client sends   → /app/chat.send          (admin-to-admin broadcast)
 *   Client sends   → /app/chat.terminal       (admin push alert to a specific terminal)
 *   Client listens → /topic/chat              (all chat messages)
 *   Client listens → /topic/terminal/{id}     (terminal-specific alerts)
 *
 * Every message is written to the audit trail via AuditLogService.
 */
@Controller
@Slf4j
public class AdminChatController {

    @Autowired private SimpMessagingTemplate ws;
    @Autowired private AuditLogService       auditLog;

    /**
     * Admin-to-admin broadcast.
     * Client sends:  { "text": "...", "sender": "username", "id": 123, "time": "14:30" }
     * Server echoes enriched message to all /topic/chat subscribers.
     */
    @MessageMapping("/chat.send")
    public void handleChatMessage(@Payload Map<String, Object> payload,
                                  SimpMessageHeaderAccessor headerAccessor) {
        String sender = (String) payload.getOrDefault("sender", "unknown");
        String text   = (String) payload.getOrDefault("text",   "");

        if (text == null || text.isBlank()) return;

        // Enrich with server-side timestamp and id if not already set
        Map<String, Object> message = new HashMap<>(payload);
        message.putIfAbsent("id",   System.currentTimeMillis());
        message.putIfAbsent("time", OffsetDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm")));
        message.put("type", "admin");

        log.info("[CHAT] {} → ALL: {}", sender, text);
        auditLog.log("ADMIN_CHAT_MESSAGE", sender,
                "Broadcast: " + text.substring(0, Math.min(text.length(), 80)));

        // Broadcast to all connected admin dashboards
        ws.convertAndSend("/topic/chat", message);
    }

    /**
     * Admin pushes a direct alert to a specific terminal.
     * Client sends:  { "terminalId": "TERM-KD-001", "alert": "...", "sender": "username" }
     * Server sends to /topic/terminal/{terminalId} AND echoes to /topic/chat.
     */
    @MessageMapping("/chat.terminal")
    public void handleTerminalAlert(@Payload Map<String, Object> payload) {
        String terminalId = (String) payload.getOrDefault("terminalId", "");
        String alert      = (String) payload.getOrDefault("alert",      "");
        String sender     = (String) payload.getOrDefault("sender",     "admin");

        if (terminalId.isBlank() || alert.isBlank()) return;

        Map<String, Object> message = new HashMap<>();
        message.put("id",         System.currentTimeMillis());
        message.put("terminalId", terminalId);
        message.put("text",       alert);
        message.put("alert",      alert);
        message.put("sender",     sender);
        message.put("time",       OffsetDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm")));
        message.put("type",       "terminal_alert");

        log.info("[CHAT] Admin {} → terminal {}: {}", sender, terminalId, alert);
        auditLog.log("TERMINAL_ALERT_SENT", sender,
                "Terminal=" + terminalId + " Alert=" +
                        alert.substring(0, Math.min(alert.length(), 80)));

        // Echo to all admins watching the chat (so sender sees it too)
        ws.convertAndSend("/topic/chat", message);
        // Also push directly to the terminal's own topic
        ws.convertAndSend("/topic/terminal/" + terminalId, message);
    }
}
