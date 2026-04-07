package com.evoting.service;

import com.evoting.model.Election;
import com.evoting.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ElectionExecutionService — executes election state changes
 * after multi-sig threshold is met. Called by MultiSigService.
 */
@Service @Slf4j
public class ElectionExecutionService {

    @Autowired private ElectionRepository      electionRepo;
    @Autowired private VoterRegistryRepository  voterRepo;
    @Autowired private BallotBoxRepository      ballotRepo;
    @Autowired private AuditLogService          auditLog;

    @Transactional
    public void activateElection(UUID id) {
        Election e = electionRepo.findById(id).orElseThrow();
        // PATCH-2: Guard — prevents replayed/stale multisig from reopening a closed election
        if (e.getStatus() != Election.ElectionStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot activate election '" + e.getName() + "': expected PENDING, got " + e.getStatus());
        }
        e.setStatus(Election.ElectionStatus.ACTIVE);
        electionRepo.save(e);
        auditLog.log("ELECTION_ACTIVATED", "SYSTEM[MULTISIG]", e.getName());
        log.info("[ELECTION] Activated: {}", e.getName());
    }

    @Transactional
    public void closeElection(UUID id) {
        Election e = electionRepo.findById(id).orElseThrow();
        // PATCH-2: Guard — prevents closing an already-closed or still-pending election
        if (e.getStatus() != Election.ElectionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot close election '" + e.getName() + "': expected ACTIVE, got " + e.getStatus());
        }
        e.setStatus(Election.ElectionStatus.CLOSED);
        electionRepo.save(e);
        voterRepo.unlockAllCardsForElection(id);
        auditLog.log("ELECTION_CLOSED", "SYSTEM[MULTISIG]", e.getName());
        log.info("[ELECTION] Closed: {}", e.getName());
    }

    @Transactional
    public void bulkUnlockCards(UUID electionId) {
        int unlocked = voterRepo.unlockAllCardsForElection(electionId);
        auditLog.log("CARDS_BULK_UNLOCKED", "SYSTEM[MULTISIG]",
                "ElectionId=" + electionId + " Count=" + unlocked);
        log.info("[CARDS] Bulk unlocked {} cards for election {}", unlocked, electionId);
    }

    @Transactional
    public void publishMerkle(UUID electionId) {
        List<String> hashes = ballotRepo.findHashesByElectionId(electionId);
        if (hashes == null || hashes.isEmpty()) {
            log.warn("[MERKLE] No votes to compute root for election {}", electionId);
            return;
        }

        // Compute Merkle root via repeated SHA-256 pairwise hashing
        List<String> layer = new ArrayList<>(hashes);
        while (layer.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                String left  = layer.get(i);
                String right = (i + 1 < layer.size()) ? layer.get(i + 1) : left;
                next.add(sha256(left + right));
            }
            layer = next;
        }
        String merkleRoot = layer.get(0);

        auditLog.log("MERKLE_ROOT_PUBLISHED", "SYSTEM[MULTISIG]",
                "ElectionId=" + electionId +
                        " Root=" + merkleRoot +
                        " Ballots=" + hashes.size());

        log.info("[MERKLE] Root={} for {} ballots in election {}",
                merkleRoot, hashes.size(), electionId);
    }

    private String sha256(String input) {
        try {
            MessageDigest md   = MessageDigest.getInstance("SHA-256");
            byte[]        hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb   = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
