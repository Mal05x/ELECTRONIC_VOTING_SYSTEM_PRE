package com.evoting.controller;

import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import com.evoting.service.AuditLogService;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Admin preferences store — persists per-user key/value preferences.
 *
 * GET  /api/admin/settings/{section}  → returns a map of key→value for that section
 * PUT  /api/admin/settings/{section}  → upserts key→value pairs for that section
 *
 * Backed by the admin_preferences table (V6 migration).
 * Sections: notifications, display, session, terminals, system
 */
@RestController
@RequestMapping("/api/admin/preferences")
public class PreferencesController {

    @PersistenceContext
    private EntityManager em;

    @Autowired private AdminUserRepository adminRepo;
    @Autowired private AuditLogService      auditLog;

    @GetMapping("/{section}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, String>> getPreferences(
            @PathVariable String section, Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found"));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT pref_key, pref_value FROM admin_preferences " +
                                "WHERE admin_id = :adminId AND pref_key LIKE :prefix")
                .setParameter("adminId", admin.getId())
                .setParameter("prefix", section + ".%")
                .getResultList();
        Map<String, String> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = ((String) row[0]).replaceFirst("^" + section + "\\.", "");
            result.put(key, (String) row[1]);
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{section}")
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, String>> savePreferences(
            @PathVariable String section,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found"));
        UUID adminId = admin.getId();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String prefKey   = section + "." + entry.getKey();
            String prefValue = entry.getValue() != null ? entry.getValue().toString() : null;
            em.createNativeQuery(
                            "INSERT INTO admin_preferences (admin_id, pref_key, pref_value, updated_at) " +
                                    "VALUES (:adminId, :key, :value, :now) " +
                                    "ON CONFLICT (admin_id, pref_key) " +
                                    "DO UPDATE SET pref_value = EXCLUDED.pref_value, updated_at = EXCLUDED.updated_at")
                    .setParameter("adminId", adminId)
                    .setParameter("key",     prefKey)
                    .setParameter("value",   prefValue)
                    .setParameter("now",     OffsetDateTime.now())
                    .executeUpdate();
        }
        auditLog.log("SETTINGS_UPDATED", auth.getName(), "Section: " + section);
        return ResponseEntity.ok(Map.of("saved", String.valueOf(body.size()), "section", section));
    }
}
