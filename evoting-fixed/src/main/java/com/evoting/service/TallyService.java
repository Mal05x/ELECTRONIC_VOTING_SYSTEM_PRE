package com.evoting.service;
import com.evoting.dto.StateBreakdownDTO;
import com.evoting.dto.RegionalBreakdownDTO;
import com.evoting.model.AuditLog;
import com.evoting.model.BallotBox;
import com.evoting.model.Election;
import com.evoting.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service @Slf4j
public class TallyService {

    private static final String TALLY_PFX  = "tally:";
    private static final String STATE_PFX  = "tally:state:";
    private static final String MERKLE_PFX = "merkle:";

    @Autowired private StringRedisTemplate     redis;
    @Autowired private BallotBoxRepository     ballotRepo;
    @Autowired private StateRepository         stateRepo;
    @Autowired private LgaRepository           lgaRepo;
    @Autowired private PollingUnitRepository    pollingUnitRepo;
    @Autowired private ElectionRepository       electionRepo;
    @Autowired private VoterRegistryRepository voterRepo;
    @Autowired private MerkleTreeService       merkleService;
    @Autowired private SimpMessagingTemplate   ws;

    // ── National tally ────────────────────────────────────────────────────────

    public void incrementTally(UUID electionId, String candidateId) {
        redis.opsForValue().increment(TALLY_PFX + electionId + ":" + candidateId);
        ws.convertAndSend("/topic/results/" + electionId, getTally(electionId));
    }

    public Map<String, Long> getTally(UUID electionId) {
        Map<String, Long> cached = scan(TALLY_PFX + electionId + ":*", TALLY_PFX + electionId + ":");
        return cached.isEmpty() ? dbTally(electionId) : cached;
    }

    // ── State tally ───────────────────────────────────────────────────────────

    public void incrementStateTally(UUID electionId, Integer stateId, String candidateId) {
        redis.opsForValue().increment(STATE_PFX + electionId + ":" + stateId + ":" + candidateId);
        ws.convertAndSend("/topic/results/" + electionId + "/state/" + stateId,
                getStateTally(electionId, stateId));
    }

    public Map<String, Long> getStateTally(UUID electionId, Integer stateId) {
        Map<String, Long> cached = scan(
                STATE_PFX + electionId + ":" + stateId + ":*",
                STATE_PFX + electionId + ":" + stateId + ":");
        if (!cached.isEmpty()) return cached;
        return ballotRepo.countVotesByCandidateAndState(electionId, stateId).stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> (Long) r[1],
                        (a, b) -> a, LinkedHashMap::new));
    }

    // ── Full state breakdown ──────────────────────────────────────────────────

    public List<StateBreakdownDTO> getStateBreakdown(UUID electionId) {
        return ballotRepo.countVotesByState(electionId).stream().map(row -> {
            Integer stateId = (Integer) row[0];
            long    total   = (Long)    row[1];
            return stateRepo.findById(stateId).map(state -> {
                Map<String, Long> tally = ballotRepo
                        .countVotesByCandidateAndState(electionId, stateId).stream()
                        .collect(Collectors.toMap(r -> r[0].toString(), r -> (Long) r[1],
                                (a, b) -> a, LinkedHashMap::new));
                long reg    = voterRepo.countByElectionIdAndPollingUnitLgaStateId(electionId, stateId);
                long voted  = voterRepo.countByElectionIdAndHasVotedTrueAndPollingUnitLgaStateId(electionId, stateId);
                double turnout = reg > 0 ? Math.round(voted * 10000.0 / reg) / 100.0 : 0.0;
                return new StateBreakdownDTO(electionId, state.getName(), state.getCode(),
                        stateId, total, reg, voted, turnout, tally);
            }).orElse(null);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }


    // ── Dynamic regional breakdown ────────────────────────────────────────────

    /**
     * Returns a regional breakdown whose granularity adapts to the election type:
     *
     *   PRESIDENTIAL              → one row per STATE (37 rows max)
     *   GUBERNATORIAL / SENATORIAL
     *     / STATE_ASSEMBLY        → one row per LGA (up to 774 rows)
     *   LOCAL_GOVERNMENT          → one row per POLLING_UNIT
     *
     * Called by GET /api/results/{id}/by-region.
     * The frontend uses the regionType field to label the chart header and legend.
     */
    public List<RegionalBreakdownDTO> getRegionalBreakdown(UUID electionId) {
        Election election = electionRepo.findById(electionId)
                .orElseThrow(() -> new IllegalArgumentException("Election not found: " + electionId));

        String type = election.getType() != null ? election.getType() : "PRESIDENTIAL";

        switch (type) {
            case "GUBERNATORIAL":
            case "SENATORIAL":
            case "STATE_ASSEMBLY":
                return getLgaBreakdown(electionId);

            case "LOCAL_GOVERNMENT":
                return getPollingUnitBreakdown(electionId);

            case "PRESIDENTIAL":
            default:
                // Reuse existing state breakdown, map to RegionalBreakdownDTO
                return getStateBreakdown(electionId).stream().map(s ->
                        new RegionalBreakdownDTO(
                                s.getStateId(), s.getStateName(), s.getStateCode(),
                                "STATE", s.getTotalVotes(),
                                s.getRegisteredVoters(), s.getTurnoutPercent(),
                                s.getCandidateTally())
                ).collect(Collectors.toList());
        }
    }

    private List<RegionalBreakdownDTO> getLgaBreakdown(UUID electionId) {
        return ballotRepo.countVotesByLga(electionId).stream().map(row -> {
            Integer lgaId = row[0] instanceof Number ? ((Number) row[0]).intValue() : null;
            if (lgaId == null) return null;
            long total = ((Number) row[1]).longValue();

            return lgaRepo.findById(lgaId).map(lga -> {
                Map<String, Long> tally = ballotRepo
                        .countVotesByCandidateForLga(electionId, lgaId).stream()
                        .collect(Collectors.toMap(
                                r -> r[0].toString(),
                                r -> ((Number) r[1]).longValue(),
                                (a, b) -> a, LinkedHashMap::new));

                String code = lga.getState() != null ? lga.getState().getCode() + "/" + lgaId : "";
                return new RegionalBreakdownDTO(
                        lgaId, lga.getName(), code,
                        "LGA", total,
                        0L,   // registered voters at LGA granularity not cached — omit
                        0.0,
                        tally);
            }).orElse(null);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private List<RegionalBreakdownDTO> getPollingUnitBreakdown(UUID electionId) {
        return ballotRepo.countVotesByPollingUnit(electionId).stream().map(row -> {
            Long puId = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
            if (puId == null) return null;
            long total = ((Number) row[1]).longValue();

            return pollingUnitRepo.findById(puId).map(pu -> {
                Map<String, Long> tally = ballotRepo
                        .countVotesByCandidateForPollingUnit(electionId, puId).stream()
                        .collect(Collectors.toMap(
                                r -> r[0].toString(),
                                r -> ((Number) r[1]).longValue(),
                                (a, b) -> a, LinkedHashMap::new));

                return new RegionalBreakdownDTO(
                        puId, pu.getName(), pu.getCode() != null ? pu.getCode() : "",
                        "POLLING_UNIT", total,
                        0L, 0.0,
                        tally);
            }).orElse(null);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    // ── Merkle root ───────────────────────────────────────────────────────────

    /**
     * Fix B-14: updateMerkleRoot() now delegates to MerkleTreeService.computeMerkleRoot().
     *
     * BUG: The previous implementation computed SHA256(prevRoot || newVoteHash) — a simple
     * hash chain, not a binary Merkle tree. getMerkleRoot() fell back to MerkleTreeService
     * when Redis was empty. The two methods produced DIFFERENT roots for the same vote set,
     * breaking public auditability: a verifier rebuilding the tree from ballot_box hashes
     * got a different root than what was published on /api/results.
     *
     * FIX: Both updateMerkleRoot() and getMerkleRoot() now use the same
     * MerkleTreeService.computeMerkleRoot() algorithm — the published root always
     * matches what an independent verifier can compute from the public ballot_box hashes.
     *
     * Scale note: full tree recomputation is O(n) per vote. For very large elections,
     * migrate to an incremental Merkle tree data structure.
     */
    public void updateMerkleRoot(UUID electionId, String newVoteHash) {
        List<String> allHashes = ballotRepo.findByElectionId(electionId)
                .stream().map(BallotBox::getVoteHash).collect(Collectors.toList());
        String newRoot = merkleService.computeMerkleRoot(allHashes);
        redis.opsForValue().set(MERKLE_PFX + electionId, newRoot);
        ws.convertAndSend("/topic/merkle/" + electionId, Map.of("merkleRoot", newRoot));
    }

    public String getMerkleRoot(UUID electionId) {
        String root = redis.opsForValue().get(MERKLE_PFX + electionId);
        if (root != null) return root;
        // DB fallback uses the same algorithm — consistent with updateMerkleRoot()
        List<String> hashes = ballotRepo.findByElectionId(electionId)
                .stream().map(BallotBox::getVoteHash).collect(Collectors.toList());
        return merkleService.computeMerkleRoot(hashes);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Long> dbTally(UUID electionId) {
        return ballotRepo.countVotesByCandidate(electionId).stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> (Long) r[1],
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** SCAN-based iteration — safe for production Redis (never blocks). */
    private Map<String, Long> scan(String pattern, String stripPrefix) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            redis.getConnectionFactory().getConnection()
                    .scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern).count(200).build())
                    .forEachRemaining(k -> {
                        String key = new String(k);
                        String val = redis.opsForValue().get(key);
                        result.put(key.replace(stripPrefix, ""), val != null ? Long.parseLong(val) : 0L);
                    });
        } catch (Exception e) {
            log.warn("Redis SCAN failed, falling back to DB: {}", e.getMessage());
        }
        return result;
    }
}
