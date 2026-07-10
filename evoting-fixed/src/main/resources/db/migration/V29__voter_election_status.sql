-- V29__voter_election_status.sql
--
-- FIX: voter_registry.has_voted / card_locked are lifetime flags on what is
-- now (since V8) a PERMANENT, cross-election voter identity record. That
-- collapses "voted in election A" and "voted in election B" into a single
-- boolean, so a voter who cast a ballot in a closed election is permanently
-- blocked from voting in every election created afterwards.
--
-- CardManagementService.bulkUnlockForElection() was meant to be the release
-- valve on election close, but it only ever reset card_locked (never
-- has_voted) — and worse, it filters WHERE v.election_id = :electionId,
-- while V8 explicitly sets election_id = NULL for every voter enrolled
-- since. That means it silently unlocks zero rows for any current voter.
-- Same problem in CardManagementService.unlockCard()/lockCard().
--
-- This table makes "has this card voted in THIS election" its own fact,
-- independent of the voter's permanent identity row. It's keyed on
-- (election_id, card_id_hash) rather than a voter FK, deliberately mirroring
-- the existing card_status_log convention — so it works uniformly whether
-- the voter_registry row behind it is a legacy per-election row or a
-- post-V8 permanent one.
CREATE TABLE voter_election_status (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    election_id   UUID        NOT NULL REFERENCES elections(id) ON DELETE CASCADE,
    card_id_hash  VARCHAR(64) NOT NULL,
    has_voted     BOOLEAN     NOT NULL DEFAULT FALSE,
    card_locked   BOOLEAN     NOT NULL DEFAULT FALSE,
    voted_at      TIMESTAMPTZ,
    CONSTRAINT uq_voter_election UNIQUE (election_id, card_id_hash)
);

CREATE INDEX idx_voter_election_status_election ON voter_election_status(election_id);
CREATE INDEX idx_voter_election_status_card     ON voter_election_status(card_id_hash);

COMMENT ON TABLE voter_election_status IS
  'Per-(election, card) voting status — the source of truth for vote-gating '
  'as of V29. voter_registry.has_voted / card_locked are left in place as a '
  'legacy/audit trace only and are no longer read by VoteProcessingService '
  'or CardManagementService.';

-- Backfill: preserve accurate history for old-style rows that still carry a
-- concrete election_id — those genuinely mean "voter X voted in election Y"
-- and shouldn't be lost.
--
-- Deliberately NOT backfilled: post-V8 permanent rows (election_id IS NULL)
-- with has_voted = TRUE. There is no reliable way to attribute a lifetime
-- flag to one specific past election without correlating against
-- ballot_box, which is deliberately anonymized (transactionId =
-- sha256(cardBurnProof), not reversible to a voter) — that's a privacy
-- property of the system, not a gap to work around. Any election currently
-- PENDING or ACTIVE has zero real votes recorded against it under the old
-- broken gate anyway (every attempt was rejected outright), so no in-flight
-- election data is lost by skipping this.
INSERT INTO voter_election_status (election_id, card_id_hash, has_voted, card_locked, voted_at)
SELECT election_id, card_id_hash, has_voted, card_locked, NULL
FROM voter_registry
WHERE election_id IS NOT NULL
  AND (has_voted = TRUE OR card_locked = TRUE)
ON CONFLICT (election_id, card_id_hash) DO NOTHING;
