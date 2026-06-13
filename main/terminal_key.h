#pragma once
#include <string>
#include <stdint.h>
#include "esp_random.h"

// Load or generate the terminal's ECDSA P-256 keypair from NVS.
// Prints the public key to UART on first boot.
void terminal_key_init(void);

// Sign a request payload. Returns Base64 P1363 signature.
// canonical = terminalId|timestamp|base64BodyHash
std::string terminal_key_sign(const std::string &terminal_id,
                              uint64_t timestamp,
                              const std::string &body_hash_b64);

// Returns the Base64 SPKI public key (for admin dashboard registration)
const std::string &terminal_key_get_pubkey_b64(void);

bool terminal_key_is_loaded(void);

/**
 * terminal_get_master_secret()
 * Returns a pointer to the 32-byte per-terminal NVS master secret used as
 * the HKDF salt for NFC secure-channel session key derivation.
 *
 * Generated randomly on first boot alongside the ECDSA keypair and stored in
 * NVS under namespace "term_auth", key "master_secret".
 *
 * This secret is SEPARATE from the ECDSA private key and the backend AES key.
 * It never leaves the device and is never transmitted anywhere.
 *
 * Production hardening: if USE_DS_PERIPHERAL is defined, consider wrapping
 * the NVS blob with the DS peripheral's HMAC key to prevent readback via JTAG.
 */
const uint8_t *terminal_get_master_secret(void);
