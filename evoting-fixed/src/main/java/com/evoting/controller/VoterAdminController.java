package com.evoting.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/voters") // This perfectly matches your React frontend!
public class VoterAdminController {

    @PutMapping("/{cardIdHash}/lock")
    public ResponseEntity<?> toggleCardLock(
            @PathVariable String cardIdHash,
            @RequestBody Map<String, Boolean> payload) {

        Boolean isLocked = payload.get("locked");

        // TODO: Call your VoterRepo here to actually update the database lock status

        return ResponseEntity.ok().body(Map.of(
                "success", true,
                "message", "Card lock status updated to " + isLocked
        ));
    }
}