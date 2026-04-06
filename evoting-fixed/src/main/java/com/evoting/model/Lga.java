package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity @Table(name = "lgas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Lga {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer id;
    @Column(nullable = false) private String name;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "state_id", nullable = false) private State state;
    @OneToMany(mappedBy = "lga", fetch = FetchType.LAZY) private List<PollingUnit> pollingUnits;
}
