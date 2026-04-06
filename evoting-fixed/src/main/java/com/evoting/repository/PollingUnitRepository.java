package com.evoting.repository;
import com.evoting.model.PollingUnit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PollingUnitRepository extends JpaRepository<PollingUnit, Long> {
    /** Fix 10: paginated version used by AdminController */
    Page<PollingUnit> findByLgaIdOrderByNameAsc(Integer lgaId, Pageable pageable);
    /** Non-paginated version still used by LocationController cascade dropdowns */
    List<PollingUnit> findByLgaIdOrderByNameAsc(Integer lgaId);
    Optional<PollingUnit> findByCode(String code);
}
