-- V25__extend_transaction_id_length.sql
-- Widens the transaction_id column to 64 characters to accommodate 
-- the full SHA-256 deterministic burn-proof hashes.

ALTER TABLE ballot_box ALTER COLUMN transaction_id TYPE VARCHAR(64);
