package com.evoting.service;

import com.evoting.repository.AdminUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * AdminExecutionService — executes admin account state changes
 * after multi-sig threshold is met.
 */
@Service @Slf4j
public class AdminExecutionService {

    @Autowired private AdminUserRepository adminRepo;
    @Autowired private AuditLogService     auditLog;

    @Transactional
    public void deactivateAdmin(UUID id) {
        adminRepo.findById(id).ifPresent(a -> {
            a.setActive(false);
            adminRepo.save(a);
            auditLog.log("ADMIN_DEACTIVATED", "SYSTEM[MULTISIG]",
                    "User=" + a.getUsername());
            log.info("[ADMIN] Deactivated: {}", a.getUsername());
        });
    }

    @Transactional
    public void activateAdmin(UUID id) {
        adminRepo.findById(id).ifPresent(a -> {
            a.setActive(true);
            adminRepo.save(a);
            auditLog.log("ADMIN_REACTIVATED", "SYSTEM[MULTISIG]",
                    "User=" + a.getUsername());
            log.info("[ADMIN] Activated: {}", a.getUsername());
        });
    }
}
