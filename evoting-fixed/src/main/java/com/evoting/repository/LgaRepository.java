package com.evoting.repository;
import com.evoting.model.Lga;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface LgaRepository extends JpaRepository<Lga, Integer> {
    List<Lga> findByStateIdOrderByNameAsc(Integer stateId);
}
