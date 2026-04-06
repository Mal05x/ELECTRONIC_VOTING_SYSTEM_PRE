package com.evoting.controller;

import com.evoting.model.TerminalHeartbeat;
import com.evoting.repository.TerminalHeartbeatRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/terminals") // <-- Safe admin zone
public class AdminTerminalController {

    @Autowired
    private TerminalHeartbeatRepository terminalHeartbeatRepo;

    @GetMapping("/all")
    public ResponseEntity<List<TerminalHeartbeat>> getAllTerminalsForDashboard() {
        return ResponseEntity.ok(terminalHeartbeatRepo.findAll());
    }
}