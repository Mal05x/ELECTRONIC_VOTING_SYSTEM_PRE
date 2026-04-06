package com.evoting.controller;
import com.evoting.dto.*;
import com.evoting.model.Candidate;
import com.evoting.repository.CandidateRepository;
import com.evoting.service.TallyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/results")
public class TallyController {

    @Autowired private TallyService         tallyService;
    @Autowired private CandidateRepository  candidateRepo;

    /**
     * GET /api/results/{electionId}
     *
     * Returns both:
     *   - candidateVotes: Map<candidateId, voteCount>  (for internal calculation)
     *   - candidates: List of enriched objects with id, fullName, party, votes
     *     (the shape the dashboard frontend expects for rendering standings)
     */
    @GetMapping("/{electionId}")
    public ResponseEntity<Map<String, Object>> getTally(@PathVariable UUID electionId) {
        Map<String, Long> tallyMap = tallyService.getTally(electionId);
        long total = tallyMap.values().stream().mapToLong(Long::longValue).sum();

        // Enrich with candidate metadata from the database
        List<Candidate> dbCandidates = candidateRepo.findByElectionId(electionId);
        List<Map<String, Object>> candidates = dbCandidates.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",       c.getId().toString());
                    m.put("fullName", c.getFullName());
                    m.put("party",    c.getParty() != null ? c.getParty() : "");
                    m.put("position", c.getPosition() != null ? c.getPosition() : "");
                    m.put("imageUrl", c.getImageUrl() != null ? c.getImageUrl() : "");
                    // Merge vote count from tally — 0 if no votes yet
                    m.put("votes",    tallyMap.getOrDefault(c.getId().toString(), 0L));
                    return m;
                })
                .sorted((a, b) -> Long.compare(
                        (Long) b.get("votes"), (Long) a.get("votes")))
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "electionId",     electionId,
                "candidateVotes", tallyMap,       // raw map for compatibility
                "candidates",     candidates,      // enriched array for dashboard
                "merkleRoot",     tallyService.getMerkleRoot(electionId) != null
                        ? tallyService.getMerkleRoot(electionId) : "",
                "totalVotes",     total
        ));
    }

    /** GET /api/results/{electionId}/by-state — Full per-state breakdown with turnout */
    @GetMapping("/{electionId}/by-state")
    public ResponseEntity<List<StateBreakdownDTO>> getStateBreakdown(@PathVariable UUID electionId) {
        return ResponseEntity.ok(tallyService.getStateBreakdown(electionId));
    }

    /** GET /api/results/{electionId}/state/{stateId} — Tally filtered to one state */
    @GetMapping("/{electionId}/state/{stateId}")
    public ResponseEntity<Map<String, Object>> getStateTally(
            @PathVariable UUID electionId, @PathVariable Integer stateId) {
        Map<String, Long> tally = tallyService.getStateTally(electionId, stateId);
        return ResponseEntity.ok(Map.of(
                "electionId",    electionId,
                "stateId",       stateId,
                "candidateVotes", tally,
                "totalVotes",    tally.values().stream().mapToLong(Long::longValue).sum()));
    }
    /** * GET /api/results/{electionId}/by-region
     * Dynamically returns results grouped by State, LGA, or Polling Unit based on election type.
     */
    @GetMapping("/{electionId}/by-region")
    public ResponseEntity<List<RegionalBreakdownDTO>> getRegionBreakdown(@PathVariable UUID electionId) {
        return ResponseEntity.ok(tallyService.getRegionalBreakdown(electionId));
    }
}
