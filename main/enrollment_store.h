#pragma once
#include <stdint.h>
#include <stdbool.h>

/*
 * enrollment_store — per-card cardStaticKey NVS persistence
 *
 * During enrollment the backend provides a unique 16-byte cardStaticKey
 * per card.  The terminal writes it to the card via INS_PERSONALIZE and
 * simultaneously stores it in NVS so the voting flow can load it later
 * to derive the identical session key that the applet computes.
 *
 * Key schema
 * ──────────
 * NVS namespace : "card_keys"
 * NVS key       : first 14 hex chars of SHA-256(cardUID)
 *                 (NVS keys are limited to 15 chars)
 * NVS value     : 16-byte blob (raw cardStaticKey)
 *
 * The SHA-256 hash is used as the lookup key so the raw card UID
 * is never stored in NVS directly.
 *
 * Capacity
 * ────────
 * A 4 MB flash partition with a 24 KB NVS area holds ~500 entries.
 * For a busy polling unit that processes more than 500 voters, the
 * NVS partition size should be increased in partitions.csv.
 * Alternatively, use the card UID hash as a short index into an
 * external SD card or PSRAM lookup table (future work).
 */

#ifdef __cplusplus
extern "C" {
#endif

// Compute SHA-256(uid, uid_len) and store as 14-char hex NVS key in key_out.
// key_out must be at least 15 bytes.
void enrollment_store_make_key(const char *card_uid_str, char *key_out);

// Save cardStaticKey[16] for the given card UID string.
// Returns true on success.
bool enrollment_store_save(const char *card_uid_str,
                           const uint8_t card_static_key[16]);

// Load cardStaticKey[16] for the given card UID string into key_out.
// Returns true if found, false if not present (card was never enrolled
// on this terminal — terminal cannot serve this voter).
bool enrollment_store_load(const char *card_uid_str,
                           uint8_t key_out[16]);

// Delete the stored key for a card (call after card is locked/decommissioned).
void enrollment_store_delete(const char *card_uid_str);

#ifdef __cplusplus
}
#endif
