-- V23__add_card_id_hash_to_voting_session.sql
-- Adds card_id_hash to voting_sessions (BUG-4 fix).
-- Binds each VotingSession to a specific voter card at tap time,
-- enabling cross-verification in VoteProcessingService.processVote().

ALTER TABLE voting_sessions
    ADD COLUMN IF NOT EXISTS card_id_hash VARCHAR(64);

-- Fast fraud-detection lookup: "has this card tapped for this election?"
CREATE INDEX IF NOT EXISTS idx_vs_card_election
    ON voting_sessions(card_id_hash, election_id)
    WHERE card_id_hash IS NOT NULL;

COMMENT ON COLUMN voting_sessions.card_id_hash IS
    'Colon-hex card UID hash. Populated at POST /api/terminal/tap. '
    'Cross-checked against VotePacketDTO.cardIdHash in processVote() '
    'to prevent session-token/card substitution attacks.';
