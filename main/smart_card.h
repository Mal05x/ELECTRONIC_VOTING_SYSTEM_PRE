#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <string>

// --- ADDED: Required for session.h to track the secure channel ---
typedef struct {
    bool established;
    bool pin_verified;
    bool fp_verified;
    uint8_t session_key[16];
    uint8_t card_static_key[16];
} applet_session_t;
// -----------------------------------------------------------------

bool sc_select_applet(void);
bool sc_establish_secure_channel(void);
bool sc_get_public_key(void);
bool sc_check_already_voted(void);
bool sc_verify_pin(const std::string &pin);
bool sc_verify_fingerprint(const uint8_t *tmpl, uint16_t tmpl_len);
bool sc_set_voted_capture_burn_proof(void);
bool sc_lock_card_admin_token(const uint8_t *admin_token_32);

// ── Enrollment-only ───────────────────────────────────────
bool sc_personalize_card(const uint8_t pin[4],
                         const uint8_t voter_id[32],
                         const uint8_t fp_tmpl[512],
                         const uint8_t card_static_key[16],
                         const uint8_t admin_token_hash[32]);

bool sc_verify_enrollment(const uint8_t pin[4],
                          const uint8_t fp_tmpl[512],
                          const uint8_t card_static_key[16]);

bool sc_store_liveness_embedding(const uint8_t *embedding, uint16_t len);
bool sc_get_liveness_embedding(uint8_t *out, uint16_t *len_out);

// Internals
void sc_derive_session_key(void);
bool sc_verify_card_cryptogram(const uint8_t *received);
void sc_generate_terminal_cryptogram(uint8_t *out);
