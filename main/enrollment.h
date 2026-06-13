#pragma once
#include <stdbool.h>

/*
 * enrollment — INEC staff-facing card personalization state machine.
 *
 * Implements the full enrollment flow:
 *   1. Wait for blank (uninitialized) card on reader
 *   2. Fetch enrollment record from backend
 *   3. Voter chooses a 4-digit PIN (entered twice to confirm)
 *   4. Capture fingerprint from R307
 *   5. Build 596-byte INS_PERSONALIZE APDU and send to card
 *   6. Store cardStaticKey in NVS for future voting sessions
 *   7. POST completion to backend
 *
 * Entry point: enroll_task() — pinned to Core 1 in MODE_ENROLLMENT.
 * State is driven by g_state (STATE_ENROLL_*) and g_enroll_session.
 */

// Run once from app_main in MODE_ENROLLMENT to start the enrollment task.
void enroll_start_task(void);
