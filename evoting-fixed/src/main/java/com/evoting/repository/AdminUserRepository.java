package com.evoting.repository;
import com.evoting.model.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {
    Optional<AdminUser> findByUsernameAndActiveTrue(String username);

    java.util.Optional<com.evoting.model.AdminUser> findByEmailIgnoreCase(String email);
}
