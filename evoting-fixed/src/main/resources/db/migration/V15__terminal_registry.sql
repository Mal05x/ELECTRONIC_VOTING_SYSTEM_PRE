-- ============================================================
-- V15: Application-layer terminal identity registry
--
-- Replaces mTLS terminal authentication for cloud deployments
-- (Render, Railway, etc.) where the TLS proxy terminates before
-- the Spring Boot application.
--
-- Each terminal generates an ECDSA P-256 keypair on first boot.
-- The admin registers the public key via POST /api/admin/terminals/provision.
-- Every subsequent terminal request must carry:
--   X-Terminal-Id:        <terminalId>
--   X-Request-Timestamp:  <unix seconds>
--   X-Terminal-Signature: <Base64 P1363 ECDSA over canonical payload>
-- ============================================================

CREATE TABLE terminal_registry (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    terminal_id     VARCHAR(64) NOT NULL UNIQUE,
    public_key      VARCHAR(256) NOT NULL,     -- Base64 SPKI ECDSA P-256
    label           VARCHAR(255),              -- Human-readable location
    polling_unit_id INTEGER     REFERENCES polling_units(id) ON DELETE SET NULL,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    registered_by   VARCHAR(100) NOT NULL,
    last_seen       TIMESTAMPTZ
);

CREATE INDEX idx_terminal_reg_id     ON terminal_registry(terminal_id) WHERE active = TRUE;
CREATE INDEX idx_terminal_reg_active ON terminal_registry(active);

COMMENT ON TABLE terminal_registry IS
    'ECDSA P-256 public keys for registered voting terminals. '
    'Used for application-layer request signing when mTLS is unavailable '
    '(cloud deployments with TLS-terminating edge proxies).';
