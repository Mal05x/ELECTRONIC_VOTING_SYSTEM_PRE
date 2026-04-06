package com.evoting.repository;
import com.evoting.model.VoterDemographics;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface VoterDemographicsRepository extends JpaRepository<VoterDemographics, UUID> {
    Optional<VoterDemographics> findByVoterId(UUID voterId);
}
