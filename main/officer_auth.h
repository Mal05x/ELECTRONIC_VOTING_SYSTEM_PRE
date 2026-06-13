#pragma once

/**
 * officer_auth.h — Polling Officer PIN Gate
 * ==========================================
 * Protects Enrollment Mode, Settings Mode, and Voting Mode activation
 * from unauthorised physical access to the terminal.
 *
 * Architecture:
 *   - 6-digit PIN (10^6 combinations vs voter's 4-digit 10^4)
 *   - Stored as SHA-256 hash in NVS namespace "officer_auth", key "pin_hash"
 *   - Fail counter in NVS key "fail_count" (uint8_t)
 *   - Lockout timestamp in NVS key "lock_until" (int64_t, esp_timer µs)
 *   - 3 consecutive failures → 300-second lockout, persists across reboots
 *
 * First-Boot:
 *   officer_auth_is_set() returns false when no PIN is provisioned.
 *   handle_home() must call officer_auth_setup() before granting any
 *   protected mode access for the first time.
 */

#include <stdbool.h>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

/** Returns true if an officer PIN hash exists in NVS. */
bool officer_auth_is_set(void);

/**
 * officer_auth_prompt
 *   Blocks until the officer enters the correct 6-digit PIN, presses
 *   BACK to cancel, or exhausts their attempts and triggers a lockout.
 *
 *   mode_name : short label shown on screen ("ENROLLMENT", "SETTINGS",
 *               "VOTING"), must be <= 12 chars.
 *
 *   Returns true on successful PIN verification.
 *   Returns false on BACK (cancel), lockout, or 3 consecutive failures.
 */
bool officer_auth_prompt(const char *mode_name);

/**
 * officer_auth_setup
 *   First-time provisioning: prompts the officer to enter a 6-digit PIN
 *   twice for confirmation, then stores the SHA-256 hash in NVS.
 *   Returns true if the PIN was provisioned successfully.
 *   Returns false if the two entries did not match or BACK was pressed.
 */
bool officer_auth_setup(void);

// Called by net_fetch_officer_pin_hash() in network.cpp
// after successfully receiving the hash from the backend.
bool officer_auth_store_fetched_hash(const uint8_t hash[32]);
/**
 * officer_auth_change
 *   Rotates the PIN: verifies the current PIN, then runs the setup flow.
 *   Called from the Settings menu PIN-change option.
 *   Returns true if the PIN was changed successfully.
 */
bool officer_auth_change(void);

/**
 * officer_auth_reset_fails
 *   Clears the fail counter and lockout timestamp from NVS.
 *   Call on successful PIN entry (already called internally by
 *   officer_auth_prompt on success).
 */
void officer_auth_reset_fails(void);

#ifdef __cplusplus
}
#endif
