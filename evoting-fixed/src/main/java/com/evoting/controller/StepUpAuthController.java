package com.evoting.controller;

import com.evoting.model.AdminUser;
import com.evoting.repository.AdminUserRepository;
import com.evoting.service.StepUpAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * StepUpAuthController — endpoints for ECDSA step-up authentication.
 *
 * GET  /api/auth/challenge?action=IMPORT_CANDIDATES
 *      Returns a one-time nonce the admin must sign before performing the action.
 *      Nonce expires in 60 seconds.
 *
 * All protected action endpoints expect these headers on the actual request:
 *   X-Action-Signature: <base64 ECDSA signature of "ACTION_TYPE:NONCE">
 *   X-Action-Nonce:     <the nonce from the challenge>
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class StepUpAuthController {

    @Autowired private StepUpAuthService    stepUpService;
    @Autowired private AdminUserRepository  adminRepo;

    /**
     * Request a challenge nonce for a specific action.
     * The frontend signs this nonce with the admin's private key,
     * then includes the signature on the actual action request.
     */
    @GetMapping("/challenge")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> getChallenge(
            @RequestParam String action,
            Authentication auth) {
        try {
            AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName()).orElseThrow();
            Map<String, Object> challenge = stepUpService.generateChallenge(admin.getId(), action.toUpperCase());
            return ResponseEntity.ok(challenge);
        } catch (IllegalStateException e) {
            // No keypair registered
            return ResponseEntity.status(412).body(Map.of(
                    "error",   e.getMessage(),
                    "code",    "NO_KEYPAIR",
                    "action",  "Go to Settings → Security & 2FA to register your signing key"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Returns the list of actions that require step-up authentication.
     * Frontend uses this to know which actions to intercept.
     */
    @GetMapping("/protected-actions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<?> getProtectedActions() {
        return ResponseEntity.ok(Map.of("actions", StepUpAuthService.PROTECTED_ACTIONS));
    }
}
