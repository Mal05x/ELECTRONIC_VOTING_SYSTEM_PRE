#pragma once

#include <stdint.h>
#include <stddef.h>
#include "esp_err.h"

// ── SHA-256 ───────────────────────────────────────────────────

esp_err_t crypto_sha256(const uint8_t *data, size_t len, uint8_t out32[32]);

typedef struct {
    const uint8_t *buf;
    size_t         len;
} sha256_part_t;

esp_err_t crypto_sha256_parts(const sha256_part_t *parts, int n_parts, uint8_t out32[32]);

// ── AES-128-CBC ───────────────────────────────────────────────

esp_err_t crypto_aes128_cbc_enc_zero_iv(const uint8_t key16[16],
                                         const uint8_t *plaintext, size_t len,
                                         uint8_t *ciphertext);

esp_err_t crypto_aes128_cbc_enc_with_iv(const uint8_t key16[16],
                                          const uint8_t iv16[16],
                                          const uint8_t *plaintext, size_t len,
                                          uint8_t *ciphertext);

// ── Secure Channel Helpers ────────────────────────────────────

esp_err_t crypto_derive_session_key(const uint8_t term_random[16],
                                     const uint8_t card_random[16],
                                     const uint8_t card_static_key[16],
                                     uint8_t session_key_out[16]);

esp_err_t crypto_compute_term_cryptogram(const uint8_t session_key[16],
                                          const uint8_t term_random[16],
                                          const uint8_t card_random[16],
                                          uint8_t term_cryptogram_out[16]);

esp_err_t crypto_verify_card_cryptogram(const uint8_t session_key[16],
                                          const uint8_t card_random[16],
                                          const uint8_t card_cryptogram[16]);

esp_err_t crypto_encrypt_pin(const uint8_t session_key[16],
                               const uint8_t pin_digits[4],
                               uint8_t enc_pin_hash_out[32]);

esp_err_t crypto_compute_personalize_commitment(
    const uint8_t card_static_key[16],
    const uint8_t stored_pin_hash[32],
    const uint8_t voter_id[32],
    const uint8_t admin_token_hash[32],
    uint8_t commitment_out[32]);
