package com.evoting.repository;
import com.evoting.model.TerminalHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TerminalHeartbeatRepository extends JpaRepository<TerminalHeartbeat, UUID> {
    List<TerminalHeartbeat> findByTamperFlagTrue();
    // Allows us to find the specific terminal row to update it!
    Optional<TerminalHeartbeat> findByTerminalId(String terminalId);
}
