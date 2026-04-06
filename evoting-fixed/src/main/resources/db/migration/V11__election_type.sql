-- V11: Add election type column.
-- Uses DEFAULT 'PRESIDENTIAL' so existing rows are backfilled automatically,
-- avoiding the NOT NULL constraint crash that occurs when Hibernate tries to
-- add a non-nullable column to a table that already contains data.

ALTER TABLE elections
    ADD COLUMN IF NOT EXISTS type VARCHAR(30) NOT NULL DEFAULT 'PRESIDENTIAL';

-- Allow known types only
ALTER TABLE elections
    DROP CONSTRAINT IF EXISTS chk_election_type;

ALTER TABLE elections
    ADD CONSTRAINT chk_election_type CHECK (type IN (
        'PRESIDENTIAL',
        'GUBERNATORIAL',
        'SENATORIAL',
        'STATE_ASSEMBLY',
        'LOCAL_GOVERNMENT'
    ));

COMMENT ON COLUMN elections.type IS
    'Electoral scope: PRESIDENTIAL | GUBERNATORIAL | SENATORIAL | STATE_ASSEMBLY | LOCAL_GOVERNMENT';
