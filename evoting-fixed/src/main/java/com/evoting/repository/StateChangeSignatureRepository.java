package com.evoting.repository;
import com.evoting.model.StateChangeSignature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface StateChangeSignatureRepository extends JpaRepository<StateChangeSignature, UUID> {
    List<StateChangeSignature>  findByChangeId(UUID changeId);
    Optional<StateChangeSignature> findByChangeIdAndAdminId(UUID changeId, UUID adminId);
    boolean existsByChangeIdAndAdminId(UUID changeId, UUID adminId);

    @Query("SELECT COUNT(s) FROM StateChangeSignature s WHERE s.changeId = :changeId")
    long countByChangeId(@Param("changeId") UUID changeId);
}
