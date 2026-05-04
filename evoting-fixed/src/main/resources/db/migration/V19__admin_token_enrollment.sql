-- ============================================================
-- V19: Admin token hash for card decommissioning
--
-- The applet's INS_LOCK_CARD requires the caller to supply a
-- 32-byte admin token.  The applet SHA-256-hashes it and
-- compares against adminTokenHash stored during INS_PERSONALIZE.
--
-- The raw token is generated here on enrollment queue creation,
-- returned ONCE to the SUPER_ADMIN, and never stored.
-- Only SHA-256(rawToken) is persisted so that even a full DB
-- compromise cannot reconstruct the token.
--
-- The terminal receives SHA-256(rawToken) via
-- GET /api/terminal/pending_enrollment and writes it into
-- bytes [564..595] of the 596-byte INS_PERSONALIZE APDU.
-- ============================================================

ALTER TABLE enrollment_queue
    ADD COLUMN IF NOT EXISTS admin_token_hash CHAR(64);

COMMENT ON COLUMN enrollment_queue.admin_token_hash IS
    'SHA-256 hex of the 32-byte raw admin token.  '
    'Written to the JCOP4 card during INS_PERSONALIZE.  '
    'The raw token is returned once to the SUPER_ADMIN on queue creation '
    'and never stored in the database.';
