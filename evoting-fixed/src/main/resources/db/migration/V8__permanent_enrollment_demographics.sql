-- ============================================================
-- V8: Permanent enrollment + encrypted demographics
--
-- Changes:
--   1. voter_demographics  — encrypted name/dob/gender at rest
--                            via pgcrypto, decryption is audit-logged
--   2. voter_registry      — add enrolled flag + remove per-election
--                            dependency (election_id made nullable for
--                            permanent identity records)
--   3. enrollment_queue    — remove election_id FK (enrollment is now
--                            permanent, not per-election)
--   4. pending_registrations — terminal-initiated registration flow:
--                            terminal posts card UID + pubkey first,
--                            admin commits demographics second
-- ============================================================

-- ── 1. Encrypted voter demographics ─────────────────────────
-- pgcrypto is already enabled in V1.
-- encrypted_data: pgp_sym_encrypt output (AES-256)
-- The encryption key is the DEMOGRAPHICS_KEY env var, never stored in DB.
-- iv column is not needed for pgp_sym_encrypt (it embeds its own IV).

CREATE TABLE voter_demographics (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    voter_id       UUID        NOT NULL REFERENCES voter_registry(id) ON DELETE CASCADE,
    encrypted_data TEXT        NOT NULL,  -- pgp_sym_encrypt(json, key)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_accessed_by  VARCHAR(100),       -- username of last decryptor
    last_accessed_at  TIMESTAMPTZ,        -- when demographics were last decrypted
    access_count      INTEGER   NOT NULL DEFAULT 0,
    UNIQUE (voter_id)
);
REVOKE UPDATE, DELETE ON voter_demographics FROM PUBLIC;

CREATE INDEX idx_voter_demographics_voter ON voter_demographics(voter_id);

COMMENT ON TABLE voter_demographics IS
    'Voter name/surname/DOB/gender encrypted at rest via pgp_sym_encrypt. '
    'Every decryption is logged in audit_log with actor and timestamp. '
    'The encryption key (DEMOGRAPHICS_KEY) is never stored in the database.';

-- ── 2. Voter registry — permanent identity columns ───────────
-- election_id is kept but made nullable so the registry can store
-- identity records that pre-date or span multiple elections.
-- For backward compat, existing rows keep their election_id.
-- New permanent registrations set election_id = NULL.

ALTER TABLE voter_registry
    ALTER COLUMN election_id DROP NOT NULL;

ALTER TABLE voter_registry
    ADD COLUMN IF NOT EXISTS enrolled            BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS enrolled_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS enrolled_terminal_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS first_name          VARCHAR(100),  -- plaintext only for admin search; real data in voter_demographics
    ADD COLUMN IF NOT EXISTS surname             VARCHAR(100);  -- same — stored as searchable but demographics table holds full encrypted record

COMMENT ON COLUMN voter_registry.enrolled IS
    'TRUE once the terminal has written biometrics to the JCOP4 card. '
    'A voter can vote in any election once enrolled — no re-enrollment needed.';

COMMENT ON COLUMN voter_registry.election_id IS
    'Nullable. Legacy rows have an election_id. New permanent identity '
    'registrations have election_id = NULL and are eligible for any election '
    'whose geographic scope includes their polling unit.';

-- ── 3. Enrollment queue — remove election dependency ─────────
-- election_id was a NOT NULL FK; we keep the column but make it nullable
-- so existing completed records are preserved.
-- New pending enrollments will have election_id = NULL.

ALTER TABLE enrollment_queue
    ALTER COLUMN election_id DROP NOT NULL;

COMMENT ON COLUMN enrollment_queue.election_id IS
    'Nullable as of V8. Enrollment is now permanent per voter, not per election. '
    'Legacy completed rows retain their election_id.';

-- ── 4. Pending registrations — terminal-initiated flow ───────
-- Step 1: Terminal reads card UID + public key, POSTs here.
-- Step 2: Admin fills demographics in dashboard, commits record.
-- Step 3: Backend creates voter_registry + voter_demographics atomically.

CREATE TABLE pending_registrations (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    terminal_id       VARCHAR(64) NOT NULL,
    polling_unit_id   BIGINT      NOT NULL REFERENCES polling_units(id),
    card_id_hash      VARCHAR(64) NOT NULL UNIQUE,
    voter_public_key  TEXT        NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'AWAITING_DEMOGRAPHICS'
                          CHECK (status IN ('AWAITING_DEMOGRAPHICS','COMMITTED','EXPIRED','CANCELLED')),
    initiated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '4 hours',
    committed_by      VARCHAR(100),   -- admin username who committed
    committed_at      TIMESTAMPTZ,
    voter_registry_id UUID        REFERENCES voter_registry(id)  -- set on commit
);

CREATE INDEX idx_pending_reg_terminal  ON pending_registrations(terminal_id, status);
CREATE INDEX idx_pending_reg_card      ON pending_registrations(card_id_hash);
CREATE INDEX idx_pending_reg_status    ON pending_registrations(status, expires_at);

COMMENT ON TABLE pending_registrations IS
    'Terminal-initiated registration records. The terminal reads the JCOP4 card '
    'and creates a pending record. The admin then fills in demographics to commit. '
    'Records expire after 4 hours if not committed.';

-- ── 5. Ballot eligibility — geographic scope ─────────────────
-- Replaces per-election voter_registry entries.
-- A voter is eligible for election E if their polling unit is
-- within the election geographic scope OR if the election has
-- scope_type = NATIONAL (all enrolled voters eligible).

CREATE TABLE election_eligibility_scope (
    id          UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    election_id UUID    NOT NULL REFERENCES elections(id) ON DELETE CASCADE,
    scope_type  VARCHAR(20) NOT NULL DEFAULT 'NATIONAL'
                    CHECK (scope_type IN ('NATIONAL','STATE','LGA')),
    state_id    INTEGER REFERENCES states(id),
    lga_id      INTEGER REFERENCES lgas(id)
);

CREATE INDEX idx_eligibility_election ON election_eligibility_scope(election_id);

-- Seed all existing elections as NATIONAL scope
INSERT INTO election_eligibility_scope (election_id, scope_type)
SELECT id, 'NATIONAL' FROM elections
ON CONFLICT DO NOTHING;

COMMENT ON TABLE election_eligibility_scope IS
    'Defines which geographic area is eligible for an election. '
    'NATIONAL: all enrolled voters. STATE: voters in a specific state. '
    'LGA: voters in a specific LGA. Multiple rows = union of scopes.';
