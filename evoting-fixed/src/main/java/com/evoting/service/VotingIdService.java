package com.evoting.service;

import com.evoting.model.PollingUnit;
import com.evoting.repository.PollingUnitVoterSeqRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates INEC-style Voting IDs: {StateCode}/{LGA2d}/{PU3d}/{Seq4d}
 * e.g. KD/01/003/0042
 *
 * Fix B-09: Replaced JVM-local ConcurrentHashMap counter with a DB-level
 * atomic INSERT ... ON CONFLICT DO UPDATE ... RETURNING sequence.
 *
 * The previous implementation broke in multi-pod Kubernetes deployments —
 * each pod had its own counter, leading to duplicate Voting IDs when two pods
 * registered voters at the same polling unit concurrently.
 *
 * The DB-level increment is:
 *   INSERT INTO polling_unit_voter_seq (polling_unit_id, next_val) VALUES (?, 1)
 *   ON CONFLICT (polling_unit_id) DO UPDATE SET next_val = next_val + 1
 *   RETURNING next_val;
 * PostgreSQL executes this atomically — safe across any number of application instances.
 */
@Service
public class VotingIdService {

    @Autowired private PollingUnitVoterSeqRepository seqRepo;

    /**
     * Atomically increments the per-polling-unit counter in the DB and returns
     * the next unique INEC-style Voting ID for this polling unit.
     *
     * Thread-safe and multi-instance-safe — no JVM synchronisation needed.
     */
    @Transactional
    public String generate(PollingUnit pu) {
        String stateCode = pu.getLga().getState().getCode();
        int    lgaSeq    = pu.getLga().getId() % 100;
        int    puSeq     = (int)(pu.getId() % 1000);

        // Atomic DB-level increment — safe across all application instances
        Long seq = seqRepo.incrementAndGet(pu.getId());
        if (seq == null) seq = 1L;

        return String.format("%s/%02d/%03d/%04d", stateCode, lgaSeq, puSeq, seq);
    }
}
