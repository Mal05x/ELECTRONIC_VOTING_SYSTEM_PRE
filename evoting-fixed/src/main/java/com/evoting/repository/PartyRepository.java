package com.evoting.repository;
import com.evoting.model.Party;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface PartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByAbbreviationIgnoreCase(String abbreviation);
    Optional<Party> findByNameIgnoreCase(String name);
}
