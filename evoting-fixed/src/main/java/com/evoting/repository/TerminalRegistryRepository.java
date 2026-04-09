package com.evoting.repository;

import com.evoting.model.TerminalRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TerminalRegistryRepository extends JpaRepository<TerminalRegistry, UUID> {
    Optional<TerminalRegistry> findByTerminalIdAndActiveTrue(String terminalId);
    boolean existsByTerminalId(String terminalId);
}
