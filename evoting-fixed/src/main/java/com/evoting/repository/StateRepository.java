package com.evoting.repository;
import com.evoting.model.State;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface StateRepository extends JpaRepository<State, Integer> {
    Optional<State> findByCode(String code);
}
