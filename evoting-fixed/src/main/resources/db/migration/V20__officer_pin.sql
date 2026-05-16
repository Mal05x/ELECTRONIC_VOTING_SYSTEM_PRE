-- Add officer_pin_hash to terminal_registry.
-- Stored as lowercase hex SHA-256 (64 chars).
-- NULL until the admin sets a PIN for the terminal.

ALTER TABLE terminal_registry
    ADD COLUMN officer_pin_hash VARCHAR(64) DEFAULT NULL;

COMMENT ON COLUMN terminal_registry.officer_pin_hash IS
    'SHA-256 hex digest of the 6-digit polling officer PIN. '
    'Set by SUPER_ADMIN via PUT /api/admin/terminals/{id}/officer-pin. '
    'Fetched by the terminal over its ECDSA-authenticated channel on first boot.';