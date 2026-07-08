-- V27__add_election_target_state.sql
--
-- Minimal, deliberately narrow fix: GUBERNATORIAL / SENATORIAL /
-- STATE_ASSEMBLY elections currently have no way to say which state they
-- belong to, so a voter registered in ANY state can vote in an election
-- meant for a different one — the candidate list and /tap flow don't check.
--
-- This adds ONE nullable column. Nullable is deliberate:
--   - Existing PRESIDENTIAL elections (and any already-created test
--     elections) keep target_state_id = NULL and are completely unaffected
--     — the eligibility check in TerminalController only runs when this is
--     set AND type != 'PRESIDENTIAL'.
--   - You opt individual elections into scoping by setting this field when
--     creating them, rather than this migration silently changing behavior
--     for anything that already exists.
--
-- Deliberately NOT included here (out of scope for this pass, same
-- reasoning as target_state_id being state-only): LGA-level scoping for
-- LOCAL_GOVERNMENT elections. That would need a target_lga_id column and
-- the equivalent check keyed on voter LGA instead of state. Flagged as a
-- known follow-up, not implemented under this migration.
ALTER TABLE elections
    ADD COLUMN IF NOT EXISTS target_state_id INTEGER REFERENCES states(id);

CREATE INDEX IF NOT EXISTS idx_elections_target_state
    ON elections(target_state_id);
