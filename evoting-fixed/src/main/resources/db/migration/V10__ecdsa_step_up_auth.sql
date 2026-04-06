-- ============================================================
-- V10: ECDSA Step-Up Authentication
--
-- Before any sensitive action, the frontend requests a
-- server-generated nonce. The admin signs it with their
-- ECDSA private key. The backend verifies before executing.
--
-- Protected actions:
--   QUEUE_ENROLLMENT, COMMIT_REGISTRATION,
--   IMPORT_CANDIDATES, DELETE_CANDIDATE,
--   CREATE_ELECTION, CREATE_PARTY
--
-- Nonce TTL: 60 seconds
-- Grace period: tracked client-side (5 min per action type)
-- Replay prevention: used=true after first verification
-- ============================================================

CREATE TABLE action_challenges (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID        NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    nonce       VARCHAR(64) NOT NULL UNIQUE,
    action_type VARCHAR(50) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '60 seconds',
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at     TIMESTAMPTZ
);

CREATE INDEX idx_challenge_nonce   ON action_challenges(nonce, used);
CREATE INDEX idx_challenge_admin   ON action_challenges(admin_id, expires_at);
CREATE INDEX idx_challenge_expires ON action_challenges(expires_at) WHERE used = FALSE;

COMMENT ON TABLE action_challenges IS
    'Server-generated nonces for ECDSA step-up authentication. '
    'Each nonce is single-use and expires after 60 seconds. '
    'Used=true after successful verification to prevent replay attacks.';
