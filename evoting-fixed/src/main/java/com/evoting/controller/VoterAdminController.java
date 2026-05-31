package com.evoting.controller;

import com.evoting.model.VoterRegistry;
import com.evoting.repository.VoterRegistryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/voters") 
public class VoterAdminController {

    @Autowired
    private VoterRegistryRepository voterRegistryRepo;

    @PutMapping("/{cardIdHash}/lock")
    public ResponseEntity<?> toggleCardLock(
            @PathVariable String cardIdHash,
            @RequestBody Map<String, Boolean> payload) {

        Boolean isLocked = payload.get("locked");
        if (isLocked == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "'locked' boolean is required"));
        }

        Optional<VoterRegistry> voterOpt = voterRegistryRepo.findByCardIdHash(cardIdHash);

        if (voterOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Voter not found"));
        }

        VoterRegistry voter = voterOpt.get();
        voter.setCardLocked(isLocked);
        voterRegistryRepo.save(voter);

        return ResponseEntity.ok().body(Map.of(
                "success", true,
                "message", "Card lock status updated",
                "cardLocked", isLocked
        ));
    }
}
