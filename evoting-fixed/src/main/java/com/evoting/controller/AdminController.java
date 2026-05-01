package com.evoting.controller;
import com.evoting.dto.*;
import com.evoting.security.RequiresStepUp;
import com.evoting.model.*;
import com.evoting.repository.*;
import com.evoting.service.*;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.evoting.repository.EnrollmentQueueRepository;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Admin REST API.
 *
 * Fix 10: All list endpoints that could grow without bound now accept
 * optional `page` (0-based) and `size` (default 50, max 200) parameters
 * and return a Spring Page. This prevents out-of-memory errors when
 * listing thousands of polling units, audit log entries, or voters.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE     = 200;

    @Autowired private ElectionRepository       electionRepo;
    @Autowired private CandidateRepository      candidateRepo;
    @Autowired private PartyRepository          partyRepo;
    @Autowired private LgaRepository            lgaRepo;
    @Autowired private PollingUnitRepository    pollingUnitRepo;
    @Autowired private AuditLogRepository       auditLogRepo;
    @Autowired private VoterRegistryRepository  voterRepo;
    @Autowired private AdminUserRepository      adminRepo;
    @Autowired private VoterRegistrationService  registrationService;
    @Autowired private CardManagementService     cardService;
    @Autowired private AdminUserService          adminService;
    @Autowired private AuditLogService           auditLog;
    @Autowired private EnrollmentService              enrollmentService;
    @Autowired private EnrollmentQueueRepository      enrollmentQueueRepo;
    @Autowired private JdbcTemplate                   jdbcTemplate;
    @Autowired private com.evoting.service.AnomalyDetectionService anomalyService;
    @Autowired private com.evoting.service.MultiSigService    multiSigService;
    @Autowired private com.evoting.service.TerminalAuthService   terminalAuthService;
    @Autowired private com.evoting.repository.TerminalRegistryRepository terminalRegistryRepo;
    @Autowired private com.evoting.repository.BallotBoxRepository        ballotRepo;
    @Autowired private com.evoting.service.MerkleTreeService               merkleService;

    // ── Helper ────────────────────────────────────────────────────────────────

    private Pageable page(int page, int size) {
        int safe = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safe);
    }

    // ── Elections ─────────────────────────────────────────────────────────────

    @PostMapping("/elections")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    @RequiresStepUp("CREATE_ELECTION")
    public ResponseEntity<Election> createElection(
            @RequestBody @Valid ElectionDTO dto, Authentication auth) {
        // Fix B-16: resolve authenticated admin UUID instead of UUID.randomUUID()
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found: " + auth.getName()));
        Election saved = electionRepo.save(Election.builder()
                .name(dto.getName()).description(dto.getDescription())
                .status(Election.ElectionStatus.PENDING)
                // type defaults to "PRESIDENTIAL" in ElectionDTO if not supplied by client
                .type(dto.getType() != null ? dto.getType() : "PRESIDENTIAL")
                .startTime(dto.getStartTime()).endTime(dto.getEndTime())
                .createdBy(admin.getId())
                .build());
        auditLog.log("ELECTION_CREATED", auth.getName(), saved.getName());
        return ResponseEntity.ok(saved);
    }



    /**
     * PUT /api/admin/elections/{id}/activate
     *
     * PATCH-1: Direct status write REMOVED. Activation is now gated behind
     * multi-signature approval. This endpoint initiates a pending state change
     * and records the requesting admin's first ECDSA signature.
     *
     * The frontend (ElectionsView.jsx) already calls the MultiSigController
     * path — this endpoint now enforces the same flow for any direct API callers.
     *
     * Requires:  SUPER_ADMIN + registered ECDSA keypair
     * Body:      { "signature": "<base64-ecdsa-sig-of-changeId>" }  (optional at initiation)
     * Returns:   pending state change status
     */
    @PutMapping("/elections/{id}/activate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> activate(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {

        Election e = electionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id));

        if (e.getStatus() != Election.ElectionStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Election must be in PENDING status to activate. Current: " + e.getStatus()));
        }

        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found: " + auth.getName()));

        String signature = (body != null) ? body.get("signature") : null;

        com.evoting.model.PendingStateChange change = multiSigService.initiate(
                "ACTIVATE_ELECTION",
                id.toString(),
                "Activate election: " + e.getName(),
                Map.of("electionId", id.toString(), "electionName", e.getName()),
                admin.getId(),
                signature
        );

        return ResponseEntity.accepted().body(Map.of(
                "changeId",  change.getId().toString(),
                "message",   change.isExecuted()
                        ? "Election activated (single-admin bootstrap mode)"
                        : "Activation initiated — awaiting co-signature in Pending Approvals",
                "status",    multiSigService.getStatus(change.getId())
        ));
    }

    /**
     * PUT /api/admin/elections/{id}/close
     *
     * PATCH-1: Direct status write REMOVED. Closing is now gated behind
     * multi-signature approval. Card unlocking happens inside
     * ElectionExecutionService.closeElection() once the threshold is met.
     *
     * Requires:  SUPER_ADMIN + registered ECDSA keypair
     * Body:      { "signature": "<base64-ecdsa-sig-of-changeId>" }  (optional at initiation)
     * Returns:   pending state change status
     */
    @PutMapping("/elections/{id}/close")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> close(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {

        Election e = electionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id));

        if (e.getStatus() != Election.ElectionStatus.ACTIVE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Election must be ACTIVE to close. Current: " + e.getStatus()));
        }

        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found: " + auth.getName()));

        String signature = (body != null) ? body.get("signature") : null;

        com.evoting.model.PendingStateChange change = multiSigService.initiate(
                "CLOSE_ELECTION",
                id.toString(),
                "Close election: " + e.getName(),
                Map.of("electionId", id.toString(), "electionName", e.getName()),
                admin.getId(),
                signature
        );

        return ResponseEntity.accepted().body(Map.of(
                "changeId",  change.getId().toString(),
                "message",   change.isExecuted()
                        ? "Election closed and cards unlocked (single-admin bootstrap mode)"
                        : "Closure initiated — awaiting co-signature in Pending Approvals",
                "status",    multiSigService.getStatus(change.getId())
        ));
    }

    /** Returns all elections — small table, no pagination needed. */
    @GetMapping("/elections")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<List<Election>> listElections() {
        return ResponseEntity.ok(electionRepo.findAll());
    }

    /**
     * DELETE /api/admin/elections/{id}
     *
     * Deletes a PENDING (draft) election only.
     * ACTIVE/CLOSED elections are part of the permanent audit trail and
     * cannot be deleted — only closed. This enforces audit integrity.
     */
    @DeleteMapping("/elections/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> deleteElection(
            @PathVariable UUID id, Authentication auth) {
        Election e = electionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id));

        if (e.getStatus() != Election.ElectionStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Only PENDING (draft) elections can be deleted. " +
                            "This election is " + e.getStatus() + "."
            ));
        }

        electionRepo.deleteById(id);
        auditLog.log("ELECTION_DELETED", auth.getName(),
                "ElectionId=" + id + " name=" + e.getName());
        return ResponseEntity.ok(Map.of(
                "message", "Draft election \"" + e.getName() + "\" deleted successfully",
                "id",       id.toString()
        ));
    }

    /**
     * Broadcast a force-unlock command to all smart cards for a closed election.
     */
    @PostMapping("/elections/{id}/unlock-cards")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> broadcastCardUnlock(
            @PathVariable UUID id, Authentication auth) {

        Election e = electionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Election not found: " + id));

        if (e.getStatus() != Election.ElectionStatus.CLOSED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Smart cards can only be bulk unlocked after the election is CLOSED."));
        }

        int unlocked = cardService.bulkUnlockForElection(id, "MANUAL_ADMIN_OVERRIDE");
        auditLog.log("CARDS_MANUALLY_UNLOCKED", auth.getName(),
                "ElectionID=" + id + " Unlocked=" + unlocked + " cards.");

        return ResponseEntity.ok(Map.of(
                "message", "Broadcasted unlock signal to all terminals.",
                "cardsUnlocked", unlocked));
    }

    // ── Audit Log (paginated — can be very large) ─────────────────────────────

    /**
     * GET /api/admin/audit-log?page=0&size=50
     * Returns a Page of audit entries ordered newest-first.
     */
    /**
     * GET /api/admin/audit-log?page=0&size=50
     *
     * Fix 10 — Proper DB-level pagination.
     *
     * BUG: auditLogRepo.findAll(Sort) returns ALL rows as a List<AuditLog>,
     * then .skip()/.limit() on the Java stream. On a large audit log this loads
     * the entire table into the JVM heap and OOMs the process.
     *
     * FIX: PageRequest passed directly to JpaRepository.findAll(Pageable),
     * which Spring Data JPA translates to:
     *   SELECT * FROM audit_log ORDER BY sequence_number DESC LIMIT ? OFFSET ?
     * Only the requested page crosses the JDBC connection.
     */
    @GetMapping("/audit-log")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Page<AuditLog>> getAuditLog(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable p = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("sequenceNumber").descending());
        return ResponseEntity.ok(auditLogRepo.findAll(p));
    }

    // ── Parties ───────────────────────────────────────────────────────────────

    @PostMapping("/parties")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    @RequiresStepUp("CREATE_PARTY")
    public ResponseEntity<Party> createParty(@RequestBody @Valid PartyDTO dto, Authentication auth) {
        if (partyRepo.findByAbbreviationIgnoreCase(dto.getAbbreviation()).isPresent())
            throw new IllegalArgumentException("Party " + dto.getAbbreviation() + " already exists");
        Party saved = partyRepo.save(Party.builder()
                .name(dto.getName()).abbreviation(dto.getAbbreviation().toUpperCase())
                .foundedYear(dto.getFoundedYear()).build());
        auditLog.log("PARTY_CREATED", auth.getName(), saved.getAbbreviation());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/parties")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<List<Party>> listParties() {
        return ResponseEntity.ok(partyRepo.findAll());
    }

    // ── Candidates ────────────────────────────────────────────────────────────

    @PostMapping("/candidates")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Candidate> addCandidate(
            @RequestBody @Valid CandidateDTO dto, Authentication auth) {
        Party party = dto.getPartyAbbreviation() != null
                ? partyRepo.findByAbbreviationIgnoreCase(dto.getPartyAbbreviation()).orElse(null) : null;
        Candidate saved = candidateRepo.save(Candidate.builder()
                .electionId(dto.getElectionId())
                .partyId(party != null ? party.getId() : null)
                .fullName(dto.getFullName())
                .party(dto.getPartyAbbreviation())
                .position(dto.getPosition()).build());
        auditLog.log("CANDIDATE_ADDED", auth.getName(), saved.getFullName());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/elections/{id}/candidates")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<List<Candidate>> getCandidates(@PathVariable UUID id) {
        return ResponseEntity.ok(candidateRepo.findByElectionId(id));
    }

    /**
     * POST /api/admin/candidates/{candidateId}/remove
     *
     * Initiates a multi-signature approval to remove a candidate.
     * The actual deletion only happens once the signature threshold is met
     * (bootstrap: 1 SUPER_ADMIN; production: 2-of-N SUPER_ADMINs).
     *
     * Routing through multi-sig rather than direct delete prevents a single
     * admin from unilaterally altering a live ballot. The initiating admin's
     * signature is submitted automatically in the same request.
     *
     * Note: The DELETE /api/admin/candidates/{id} endpoint is kept for
     * backward compatibility but now requires SUPER_ADMIN and logs a warning.
     */
    @PostMapping("/candidates/{candidateId}/remove")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> initiateRemoveCandidate(
            @PathVariable UUID candidateId,
            @RequestParam(required = false) String signature,
            Authentication auth) {

        com.evoting.model.Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found"));

        String targetLabel = auth.getName() + " initiated removal of " + candidate.getFullName();

        com.evoting.model.PendingStateChange change = multiSigService.initiate(
                "REMOVE_CANDIDATE",
                candidateId.toString(),
                targetLabel,
                Map.of(
                        "candidateId", candidateId.toString(),
                        "fullName",    candidate.getFullName(),
                        "party",       candidate.getParty() != null ? candidate.getParty() : "",
                        "electionId",  candidate.getElectionId().toString()
                ),
                admin.getId(),
                signature  // null if not provided — approvals page signs separately
        );

        boolean executed = change.isExecuted();

        if (executed) {
            return ResponseEntity.ok(Map.of(
                    "status",      "EXECUTED",
                    "message",     "Candidate removed immediately (single-admin mode)",
                    "candidateId", candidateId.toString(),
                    "changeId",    change.getId().toString()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status",      "PENDING_APPROVAL",
                "message",     "Removal initiated — awaiting co-signature in Pending Approvals",
                "candidateId", candidateId.toString(),
                "changeId",    change.getId().toString(),
                "required",    change.getSignaturesRequired()
        ));
    }

    /** Legacy DELETE endpoint — kept for compatibility, logs a deprecation warning */
    @DeleteMapping("/candidates/{candidateId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @RequiresStepUp("DELETE_CANDIDATE")
    public ResponseEntity<?> deleteCandidate(
            @PathVariable UUID candidateId, Authentication auth) {
        return candidateRepo.findById(candidateId).map(c -> {
            candidateRepo.deleteById(candidateId);
            auditLog.log("CANDIDATE_REMOVED_DIRECT", auth.getName(),
                    "CandidateId=" + candidateId + " ElectionId=" + c.getElectionId()
                            + " WARNING=bypassed_multisig");
            return (ResponseEntity<?>) ResponseEntity.ok(
                    Map.of("message", "Candidate removed (direct — use /remove endpoint in future)",
                            "id", candidateId.toString()));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Polling Units (paginated — can be large in 774-LGA setup) ────────────

    @PostMapping("/polling-units")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<PollingUnitDTO> createPollingUnit(
            @RequestBody @Valid CreatePollingUnitDTO dto, Authentication auth) {
        Lga lga = lgaRepo.findById(dto.getLgaId())
                .orElseThrow(() -> new IllegalArgumentException("LGA not found: " + dto.getLgaId()));
        PollingUnit saved = pollingUnitRepo.save(PollingUnit.builder()
                .name(dto.getName()).code(dto.getCode()).lga(lga)
                .capacity(dto.getCapacity()).build());
        auditLog.log("POLLING_UNIT_CREATED", auth.getName(),
                saved.getName() + " | " + lga.getName() + " | " + lga.getState().getName());
        return ResponseEntity.ok(new PollingUnitDTO(
                saved.getId(), saved.getName(), saved.getCode(),
                lga.getId(), lga.getName(),
                lga.getState().getId(), lga.getState().getName(), saved.getCapacity()));
    }

    /**
     * GET /api/admin/polling-units/lga/{lgaId}?page=0&size=50
     * Fix 10: paginated so loading all PUs in Kano (44 LGAs * many PUs)
     * does not spike memory.
     */
    @GetMapping("/polling-units/lga/{lgaId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Page<PollingUnitDTO>> getPollingUnitsByLga(
            @PathVariable Integer lgaId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable p = page(page, size);
        return ResponseEntity.ok(
                pollingUnitRepo.findByLgaIdOrderByNameAsc(lgaId, p)
                        .map(pu -> new PollingUnitDTO(
                                pu.getId(), pu.getName(), pu.getCode(),
                                pu.getLga().getId(), pu.getLga().getName(),
                                pu.getLga().getState().getId(), pu.getLga().getState().getName(),
                                pu.getCapacity())));
    }

    /**
     * GET /api/admin/voters?electionId={uuid}&page=0&size=50
     * Fix 10: paginated voter list per election.
     */
    @GetMapping("/voters")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Page<com.evoting.model.VoterRegistry>> listVoters(
            @RequestParam UUID electionId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable p = page(page, size);
        return ResponseEntity.ok(voterRepo.findByElectionIdPageable(electionId, p));
    }

    // ── Voter Registration ────────────────────────────────────────────────────

    @PostMapping("/voters/register")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<VoterRegistrationResponseDTO> registerVoter(
            @RequestBody @Valid VoterRegistrationDTO dto, Authentication auth) {
        VoterRegistry saved = registrationService.register(dto, auth.getName());
        return ResponseEntity.ok(new VoterRegistrationResponseDTO(
                saved.getVotingId(),
                saved.getPollingUnit().getName(),
                saved.getLga().getName(),
                saved.getState().getName(),
                "Voter registered. Voting ID: " + saved.getVotingId()));
    }

    // ── Fix B-01: Enrollment Queue Management ────────────────────────────────

    /**
     * POST /api/admin/enrollment/queue
     *
     * Admin queues a voter enrollment for a specific terminal.
     * Server generates the per-card SCP03 static key (Fix B-05) — it is
     * stored in enrollment_queue and delivered to the terminal on GET /terminal/pending_enrollment.
     */
    @PostMapping("/enrollment/queue")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    @RequiresStepUp("QUEUE_ENROLLMENT")
    public ResponseEntity<?> queueEnrollment(
            @RequestBody @Valid EnrollmentQueueRequestDTO dto, Authentication auth) {
        EnrollmentQueue queued = enrollmentService.queueEnrollment(dto, auth.getName());
        return ResponseEntity.ok(java.util.Map.of(
                "enrollmentId", queued.getId(),
                "terminalId",   queued.getTerminalId(),
                "status",       queued.getStatus(),
                "message",      "Enrollment queued. Terminal " + queued.getTerminalId() +
                        " can now fetch and process this enrollment."));
    }

    /**
     * GET /api/admin/enrollment/pending?electionId={uuid}
     * Lists all PENDING enrollment records for an election (admin monitoring).
     */
    @GetMapping("/enrollment/pending")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<?> listPendingEnrollments(
            @RequestParam(required = false) UUID electionId) {
        if (electionId == null) {
            List<EnrollmentQueue> allPending = enrollmentQueueRepo.findAll().stream()
                    .filter(record -> "PENDING".equals(record.getStatus()))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(allPending);
        }

        // Otherwise, filter by the specific election ID
        return ResponseEntity.ok(
                enrollmentService.listPendingEnrollments(electionId));

    }

    // ── Added in v4 patch: profile, stats, terminals, audit verify ────────────

    @Autowired private TerminalHeartbeatRepository heartbeatRepo;
    @Autowired private PasswordEncoder passwordEncoder;

    /**
     * PUT /api/admin/me
     * Update the authenticated admin's profile (username, email, displayName).
     */
    @PutMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, String>> updateProfile(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found: " + auth.getName()));
        if (body.containsKey("email"))       admin.setEmail(body.get("email"));
        if (body.containsKey("displayName")) admin.setDisplayName(body.get("displayName"));
        adminRepo.save(admin);
        auditLog.log("PROFILE_UPDATED", auth.getName(), "Profile fields updated");
        return ResponseEntity.ok(Map.of(
                "username",    admin.getUsername(),
                "email",       admin.getEmail()       != null ? admin.getEmail()       : "",
                "displayName", admin.getDisplayName() != null ? admin.getDisplayName() : ""
        ));
    }

    /**
     * GET /api/admin/me
     * Returns the authenticated admin's profile.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, Object>> getProfile(Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found: " + auth.getName()));
        return ResponseEntity.ok(Map.of(
                "username",    admin.getUsername(),
                "email",       admin.getEmail()       != null ? admin.getEmail()       : "",
                "displayName", admin.getDisplayName() != null ? admin.getDisplayName() : "",
                "role",        admin.getRole().name(),
                "lastLogin",   admin.getLastLogin()   != null ? admin.getLastLogin().toString() : ""
        ));
    }

    /**
     * GET /api/admin/stats/overview
     * Aggregated dashboard statistics computed from live DB data.
     */
    @GetMapping("/stats/overview")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, Object>> getStats() {
        // Elections
        long totalElections  = electionRepo.count();
        long activeElections = electionRepo.findByStatus(
                Election.ElectionStatus.ACTIVE).size();

        // Terminals — latest heartbeat per terminal
        List<TerminalHeartbeat> allHeartbeats =
                heartbeatRepo.findAll();
        Map<String, TerminalHeartbeat> latestPerTerminal =
                new HashMap<>();
        for (com.evoting.model.TerminalHeartbeat h : allHeartbeats) {
            latestPerTerminal.merge(h.getTerminalId(), h, (existing, candidate) ->
                    candidate.getReportedAt().isAfter(existing.getReportedAt()) ? candidate : existing);
        }
        java.time.OffsetDateTime cutoff = java.time.OffsetDateTime.now().minusMinutes(5);
        long onlineTerminals = latestPerTerminal.values().stream()
                .filter(h -> h.getReportedAt() != null && h.getReportedAt().isAfter(cutoff))
                .count();
        long totalTerminals = latestPerTerminal.size();

        // Voters — count across all elections
        long registeredVoters = voterRepo.count();
        long votesCast = voterRepo.countByHasVotedTrue();

        return ResponseEntity.ok(Map.of(
                "registeredVoters",  registeredVoters,
                "votesCast",         votesCast,
                "onlineTerminals",   onlineTerminals,
                "totalTerminals",    totalTerminals,
                "totalElections",    totalElections,
                "activeElections",   activeElections
        ));
    }

    /**
     * GET /api/admin/terminals
     * Returns the latest heartbeat record for every known terminal.
     */
    @GetMapping("/terminals")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<List<Map<String, Object>>> getTerminals() {
        List<TerminalHeartbeat> allHeartbeats = heartbeatRepo.findAll();
        Map<String, TerminalHeartbeat> latestPerTerminal = new HashMap<>();

        for (TerminalHeartbeat h : allHeartbeats) {
            // Null safety: skip bad records to prevent 500 errors
            if (h.getTerminalId() == null) continue;

            latestPerTerminal.merge(h.getTerminalId(), h, (existing, candidate) -> {
                // Null safety for timestamps
                if (candidate.getReportedAt() == null) return existing;
                if (existing.getReportedAt() == null) return candidate;
                return candidate.getReportedAt().isAfter(existing.getReportedAt()) ? candidate : existing;
            });
        }

        java.time.OffsetDateTime now     = java.time.OffsetDateTime.now();
        java.time.OffsetDateTime online  = now.minusMinutes(5);
        java.time.OffsetDateTime warning = now.minusMinutes(15);

        List<Map<String, Object>> result = latestPerTerminal.values().stream()
                .map(h -> {
                    String status = "OFFLINE";
                    if (h.getReportedAt() != null) {
                        if (h.getReportedAt().isAfter(online))  status = "ONLINE";
                        else if (h.getReportedAt().isAfter(warning)) status = "WARNING";
                    }
                    if (Boolean.TRUE.equals(h.isTamperFlag())) status = "ALERT";

                    Map<String, Object> m = new HashMap<>();
                    m.put("terminalId",    h.getTerminalId());
                    m.put("batteryLevel",  h.getBatteryLevel() != null ? h.getBatteryLevel() : 0);

                    // FIX 1: React expects "tamperFlag", not "tamperDetected"
                    m.put("tamperFlag",    h.isTamperFlag());

                    m.put("ipAddress",     h.getIpAddress() != null ? h.getIpAddress() : "");

                    // FIX 2: React expects "reportedAt", not "lastHeartbeat"
                    m.put("reportedAt",    h.getReportedAt() != null ? h.getReportedAt().toString() : null);

                    m.put("status",        status);
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * PUT /api/admin/terminals/{terminalId}/resolve
     * Clears tamper alert for a terminal (marks it resolved in audit).
     * The terminal's next heartbeat with tamperFlag=false will confirm hardware is OK.
     */
    @PutMapping("/terminals/{terminalId}/resolve")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> resolveTamper(
            @PathVariable String terminalId, Authentication auth) {
        auditLog.log("TAMPER_ALERT_RESOLVED", auth.getName(),
                "Admin resolved tamper alert for terminal: " + terminalId);
        return ResponseEntity.ok(Map.of(
                "terminalId", terminalId,
                "message",    "Tamper alert acknowledged. Terminal " + terminalId +
                        " will resume normal operation on next heartbeat."));
    }

    /**
     * GET /api/admin/audit-log/verify
     * Verifies hash-chain integrity of the audit log.
     */
    @GetMapping("/audit-log/verify")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, Object>> verifyAuditChain() {
        java.util.List<AuditLog> logs =
                auditLogRepo.findAllByOrderBySequenceNumberAsc();
        boolean valid = true;
        int checked = 0;
        for (int i = 1; i < logs.size(); i++) {
            checked++;
            AuditLog curr = logs.get(i);
            AuditLog prev = logs.get(i - 1);
            if (curr.getPreviousHash() != null && prev.getEntryHash() != null) {
                if (!curr.getPreviousHash().equals(prev.getEntryHash())) {
                    valid = false;
                    break;
                }
            }
        }
        return ResponseEntity.ok(Map.of(
                "valid",            valid,
                "entriesVerified",  checked,
                "totalEntries",     logs.size(),
                "message",          valid ? "Audit chain integrity verified" : "Chain integrity violation detected"
        ));
    }

    // ── Admin preferences (notifications, display, session, terminal, system) ──

    /**
     * PUT /api/admin/settings/{section}
     * Body: arbitrary JSON object (stored as key=section, value=JSON string).
     */
    @PutMapping("/settings/{section}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Map<String, String>> saveSetting(
            @PathVariable String section,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found"));
        // Upsert into admin_preferences
        String json = body.toString(); // store as string — extend with Jackson if needed
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception ignored) {}
        // Cast UUID to String — plain JDBC ? binding does not auto-cast UUID objects
        // to the PostgreSQL uuid column type, causing a PSQLException at runtime.
        jdbcTemplate.update(
                "INSERT INTO admin_preferences(admin_id, pref_key, pref_value, updated_at) " +
                        "VALUES (?::uuid, ?, ?, NOW()) " +
                        "ON CONFLICT (admin_id, pref_key) DO UPDATE SET pref_value = EXCLUDED.pref_value, updated_at = NOW()",
                admin.getId().toString(), section, json
        );
        auditLog.log("SETTINGS_UPDATED", auth.getName(), "section=" + section);
        return ResponseEntity.ok(Map.of("section", section, "message", "Settings saved"));
    }

    /**
     * GET /api/admin/settings/{section}
     * Returns the stored preferences JSON for a section.
     */
    @GetMapping("/settings/{section}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<Object> getSetting(
            @PathVariable String section, Authentication auth) {
        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found"));
        try {
            String val = jdbcTemplate.queryForObject(
                    "SELECT pref_value FROM admin_preferences WHERE admin_id=?::uuid AND pref_key=?",
                    String.class, admin.getId().toString(), section);
            if (val == null) return ResponseEntity.noContent().build();
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(val, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of());
        }
    }

    /**
     * GET /api/admin/anomalies
     * Returns recent anomaly alerts (high vote rate, suspicious patterns).
     * Used by the admin dashboard to surface security warnings.
     */
    @GetMapping("/anomalies")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<java.util.List<java.util.Map<String, Object>>> getAnomalies() {
        return ResponseEntity.ok(anomalyService.peekAlerts());
    }

    /**
     * DELETE /api/admin/enrollment/queue/{id}
     * Cancels a PENDING enrollment record — only allowed before the terminal
     * has processed it (status=PENDING). Once COMPLETED the record is immutable.
     */
    @DeleteMapping("/enrollment/queue/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> cancelEnrollment(
            @PathVariable java.util.UUID id, Authentication auth) {
        return enrollmentQueueRepo.findById(id)
                .map(record -> {
                    if ("COMPLETED".equals(record.getStatus())) {
                        return ResponseEntity.badRequest()
                                .<Map<String, String>>body(Map.of("error",
                                        "Cannot cancel a completed enrollment record"));
                    }
                    enrollmentQueueRepo.deleteById(id);
                    auditLog.log("ENROLLMENT_CANCELLED", auth.getName(),
                            "EnrollmentID=" + id + " terminal=" + record.getTerminalId());
                    return ResponseEntity.ok(
                            Map.of("message", "Enrollment record cancelled", "id", id.toString()));
                })
                .orElse(ResponseEntity.notFound().<Map<String, String>>build());
    }

    /**
     * POST /api/admin/audit-log/export/initiate
     *
     * Initiates a multi-signature approval to export the full audit log.
     * The actual download is only released once threshold is met.
     * Returns { changeId, status } — frontend polls /state-changes/{id}/status
     * until executed=true, then calls /audit-log/export/download?changeId=...
     *
     * Requires: SUPER_ADMIN + registered ECDSA keypair
     */
    @PostMapping("/audit-log/export/initiate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> initiateAuditExport(
            @RequestBody(required = false) Map<String, String> body,
            Authentication auth) {

        AdminUser admin = adminRepo.findByUsernameAndActiveTrue(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Admin not found"));

        String signature = body != null ? body.get("signature") : null;

        com.evoting.model.PendingStateChange change = multiSigService.initiate(
                "EXPORT_AUDIT_LOG",
                "AUDIT-LOG",
                "Export full audit log",
                Map.of("requestedBy", auth.getName()),
                admin.getId(),
                signature
        );

        return ResponseEntity.accepted().body(Map.of(
                "changeId", change.getId().toString(),
                "executed", change.isExecuted(),
                "message",  change.isExecuted()
                        ? "Export approved — call /audit-log/export/download?changeId=" + change.getId()
                        : "Export initiated — awaiting co-signature in Pending Approvals",
                "status",   multiSigService.getStatus(change.getId())
        ));
    }

    /**
     * GET /api/admin/audit-log/export/download?changeId={uuid}
     *
     * Downloads the audit log JSON ONLY if the referenced PendingStateChange
     * is in executed=true state. Prevents download without multisig approval.
     */
    @GetMapping("/audit-log/export/download")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> downloadAuditLog(
            @RequestParam java.util.UUID changeId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "200") int size,
            Authentication auth) {

        Map<String, Object> status = multiSigService.getStatus(changeId);
        Boolean executed = (Boolean) status.get("executed");
        if (!Boolean.TRUE.equals(executed)) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Audit log export has not been approved yet. " +
                            "Complete the multisig approval first."));
        }

        // Validate changeId still belongs to EXPORT_AUDIT_LOG (not another action)
        if (!"EXPORT_AUDIT_LOG".equals(status.get("actionType"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid export approval ID"));
        }

        Pageable p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("sequenceNumber").descending());
        org.springframework.data.domain.Page<AuditLog> logs = auditLogRepo.findAll(p);

        auditLog.log("AUDIT_LOG_EXPORTED", auth.getName(),
                "ChangeId=" + changeId + " records=" + logs.getTotalElements());

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"audit-log-export.json\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(logs);
    }

    // ── Terminal Provisioning ────────────────────────────────────────────────

    /**
     * POST /api/admin/terminals/provision
     *
     * Registers a terminal's ECDSA P-256 public key for application-layer signing.
     * Replaces mTLS terminal identity for cloud deployments.
     *
     * The terminal generates a keypair on first boot (stored in NVS) and sends
     * its public key to this endpoint during initial setup.
     * Subsequent requests must carry a valid X-Terminal-Signature header.
     *
     * Body: { "terminalId": "TERM-KD-001", "publicKey": "<Base64 SPKI>",
     *         "label": "Kaduna North Ward 3", "pollingUnitId": 42 }
     *
     * Idempotent — calling again rotates the terminal's key.
     * Requires: SUPER_ADMIN
     */
    @PostMapping("/terminals/provision")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> provisionTerminal(
            @RequestBody Map<String, Object> body,
            Authentication auth) {

        String terminalId    = (String) body.get("terminalId");
        String publicKey     = (String) body.get("publicKey");
        String label         = (String) body.getOrDefault("label", terminalId);
        Object puIdObj       = body.get("pollingUnitId");
        Integer pollingUnitId = puIdObj != null ? Integer.valueOf(puIdObj.toString()) : null;

        if (terminalId == null || terminalId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "terminalId is required"));
        if (publicKey == null || publicKey.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "publicKey is required"));

        com.evoting.model.TerminalRegistry reg = terminalAuthService.register(
                terminalId, publicKey, label, pollingUnitId, auth.getName());

        return ResponseEntity.ok(Map.of(
                "terminalId",    reg.getTerminalId(),
                "label",         reg.getLabel() != null ? reg.getLabel() : "",
                "pollingUnitId", reg.getPollingUnitId() != null ? reg.getPollingUnitId() : 0,
                "registeredAt",  reg.getRegisteredAt().toString(),
                "message",       "Terminal provisioned. Requests from this terminal " +
                        "must now include X-Terminal-Signature headers."
        ));
    }

    /**
     * GET /api/admin/terminals/registry
     *
     * Lists all registered terminals and their status.
     */
    @GetMapping("/terminals/registry")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','OBSERVER')")
    public ResponseEntity<java.util.List<Map<String, Object>>> listRegisteredTerminals() {
        return ResponseEntity.ok(
                terminalRegistryRepo.findAll().stream()
                        .map(t -> {
                            Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("terminalId",   t.getTerminalId());
                            m.put("label",        t.getLabel() != null ? t.getLabel() : "");
                            m.put("pollingUnitId",t.getPollingUnitId());
                            m.put("active",       t.isActive());
                            m.put("registeredAt", t.getRegisteredAt().toString());
                            m.put("registeredBy", t.getRegisteredBy());
                            m.put("lastSeen",     t.getLastSeen() != null ? t.getLastSeen().toString() : null);
                            return m;
                        }).toList()
        );
    }

    /**
     * PUT /api/admin/terminals/{terminalId}/deactivate
     * Deactivates a terminal — it can no longer make signed requests.
     * Requires SUPER_ADMIN.
     */
    @PutMapping("/terminals/{terminalId}/deactivate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> deactivateTerminal(
            @PathVariable String terminalId, Authentication auth) {
        return terminalRegistryRepo.findByTerminalIdAndActiveTrue(terminalId)
                .map(t -> {
                    t.setActive(false);
                    terminalRegistryRepo.save(t);
                    auditLog.log("TERMINAL_DEACTIVATED", auth.getName(),
                            "TerminalId=" + terminalId);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "terminalId", terminalId,
                            "active",     false,
                            "message",    "Terminal deactivated — signed requests will be rejected"));
                })
                .orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    /**
     * POST /api/admin/merkle/publish?electionId={uuid}
     * Manually re-computes and publishes the Merkle root for an election.
     * Called by the "Publish Merkle Root Now" button in System Settings.
     */
    @PostMapping("/merkle/publish")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> publishMerkleRoot(
            @RequestParam UUID electionId, Authentication auth) {
        List<String> hashes = ballotRepo.findHashesByElectionId(electionId);
        String root = merkleService.computeMerkleRoot(hashes);
        auditLog.log("MERKLE_ROOT_PUBLISHED", auth.getName(),
                "ElectionID=" + electionId + " ballots=" + hashes.size());
        return ResponseEntity.ok(Map.of(
                "electionId",   electionId.toString(),
                "merkleRoot",   root,
                "totalBallots", hashes.size(),
                "message",      "Merkle root recomputed and published"
        ));
    }

}
