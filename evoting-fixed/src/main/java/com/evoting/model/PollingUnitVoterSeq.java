package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;

/** DB-level per-polling-unit voter sequence counter — safe across multiple JVM instances. */
@Entity @Table(name = "polling_unit_voter_seq")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PollingUnitVoterSeq {
    @Id
    @Column(name = "polling_unit_id")
    private Long pollingUnitId;

    @Column(name = "next_val", nullable = false)
    private Long nextVal = 0L;
}
