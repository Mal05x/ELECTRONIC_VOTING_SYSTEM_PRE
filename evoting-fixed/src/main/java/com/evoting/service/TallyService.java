package com.evoting.service;
import com.evoting.dto.StateBreakdownDTO;
import com.evoting.dto.RegionalBreakdownDTO;
import com.evoting.model.AuditLog;
import com.evoting.model.BallotBox;
import com.evoting.model.Election;
import com.evoting.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    // ── FIX DQ3: Rebuild Redis tally from DB on startup ───────────────────────
    //
    // PROBLEM:
    //   The national tally lives in Redis. If Redis is restarted mid-election,
    //   all tally keys for in-progress elections are lost. getTally() falls back
    //   to dbTally() when Redis is empty — this is correct — but the NEXT vote
    //   after restart calls incrementTally(), which starts Redis at 1 instead of
    //   (DB count + 1). The tally then diverges from the true count until Redis
    //   is re-populated.
    //
    // FIX:
    //   On ApplicationReadyEvent (after all beans are initialized), scan for all
    //   ACTIVE elections. For each, check if Redis has ANY tally keys. If Redis
    //   is empty for an active election (indicating a Redis restart during voting),
    //   seed it from the DB before the first increment arrives.
    //
    //   This is safe because:
    //   - ApplicationReadyEvent fires after Flyway migrations complete.
    //   - The rebuild is idempotent — if Redis already has keys, nothing changes.
    //   - DB is the source of truth; Redis is always a cache of it.
    //   - The Merkle root is also recomputed from DB to ensure consistency.
    //
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildRedisTallyOnStartup() {
        log.info("[TallyService] Checking Redis tally consistency for active elections...");
        List<Election> activeElections;
        try {
            activeElections = electionRepo.findAll().stream()
                    .filter(e -> "ACTIVE".equals(
                            e.getStatus() != null ? e.getStatus().name() : ""))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[TallyService] Could not query elections on startup (DB not ready?): {}",
                    e.getMessage());
            return;
        }

        if (activeElections.isEmpty()) {
            log.info("[TallyService] No active elections — Redis check skipped.");
            return;
        }

        for (Election election : activeElections) {
            UUID electionId = election.getId();
            try {
                Map<String, Long> redisState = scan(
                        TALLY_PFX + electionId + ":*",
                        TALLY_PFX + electionId + ":");

                if (redisState.isEmpty()) {
                    log.warn("[TallyService] Redis tally EMPTY for active election {} ({}). " +
                                    "Seeding from DB — likely cause: Redis restart during voting.",
                            electionId, election.getName());
                    seedTallyFromDb(electionId);
                } else {
                    log.info("[TallyService] Redis tally OK for election {} ({} candidate entries).",
                            electionId, redisState.size());
                }
            } catch (Exception e) {
                log.error("[TallyService] Failed to check/rebuild tally for election {}: {}",
                        electionId, e.getMessage());
                // Do not crash startup — log and continue; dbTally() fallback remains active
            }
        }
    }

    /**
     * Seeds Redis tally counters from the DB for a specific election.
     * Called when Redis is found empty for an active election after a restart.
     * Also rebuilds the Merkle root in Redis.
     */
    public void seedTallyFromDb(UUID electionId) {
        // National tally
        Map<String, Long> dbCounts = dbTally(electionId);
        dbCounts.forEach((candidateId, count) -> {
            String key = TALLY_PFX + electionId + ":" + candidateId;
            redis.opsForValue().set(key, count.toString());
        });
        log.info("[TallyService] Seeded {} candidate tally entries for election {}",
                dbCounts.size(), electionId);

        // State tallies — rebuild for each state that has votes
        ballotRepo.countVotesByState(electionId).forEach(row -> {
            Integer stateId = (Integer) row[0];
            ballotRepo.countVotesByCandidateAndState(electionId, stateId).forEach(r -> {
                String key = STATE_PFX + electionId + ":" + stateId + ":" + r[0];
                redis.opsForValue().set(key, r[1].toString());
            });
        });
        log.info("[TallyService] Seeded state tally entries for election {}", electionId);

        // Merkle root
        List<String> allHashes = ballotRepo.findByElectionId(electionId)
                .stream().map(BallotBox::getVoteHash).collect(Collectors.toList());
        if (!allHashes.isEmpty()) {
            String root = merkleService.computeMerkleRoot(allHashes);
            redis.opsForValue().set(MERKLE_PFX + electionId, root);
            log.info("[TallyService] Rebuilt Merkle root for election {} from {} votes: {}",
                    electionId, allHashes.size(), root.substring(0, 16) + "...");
        }
    }

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
                return new RegionalBreakdownDTO(lgaId, lga.getName(), code,
                        "LGA", total, 0L, 0.0, tally);
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
                return new RegionalBreakdownDTO(puId, pu.getName(),
                        pu.getCode() != null ? pu.getCode() : "",
                        "POLLING_UNIT", total, 0L, 0.0, tally);
            }).orElse(null);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    // ── Merkle root ───────────────────────────────────────────────────────────

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
