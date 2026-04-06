-- ============================================================
-- V4: Idempotency keys + BallotBox composite tally indexes
-- ============================================================

-- Fix 4: idempotency guard for vote retries from dropped connections
CREATE TABLE idempotency_keys (
    payload_hash   CHAR(64)    PRIMARY KEY,   -- SHA-256 of the raw AES ciphertext
    transaction_id CHAR(16)    NOT NULL,       -- receipt issued for this payload
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- TTL index — PG doesn't auto-expire rows, but the scheduled job uses this
CREATE INDEX idx_idem_created ON idempotency_keys(created_at);

-- Fix 2: composite tally indexes on ballot_box
-- (these complement the unique indexes already present from V1)
CREATE INDEX IF NOT EXISTS idx_bb_election_candidate
    ON ballot_box(election_id, candidate_id);

CREATE INDEX IF NOT EXISTS idx_bb_election_state_candidate
    ON ballot_box(election_id, state_id, candidate_id);

CREATE INDEX IF NOT EXISTS idx_bb_election_lga
    ON ballot_box(election_id, lga_id);

CREATE INDEX IF NOT EXISTS idx_bb_election_pu
    ON ballot_box(election_id, polling_unit_id);

CREATE INDEX IF NOT EXISTS idx_bb_cast_at
    ON ballot_box(election_id, cast_at DESC);

-- Fix 10: index for paginated voter queries
CREATE INDEX IF NOT EXISTS idx_voter_election_pageable
    ON voter_registry(election_id, registration_at DESC);
