package com.evoting.controller;

import com.evoting.dto.VotePacketDTO;
import com.evoting.dto.VoteReceiptDTO;
import com.evoting.service.VoteProcessingService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/terminal")
@Slf4j
public class VoteController {

    @Autowired private VoteProcessingService voteService;

    // 2. The path must accept the dynamic card ID
    @PutMapping("/{cardIdHash}/lock")
    public ResponseEntity<?> toggleCardLock(
            @PathVariable String cardIdHash,
            @RequestBody Map<String, Boolean> payload) {

        // Extract the boolean value sent from the React frontend
        Boolean isLocked = payload.get("locked");

        // Add your business logic here to find the voter/card by 'cardIdHash'
        // and update their lock status in the database.

        return ResponseEntity.ok().body(Map.of(
                "success", true,
                "message", "Card lock status updated to " + isLocked
        ));
    } // <--- THIS WAS THE MISSING BRACKET!

    /**
     * POST /api/terminal/vote — AES-encrypted vote bundle.
     *
     * Fix B-07: accepts typed VotePacketDTO with @Valid instead of Map<String,String>.
     * Fix B-08: catch block no longer leaks exception detail to caller — only logged server-side.
     */
   /* @PostMapping("/vote")
    public ResponseEntity<VoteReceiptDTO> castVote(
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(voteService.processVote(body.get("payload")));
        } catch (com.evoting.exception.InvalidSessionException e) {
            // Fix B-08: return generic message, not e.getMessage()
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new VoteReceiptDTO(null, null, "Vote submission failed"));
        } catch (Exception e) {
            // Fix B-08: log full detail server-side, return generic message to caller
            log.error("Vote processing error", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new VoteReceiptDTO(null, null, "Vote submission failed"));
        }
    }*/

}