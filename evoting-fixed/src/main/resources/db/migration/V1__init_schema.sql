-- ============================================================
-- V1: Core tables
-- ============================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE elections (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','CLOSED')),
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE candidates (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    election_id UUID        NOT NULL REFERENCES elections(id),
    full_name   VARCHAR(255) NOT NULL,
    party       VARCHAR(100),
    position    VARCHAR(100) NOT NULL,
    photo_hash  VARCHAR(64),
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE voter_registry (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    election_id           UUID        NOT NULL REFERENCES elections(id),
    card_id_hash          VARCHAR(64) NOT NULL UNIQUE,
    voter_public_key      TEXT        NOT NULL,
    encrypted_demographic TEXT,
    has_voted             BOOLEAN     NOT NULL DEFAULT FALSE,
    registration_at       TIMESTAMPTZ DEFAULT NOW()
);
ALTER TABLE voter_registry ENABLE ROW LEVEL SECURITY;

CREATE TABLE ballot_box (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    election_id        UUID        NOT NULL REFERENCES elections(id),
    candidate_id       UUID        NOT NULL REFERENCES candidates(id),
    vote_hash          VARCHAR(64) NOT NULL UNIQUE,
    transaction_id     VARCHAR(16) NOT NULL UNIQUE,
    session_token_hash VARCHAR(64) NOT NULL UNIQUE,
    terminal_id        VARCHAR(64),
    cast_at            TIMESTAMPTZ DEFAULT NOW()
);
REVOKE UPDATE, DELETE ON ballot_box FROM PUBLIC;

CREATE TABLE audit_log (
    id              BIGSERIAL   PRIMARY KEY,
    sequence_number BIGINT      NOT NULL UNIQUE,
    event_type      VARCHAR(50) NOT NULL,
    actor           VARCHAR(100) NOT NULL,
    payload_hash    VARCHAR(64) NOT NULL,
    previous_hash   VARCHAR(64) NOT NULL,
    entry_hash      VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
REVOKE UPDATE, DELETE ON audit_log FROM PUBLIC;

CREATE TABLE admin_users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(100) NOT NULL UNIQUE,
    email         VARCHAR(255) UNIQUE, -- ADDED THIS LINE
    display_name  VARCHAR(150),
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'OBSERVER' CHECK (role IN ('SUPER_ADMIN','ADMIN','OBSERVER')),
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    last_login    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE voting_sessions (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_token_hash VARCHAR(64) NOT NULL UNIQUE,
    election_id        UUID        NOT NULL REFERENCES elections(id),
    terminal_id        VARCHAR(64) NOT NULL,
    polling_unit_id    BIGINT,
    state_id           INTEGER,
    lga_id             INTEGER,
    is_used            BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE terminal_heartbeats (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    terminal_id   VARCHAR(64) NOT NULL,
    battery_level SMALLINT    CHECK (battery_level BETWEEN 0 AND 100),
    tamper_flag   BOOLEAN     NOT NULL DEFAULT FALSE,
    ip_address    VARCHAR(45),
    reported_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_voter_card    ON voter_registry(card_id_hash);
CREATE INDEX idx_auditlog_seq  ON audit_log(sequence_number);
CREATE INDEX idx_session_token ON voting_sessions(session_token_hash, is_used);
CREATE INDEX idx_hb_terminal   ON terminal_heartbeats(terminal_id, reported_at DESC);
CREATE INDEX idx_candidate_el  ON candidates(election_id);

INSERT INTO admin_users (username, display_name, password_hash, role, is_active)
VALUES ('superadmin', 'System Administrator', '$2a$12$E1sCH6q./3AACK469PoPF.DRNu38Fb2niZFhR1gyk9cazHinC3P5a','SUPER_ADMIN',TRUE);