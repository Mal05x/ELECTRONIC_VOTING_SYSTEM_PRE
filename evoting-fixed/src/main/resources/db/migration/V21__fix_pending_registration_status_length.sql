-- V21: Fix pending_registrations.status column
--
-- Bug: V8 migration defined status as VARCHAR(20), but the initial value
-- 'AWAITING_DEMOGRAPHICS' is 21 characters. Every terminal-initiated
-- registration fails with "value too long for type character varying(20)".
--
-- All status values and their lengths:
--   AWAITING_DEMOGRAPHICS  = 21 chars  ← too long for VARCHAR(20)
--   COMMITTED              = 9 chars
--   EXPIRED                = 7 chars
--   CANCELLED              = 9 chars

ALTER TABLE pending_registrations
    ALTER COLUMN status TYPE VARCHAR(30);
