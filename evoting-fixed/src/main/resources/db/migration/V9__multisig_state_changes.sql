-- ============================================================
-- V9: Cryptographic multi-signature for state changes
--
-- Architecture:
--   - Each SUPER_ADMIN registers an ECDSA P-256 public key
--   - Sensitive state changes create a pending_state_changes record
--   - 2-of-N SUPER_ADMINs must sign the challenge within 24 hours
--   - Backend verifies signatures before executing the action
--   - First SUPER_ADMIN bypasses threshold if only one exists (bootstrap)
--
-- Multi-sig required actions:
--   ACTIVATE_ELECTION, CLOSE_ELECTION, BULK_UNLOCK_CARDS,
--   DEACTIVATE_ADMIN, ACTIVATE_ADMIN, PUBLISH_MERKLE_ROOT
-- ============================================================

-- ── 1. Admin ECDSA public keys ───────────────────────────────
CREATE TABLE admin_keypairs (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID        NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    public_key  TEXT        NOT NULL,   -- Base64-encoded ECDSA P-256 SubjectPublicKeyInfo
    algorithm   VARCHAR(20) NOT NULL DEFAULT 'ECDSA-P256',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (admin_id)
);

CREATE INDEX idx_keypair_admin ON admin_keypairs(admin_id);

COMMENT ON TABLE admin_keypairs IS
    'ECDSA P-256 public keys registered by SUPER_ADMINs. '
    'Private key is generated and stored in browser Web Crypto API. '
    'Never stored server-side.';

-- ── 2. Pending state changes ─────────────────────────────────
CREATE TABLE pending_state_changes (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type     VARCHAR(50) NOT NULL
                        CHECK (action_type IN (
                            'ACTIVATE_ELECTION',
                            'CLOSE_ELECTION',
                            'BULK_UNLOCK_CARDS',
                            'DEACTIVATE_ADMIN',
                            'ACTIVATE_ADMIN',
                            'PUBLISH_MERKLE_ROOT'
                        )),
    target_id       VARCHAR(255) NOT NULL,   -- UUID of election/admin/etc
    target_label    VARCHAR(255),            -- human-readable description
    payload         JSONB,                   -- extra params if needed
    initiated_by    UUID        NOT NULL REFERENCES admin_users(id),
    signatures_required INTEGER NOT NULL DEFAULT 2,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    executed        BOOLEAN     NOT NULL DEFAULT FALSE,
    executed_at     TIMESTAMPTZ,
    cancelled       BOOLEAN     NOT NULL DEFAULT FALSE,
    cancelled_by    UUID        REFERENCES admin_users(id),
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   TEXT
);

CREATE INDEX idx_psc_executed   ON pending_state_changes(executed, cancelled, expires_at);
CREATE INDEX idx_psc_action     ON pending_state_changes(action_type, executed);
CREATE INDEX idx_psc_initiated  ON pending_state_changes(initiated_by);

COMMENT ON TABLE pending_state_changes IS
    'Sensitive actions that require 2-of-N SUPER_ADMIN signatures before execution. '
    'Records expire after 24 hours. Bootstrap exception: if only 1 SUPER_ADMIN exists, '
    'threshold is reduced to 1 automatically.';

-- ── 3. Signatures ────────────────────────────────────────────
CREATE TABLE state_change_signatures (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    change_id   UUID        NOT NULL REFERENCES pending_state_changes(id) ON DELETE CASCADE,
    admin_id    UUID        NOT NULL REFERENCES admin_users(id),
    signature   TEXT        NOT NULL,   -- Base64 ECDSA signature of change_id (the UUID string)
    signed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (change_id, admin_id)        -- one signature per admin per change
);

CREATE INDEX idx_sig_change  ON state_change_signatures(change_id);
CREATE INDEX idx_sig_admin   ON state_change_signatures(admin_id);

COMMENT ON TABLE state_change_signatures IS
    'ECDSA signatures from SUPER_ADMINs approving a pending state change. '
    'The signed payload is the change UUID as a UTF-8 string. '
    'Backend verifies each signature against the admin''s registered public key.';

-- ── 4. Notification to co-signers ────────────────────────────
-- When a state change is initiated, all other SUPER_ADMINs get a
-- notification. This table tracks who has been notified.
CREATE TABLE state_change_notifications (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    change_id   UUID        NOT NULL REFERENCES pending_state_changes(id) ON DELETE CASCADE,
    admin_id    UUID        NOT NULL REFERENCES admin_users(id),
    notified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at     TIMESTAMPTZ,
    UNIQUE (change_id, admin_id)
);

COMMENT ON TABLE state_change_notifications IS
    'Tracks which SUPER_ADMINs have been notified about a pending state change '
    'requiring their co-signature.';
