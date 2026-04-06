package com.evoting.service;
import com.evoting.model.AuditLog;
import com.evoting.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service @Slf4j
public class AuditLogService {

    private static final String GENESIS =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final int VERIFY_CHUNK_SIZE = 1000;

    @Autowired private AuditLogRepository    repo;
    @Autowired private SimpMessagingTemplate  ws;

    /**
     * Maps audit event types to notification severity.
     * Broadcast to /topic/notifications so the frontend NotificationContext
     * can display real events without polling.
     */
    private static String notifType(String eventType) {
        if (eventType == null) return "info";
        return switch (eventType) {
            case "AUTH_SUCCESS", "ENROLLMENT_COMPLETED", "ADMIN_LOGIN",
                 "VOTE_CAST", "LIVENESS_PASS", "POLLING_UNIT_CREATED",
                 "CANDIDATE_ADDED", "ELECTION_CREATED", "ADMIN_USER_CREATED",
                 "MERKLE_ROOT_PUBLISHED", "PROFILE_UPDATED", "SETTINGS_UPDATED",
                 "PASSWORD_CHANGED"                          -> "success";
            case "TERMINAL_TAMPER_ALERT", "HIGH_VOTE_RATE",
                 "VOTE_INTERVAL_ANOMALY", "ANOMALY_DETECTED",
                 "AUTH_FAIL_LIVENESS", "AUTH_FAIL_SIGNATURE",
                 "LIVENESS_FAIL", "LIVENESS_FALLBACK"       -> "warning";
            case "AUTH_FAIL_CARD_LOCKED", "AUTH_FAIL_ALREADY_VOTED",
                 "VOTE_FAIL_BURN_PROOF", "ADMIN_DEACTIVATED" -> "error";
            default                                          -> "info";
        };
    }

    /** Human-readable title from event type */
    private static String notifTitle(String eventType) {
        if (eventType == null) return "System Event";
        return switch (eventType) {
            case "AUTH_SUCCESS"          -> "Voter Authenticated";
            case "VOTE_CAST"             -> "Vote Cast";
            case "ADMIN_LOGIN"           -> "Admin Login";
            case "ADMIN_LOGOUT"          -> "Admin Logout";
            case "ELECTION_CREATED"      -> "Election Created";
            case "ELECTION_ACTIVATED"    -> "Election Activated";
            case "ELECTION_CLOSED"       -> "Election Closed";
            case "ENROLLMENT_COMPLETED"  -> "Enrollment Completed";
            case "TERMINAL_TAMPER_ALERT" -> "Tamper Alert";
            case "HIGH_VOTE_RATE"        -> "High Vote Rate";
            case "ANOMALY_DETECTED"      -> "Anomaly Detected";
            case "LIVENESS_PASS"         -> "Liveness Confirmed";
            case "LIVENESS_FAIL"         -> "Liveness Failed";
            case "AUTH_FAIL_LIVENESS"    -> "Auth Failed — Liveness";
            case "AUTH_FAIL_SIGNATURE"   -> "Auth Failed — Signature";
            case "AUTH_FAIL_CARD_LOCKED" -> "Card Locked";
            case "MERKLE_ROOT_PUBLISHED" -> "Merkle Root Published";
            case "PROFILE_UPDATED"       -> "Profile Updated";
            case "PASSWORD_CHANGED"      -> "Password Changed";
            case "SETTINGS_UPDATED"      -> "Settings Updated";
            case "ADMIN_USER_CREATED"    -> "New Admin Account";
            case "ADMIN_DEACTIVATED"     -> "Admin Deactivated";
            case "POLLING_UNIT_CREATED"  -> "Polling Unit Created";
            case "CANDIDATE_ADDED"       -> "Candidate Added";
            default                      -> eventType.replace("_", " ");
        };
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation   = Isolation.SERIALIZABLE
    )
    public void log(String eventType, String actor, String eventData) {
        String prev = findLatestForUpdate();
        long   seq  = repo.findMaxSequenceNumber() + 1;
        AuditLog entry = repo.save(new AuditLog(seq, eventType, actor, eventData, prev));
        log.info("[AUDIT] seq={} type={} actor={}", seq, eventType, actor);

        // Broadcast real-time notification to all subscribed admin dashboards
        try {
            ws.convertAndSend("/topic/notifications", Map.of(
                    "id",        entry.getId(),
                    "type",      notifType(eventType),
                    "title",     notifTitle(eventType),
                    "body",      actor + ": " + eventData,
                    "eventType", eventType,
                    "actor",     actor,
                    "seq",       seq,
                    "time",      entry.getCreatedAt().toString()
            ));
        } catch (Exception e) {
            // WS broadcast failure must never break the audit log write
            log.warn("[AUDIT] WS broadcast failed for seq={}: {}", seq, e.getMessage());
        }
    }

    private String findLatestForUpdate() {
        return repo.findLatestForUpdate()
                .map(AuditLog::getEntryHash)
                .orElse(GENESIS);
    }

    @Scheduled(fixedDelay = 300_000)
    public boolean verifyChain() {
        String expected = GENESIS;
        int    page     = 0;
        long   verified = 0;
        Page<AuditLog> chunk;
        do {
            chunk = repo.findAll(PageRequest.of(
                    page++, VERIFY_CHUNK_SIZE,
                    Sort.by("sequenceNumber").ascending()));
            for (AuditLog entry : chunk.getContent()) {
                if (!entry.getPreviousHash().equals(expected)) {
                    log.error("AUDIT CHAIN BROKEN at seq={}", entry.getSequenceNumber());
                    return false;
                }
                String recalc = AuditLog.sha256(
                        entry.getSequenceNumber() + entry.getPayloadHash() + entry.getPreviousHash());
                if (!recalc.equals(entry.getEntryHash())) {
                    log.error("AUDIT ENTRY TAMPERED at seq={}", entry.getSequenceNumber());
                    return false;
                }
                expected = entry.getEntryHash();
                verified++;
            }
        } while (chunk.hasNext());
        log.info("Audit chain OK — {} entries verified.", verified);
        return true;
    }
}
