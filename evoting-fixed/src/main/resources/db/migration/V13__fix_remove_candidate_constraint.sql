-- ============================================================
-- V13: Add REMOVE_CANDIDATE to pending_state_changes action_type constraint
--
-- Bug: V9 migration defined a CHECK constraint on action_type that omitted
-- 'REMOVE_CANDIDATE'. The MultiSigService and AdminController both use this
-- action type, causing a PostgreSQL CHECK violation on any candidate removal.
--
-- Fix: Drop and recreate the constraint to include REMOVE_CANDIDATE.
-- ============================================================

-- Drop the old constraint (name matches PostgreSQL's auto-generated convention)
ALTER TABLE pending_state_changes
    DROP CONSTRAINT IF EXISTS pending_state_changes_action_type_check;

-- Recreate with REMOVE_CANDIDATE included
ALTER TABLE pending_state_changes
    ADD CONSTRAINT pending_state_changes_action_type_check
    CHECK (action_type IN (
        'ACTIVATE_ELECTION',
        'CLOSE_ELECTION',
        'BULK_UNLOCK_CARDS',
        'DEACTIVATE_ADMIN',
        'ACTIVATE_ADMIN',
        'PUBLISH_MERKLE_ROOT',
        'REMOVE_CANDIDATE'
    ));
