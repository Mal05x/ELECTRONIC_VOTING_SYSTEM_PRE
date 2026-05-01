package com.evoting.controller;

import com.evoting.model.TerminalHeartbeat;
import com.evoting.model.TerminalRegistry;
import com.evoting.repository.TerminalHeartbeatRepository;
import com.evoting.repository.TerminalRegistryRepository;
import com.evoting.service.TerminalAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/terminals") // <-- Safe admin zone
public class AdminTerminalController {

    @Autowired
    private TerminalHeartbeatRepository terminalHeartbeatRepo;

    // NEW: Inject the Registry repo and Auth service
    @Autowired
    private TerminalRegistryRepository terminalRegistryRepo;

    @Autowired
    private TerminalAuthService terminalAuthService;

    @GetMapping("/all")
    public ResponseEntity<List<TerminalHeartbeat>> getAllTerminalsForDashboard() {
        return ResponseEntity.ok(terminalHeartbeatRepo.findAll());
    }

    // ── MISSING ENDPOINTS ADDED BELOW ────────────────────────────────────────

    // 1. Fetch the authorized terminals for the Registry Tab
    @GetMapping("/registry")
    public ResponseEntity<List<TerminalRegistry>> getTerminalRegistry() {
        return ResponseEntity.ok(terminalRegistryRepo.findAll());
    }

    // 2. Handle the "Provision Terminal" form submission
    @PostMapping("/provision")
    public ResponseEntity<?> provisionTerminal(@RequestBody Map<String, Object> payload) {
        try {
            String terminalId = (String) payload.get("terminalId");
            String publicKey = (String) payload.get("publicKey");
            String label = (String) payload.get("label");

            Integer pollingUnitId = null;
            if (payload.get("pollingUnitId") != null) {
                pollingUnitId = Integer.parseInt(payload.get("pollingUnitId").toString());
            }

            // In a real app, grab the admin's username from Spring Security context
            String registeredBy = "SuperAdmin";

            // Delegate to your existing TerminalAuthService
            TerminalRegistry registered = terminalAuthService.register(
                    terminalId, publicKey, label, pollingUnitId, registeredBy
            );

            return ResponseEntity.ok(registered);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provisioning failed: " + e.getMessage()));
        }
    }
}