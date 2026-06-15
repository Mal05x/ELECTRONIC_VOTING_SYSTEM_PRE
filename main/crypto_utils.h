#pragma once
#include <stdint.h>
#include <string>

// AES-256-GCM encrypt plaintext for backend, returns base64(IV||CT||TAG)
std::string crypto_aes_gcm_encrypt(const std::string &plaintext);

// SHA-256 hash of a string, returned as base64
std::string crypto_sha256_base64(const std::string &input);
// SHA-256 hash of a string, returned as a 64-character HEX string
std::string crypto_sha256_hex(const std::string &input);

// Convert ASN.1 DER ECDSA signature to raw 64-byte P1363 format
bool der_to_p1363(const uint8_t *der, size_t der_len, uint8_t *p1363_out);
