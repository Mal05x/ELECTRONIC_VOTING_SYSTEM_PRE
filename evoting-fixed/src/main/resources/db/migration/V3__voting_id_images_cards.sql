-- ============================================================
-- V3: Voting ID, Party/Candidate images, Card management
-- ============================================================

-- ── Parties table ─────────────────────────────────────────────
CREATE TABLE parties (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(200) NOT NULL UNIQUE,
    abbreviation  VARCHAR(20)  NOT NULL UNIQUE,
    logo_s3_key   VARCHAR(500),
    logo_url      TEXT,
    founded_year  INTEGER,
    created_at    TIMESTAMPTZ  DEFAULT NOW()
);

-- ── Candidate enhancements ────────────────────────────────────
ALTER TABLE candidates
    ADD COLUMN party_id    UUID REFERENCES parties(id),
    ADD COLUMN image_s3_key VARCHAR(500),
    ADD COLUMN image_url   TEXT;

-- ── Voter Registry enhancements ───────────────────────────────
-- voting_id: INEC-style e.g. "KD/01/003/0042"
ALTER TABLE voter_registry
    ADD COLUMN voting_id   VARCHAR(20) UNIQUE,
    ADD COLUMN card_locked BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill existing rows with a placeholder voting_id (if any)
UPDATE voter_registry SET voting_id = 'PENDING-' || id::text
WHERE voting_id IS NULL;

-- Now make it NOT NULL
ALTER TABLE voter_registry ALTER COLUMN voting_id SET NOT NULL;

-- ── Card Status Log ───────────────────────────────────────────
CREATE TABLE card_status_log (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id_hash VARCHAR(64)  NOT NULL,
    election_id  UUID         NOT NULL REFERENCES elections(id),
    event_type   VARCHAR(20)  NOT NULL CHECK (event_type IN ('LOCKED','UNLOCKED','REGISTRATION')),
    triggered_by VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  DEFAULT NOW()
);
REVOKE UPDATE, DELETE ON card_status_log FROM PUBLIC;

-- ── Indexes ───────────────────────────────────────────────────
CREATE INDEX idx_voter_voting_id   ON voter_registry(voting_id);
CREATE INDEX idx_voter_locked      ON voter_registry(election_id, card_locked);
CREATE INDEX idx_card_log_hash     ON card_status_log(card_id_hash);
CREATE INDEX idx_card_log_election ON card_status_log(election_id, created_at DESC);
CREATE INDEX idx_party_abbrev      ON parties(abbreviation);
CREATE INDEX idx_candidate_party   ON candidates(party_id);

-- ── Seed major Nigerian parties ───────────────────────────────
INSERT INTO parties (name, abbreviation, founded_year) VALUES
('All Progressives Congress',              'APC',  2013),
('Peoples Democratic Party',               'PDP',  1998),
('Labour Party',                           'LP',   2002),
('All Progressives Grand Alliance',        'APGA', 2003),
('New Nigeria Peoples Party',              'NNPP', 2001),
('Social Democratic Party',               'SDP',  1989),
('Action Democratic Party',               'ADP',  2017),
('Young Progressive Party',               'YPP',  2017),
('African Democratic Congress',           'ADC',  2005),
('Accord Party',                          'A',    2006);
