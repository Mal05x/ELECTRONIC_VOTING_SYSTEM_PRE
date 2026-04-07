-- ============================================================
-- V14: Password reset tokens + EXPORT_AUDIT_LOG multisig action
-- ============================================================

-- ── 1. Password reset tokens ──────────────────────────────────────────────
CREATE TABLE password_reset_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID        NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    token       VARCHAR(128) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '30 minutes',
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prt_token   ON password_reset_tokens(token) WHERE used = FALSE;
CREATE INDEX idx_prt_admin   ON password_reset_tokens(admin_id);
CREATE INDEX idx_prt_expires ON password_reset_tokens(expires_at) WHERE used = FALSE;

COMMENT ON TABLE password_reset_tokens IS
    'One-time email password reset tokens. Expire after 30 min. Single-use.';

-- ── 2. Add EXPORT_AUDIT_LOG to pending_state_changes constraint ──────────
--  (V13 added REMOVE_CANDIDATE; now add EXPORT_AUDIT_LOG for multisig gating)
ALTER TABLE pending_state_changes
    DROP CONSTRAINT IF EXISTS pending_state_changes_action_type_check;

ALTER TABLE pending_state_changes
    ADD CONSTRAINT pending_state_changes_action_type_check
    CHECK (action_type IN (
        'ACTIVATE_ELECTION',
        'CLOSE_ELECTION',
        'BULK_UNLOCK_CARDS',
        'DEACTIVATE_ADMIN',
        'ACTIVATE_ADMIN',
        'PUBLISH_MERKLE_ROOT',
        'REMOVE_CANDIDATE',
        'EXPORT_AUDIT_LOG'
    ));
