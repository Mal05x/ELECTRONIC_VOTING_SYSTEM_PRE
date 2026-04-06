package com.evoting.repository;
import com.evoting.model.AdminKeypair;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface AdminKeypairRepository extends JpaRepository<AdminKeypair, UUID> {
    Optional<AdminKeypair> findByAdminId(UUID adminId);
    boolean existsByAdminId(UUID adminId);
}
