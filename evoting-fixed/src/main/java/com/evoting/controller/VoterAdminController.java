package com.evoting.controller;

import com.evoting.model.VoterRegistry;
import com.evoting.repository.VoterRegistryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/voters") 
public class VoterAdminController {

    @Autowired
    private VoterRegistryRepository voterRegistryRepo;

    /**
     * 1. GET ENDPOINT (Fixes the empty table on the React Frontend)
     * Fetches voters, optionally filtering by election ID.
     */
    @GetMapping
    public ResponseEntity<List<VoterRegistry>> getVoters(@RequestParam(required = false) UUID electionId) {
        List<VoterRegistry> voters;
        
        if (electionId != null) {
            voters = voterRegistryRepo.findByElectionId(electionId);
        } else {
            // V8 Permanent records: Return all if no specific election is requested
            voters = voterRegistryRepo.findAll();
        }
        
        return ResponseEntity.ok(voters);
    }

    /**
     * 2. PUT ENDPOINT (Replaces the TODO with actual database logic)
     * Locks or unlocks the physical card to prevent or allow voting.
     */
    @PutMapping("/{cardIdHash}/lock")
    public ResponseEntity<?> toggleCardLock(
            @PathVariable String cardIdHash,
            @RequestBody Map<String, Boolean> payload) {

        Boolean isLocked = payload.get("locked");
        
        if (isLocked == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "'locked' boolean is required"));
        }

        // Look up the voter by their permanent card hash
        Optional<VoterRegistry> voterOpt = voterRegistryRepo.findByCardIdHash(cardIdHash);

        if (voterOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "Voter not found for this card hash."
            ));
        }

        // Update the lock state and save it back to PostgreSQL
        VoterRegistry voter = voterOpt.get();
        voter.setCardLocked(isLocked);
        voterRegistryRepo.save(voter);

        return ResponseEntity.ok().body(Map.of(
                "success", true,
                "message", "Card lock status updated to " + isLocked,
                "cardLocked", isLocked
        ));
    }
}
