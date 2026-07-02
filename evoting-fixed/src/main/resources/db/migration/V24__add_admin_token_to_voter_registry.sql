-- ============================================================
-- V24: Add admin token hash to permanent voter registry
-- 
-- Transfers the SHA-256 hash of the admin token from the 
-- temporary enrollment queue to the permanent registry so 
-- cards can be securely unlocked or reset later.
-- ============================================================

ALTER TABLE voter_registry
    ADD COLUMN admin_token_hash CHAR(64);

COMMENT ON COLUMN voter_registry.admin_token_hash IS
    'SHA-256 hex of the 32-byte raw admin token. Transferred from enrollment_queue upon completion.';
