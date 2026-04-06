package com.evoting.controller;
import com.evoting.repository.BallotBoxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Public receipt tracker — voters verify their vote without revealing candidate choice. */
@RestController
@RequestMapping("/api/receipt")
public class ReceiptController {

    @Autowired private BallotBoxRepository ballotRepo;

    /** GET /api/receipt/{transactionId} */
    @GetMapping("/{transactionId}")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String transactionId) {
        return ballotRepo.findByTransactionId(transactionId.toUpperCase())
            .map(b -> ResponseEntity.ok(Map.<String, Object>of(
                "verified", true,
                "transactionId", b.getTransactionId(),
                "electionId",    b.getElectionId(),
                "castAt",        b.getCastAt(),
                "message",       "Your vote is confirmed in the ballot box.")))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("verified", false, "message", "No vote found for this transaction ID.")));
    }
}
