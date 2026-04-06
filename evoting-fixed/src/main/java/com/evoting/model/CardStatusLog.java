package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable log of every smart card lock/unlock event.
 * card_id_hash is the SHA-256 of the JCOP4 UID (same as voter_registry.card_id_hash).
 */
@Entity @Table(name = "card_status_log")
@Getter @NoArgsConstructor
public class CardStatusLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "card_id_hash", nullable = false) private String cardIdHash;
    @Column(name = "election_id",  nullable = false) private UUID electionId;
    @Column(name = "event_type",   nullable = false)
    @Enumerated(EnumType.STRING) private CardEvent eventType;
    @Column(name = "triggered_by", nullable = false) private String triggeredBy; // "AUTO_CLOSE" or admin username
    @Column(name = "created_at")   private OffsetDateTime createdAt;

    public enum CardEvent { LOCKED, UNLOCKED, REGISTRATION }

    public CardStatusLog(String cardIdHash, UUID electionId, CardEvent event, String triggeredBy) {
        this.cardIdHash  = cardIdHash;
        this.electionId  = electionId;
        this.eventType   = event;
        this.triggeredBy = triggeredBy;
        this.createdAt   = OffsetDateTime.now();
    }
}
