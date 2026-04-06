package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name = "terminal_heartbeats")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TerminalHeartbeat {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "terminal_id",   nullable = false) private String  terminalId;
    @Column(name = "battery_level") private Short   batteryLevel;
    @Column(name = "tamper_flag",   nullable = false) private boolean tamperFlag = false;
    @Column(name = "ip_address")    private String  ipAddress;
    @Column(name = "reported_at")   private OffsetDateTime reportedAt = OffsetDateTime.now();
}
