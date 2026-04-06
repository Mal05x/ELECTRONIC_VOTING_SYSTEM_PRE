package com.evoting.service;

import com.evoting.model.Candidate;
import com.evoting.repository.CandidateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * CandidateRemovalService — executes the REMOVE_CANDIDATE multi-sig action.
 *
 * Candidate deletion is routed through multi-sig because:
 *   - Removing a candidate from a live election immediately affects vote tallies.
 *   - It is irreversible — votes cast for the removed candidate become orphaned.
 *   - A single admin should not be able to unilaterally alter the ballot.
 *
 * This service is called by MultiSigService once the signature threshold is met.
 * For single-SUPER_ADMIN deployments (bootstrap mode), execution is immediate
 * after one signature, matching the same behaviour as ACTIVATE_ELECTION.
 */
@Service
@Slf4j
public class CandidateRemovalService {

    @Autowired private CandidateRepository candidateRepo;
    @Autowired private AuditLogService      auditLog;

    /**
     * Permanently removes a candidate.
     * Called by MultiSigService after signature threshold is met.
     *
     * @param candidateId UUID of the candidate to remove
     * @param actorLabel  Human-readable label for audit ("name | electionId")
     */
    @Transactional
    public void removeCandidate(UUID candidateId, String actorLabel) {
        Candidate c = candidateRepo.findById(candidateId).orElse(null);
        if (c == null) {
            log.warn("[CANDIDATE_REMOVAL] Candidate {} not found — may have been removed already",
                    candidateId);
            return;
        }

        String detail = "CandidateId=" + candidateId
                + " Name="       + c.getFullName()
                + " Party="      + (c.getParty() != null ? c.getParty() : "—")
                + " ElectionId=" + c.getElectionId()
                + " AuthorisedBy=" + actorLabel;

        candidateRepo.deleteById(candidateId);

        auditLog.log("CANDIDATE_REMOVED_MULTISIG", "SYSTEM", detail);
        log.info("[CANDIDATE_REMOVAL] Removed candidate {} ({})", candidateId, c.getFullName());
    }
}
