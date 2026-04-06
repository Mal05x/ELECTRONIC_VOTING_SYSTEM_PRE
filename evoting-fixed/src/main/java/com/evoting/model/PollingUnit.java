package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "polling_units")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PollingUnit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(unique = true)    private String code;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "lga_id", nullable = false) private Lga lga;
    @Column(nullable = false) private Integer capacity;
}
