package com.evoting.controller;
import com.evoting.dto.*;
import com.evoting.model.CardStatusLog;
import com.evoting.service.CardManagementService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Smart Card lock/unlock management.
 *
 *   POST /api/admin/cards/unlock         — single card manual unlock
 *   POST /api/admin/cards/lock           — single card manual lock (lost/stolen)
 *   POST /api/admin/cards/unlock-all/{electionId} — bulk unlock (SUPER_ADMIN only)
 *   GET  /api/admin/cards/history/{cardIdHash}    — card's lock/unlock history
 *   GET  /api/admin/cards/election/{electionId}   — all card events for an election
 *
 * Note: bulk unlock also fires automatically when an election is CLOSED
 *       (wired in AdminController.closeElection).
 */
@RestController
@RequestMapping("/api/admin/cards")
public class CardManagementController {

    @Autowired private CardManagementService cardService;

    /** Manual single-card unlock */
    @PostMapping("/unlock")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> unlock(
            @RequestBody @Valid CardUnlockDTO dto, Authentication auth) {
        cardService.unlockCard(dto.getCardIdHash(), dto.getElectionId(), auth.getName());
        return ResponseEntity.ok(Map.of("message", "Card unlocked successfully",
            "cardIdHash", dto.getCardIdHash()));
    }

    /** Manual single-card lock (lost/stolen card) */
    @PostMapping("/lock")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> lock(
            @RequestBody @Valid CardUnlockDTO dto, Authentication auth) {
        cardService.lockCard(dto.getCardIdHash(), dto.getElectionId(), auth.getName());
        return ResponseEntity.ok(Map.of("message", "Card locked successfully",
            "cardIdHash", dto.getCardIdHash()));
    }

    /**
     * Bulk unlock all cards for an election.
     * This is also called automatically by AdminController.closeElection(),
     * but can be called manually if needed.
     */
    @PostMapping("/unlock-all/{electionId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> bulkUnlock(
            @PathVariable UUID electionId, Authentication auth) {
        int count = cardService.bulkUnlockForElection(electionId, auth.getName());
        return ResponseEntity.ok(Map.of(
            "electionId",    electionId,
            "cardsUnlocked", count,
            "message",       count + " cards unlocked for election " + electionId));
    }

    /** Card lock/unlock history */
    @GetMapping("/history/{cardIdHash}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<List<CardStatusDTO>> getHistory(@PathVariable String cardIdHash) {
        return ResponseEntity.ok(
            cardService.getCardHistory(cardIdHash).stream()
                .map(l -> new CardStatusDTO(l.getCardIdHash(), l.getElectionId(),
                    l.getEventType(), l.getTriggeredBy(), l.getCreatedAt()))
                .collect(Collectors.toList()));
    }

    /** All card events for an election */
    @GetMapping("/election/{electionId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<List<CardStatusDTO>> getElectionEvents(@PathVariable UUID electionId) {
        return ResponseEntity.ok(
            cardService.getElectionCardEvents(electionId).stream()
                .map(l -> new CardStatusDTO(l.getCardIdHash(), l.getElectionId(),
                    l.getEventType(), l.getTriggeredBy(), l.getCreatedAt()))
                .collect(Collectors.toList()));
    }
}
