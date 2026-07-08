-- V28__add_election_target_lga.sql
--
-- Extends V27's state-level scoping down one level, for LOCAL_GOVERNMENT
-- elections specifically. An LGA election genuinely is one LGA — this is a
-- clean 1:1 mapping, same shape as target_state_id.
--
-- Deliberately NOT extended to SENATORIAL / STATE_ASSEMBLY: those
-- constituencies can span multiple LGAs, and this schema has no concept of
-- "the set of LGAs that make up district X" yet. A single target_lga_id
-- would incorrectly reject real, eligible voters from the district's other
-- LGAs — worse than the current state-only enforcement for those two
-- types. Real constituency modeling (a proper Constituency entity mapping
-- to a set of LGAs or wards) is future work, not this migration.
ALTER TABLE elections
    ADD COLUMN IF NOT EXISTS target_lga_id INTEGER REFERENCES lgas(id);

CREATE INDEX IF NOT EXISTS idx_elections_target_lga
    ON elections(target_lga_id);
