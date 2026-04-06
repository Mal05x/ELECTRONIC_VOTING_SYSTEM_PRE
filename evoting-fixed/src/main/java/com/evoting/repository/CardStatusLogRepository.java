package com.evoting.repository;
import com.evoting.model.CardStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;
public interface CardStatusLogRepository extends JpaRepository<CardStatusLog, UUID> {
    List<CardStatusLog> findByCardIdHashOrderByCreatedAtDesc(String cardIdHash);
    List<CardStatusLog> findByElectionIdOrderByCreatedAtDesc(UUID electionId);
}
