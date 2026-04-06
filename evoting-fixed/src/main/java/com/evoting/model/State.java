package com.evoting.model;
import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity @Table(name = "states")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class State {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer id;
    @Column(nullable = false, unique = true) private String name;
    @Column(unique = true, length = 5)       private String code;
    @OneToMany(mappedBy = "state", fetch = FetchType.LAZY) private List<Lga> lgas;
}
