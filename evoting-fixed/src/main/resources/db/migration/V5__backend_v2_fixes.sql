-- ============================================================
-- V5: Backend v2.0 fixes
-- B-01: Enrollment queue for terminal workflow
-- B-05: Per-card SCP03 static key hash
-- B-09: DB-level polling unit voter sequence
-- ============================================================

-- B-05: Per-card static key hash (SHA-256 stored, never the raw key)
ALTER TABLE voter_registry
    ADD COLUMN IF NOT EXISTS card_static_key_hash CHAR(64);

-- B-09: Atomic cross-instance voter sequence counter
CREATE TABLE IF NOT EXISTS polling_unit_voter_seq (
    polling_unit_id BIGINT PRIMARY KEY,
    next_val        BIGINT NOT NULL DEFAULT 0
);

-- B-01: Enrollment queue — admin pushes records, terminal pulls them
CREATE TABLE IF NOT EXISTS enrollment_queue (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    terminal_id           VARCHAR(64)  NOT NULL,
    election_id           UUID         NOT NULL REFERENCES elections(id),
    polling_unit_id       BIGINT       NOT NULL REFERENCES polling_units(id),
    voter_public_key      TEXT         NOT NULL,
    encrypted_demographic TEXT,
    -- Base64-encoded 16-byte per-card static key for SCP03 personalize APDU
    -- Stored only until the terminal confirms card write; then zeroed by cleanup job
    card_static_key       TEXT         NOT NULL,
    -- SHA-256 of card_static_key stored in voter_registry after completion
    card_static_key_hash  CHAR(64),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    card_id_hash          VARCHAR(64),     -- filled by terminal on completion
    voting_id             VARCHAR(20),     -- filled on completion
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_enroll_terminal_status
    ON enrollment_queue(terminal_id, status);
CREATE INDEX IF NOT EXISTS idx_enroll_election
    ON enrollment_queue(election_id, status);
