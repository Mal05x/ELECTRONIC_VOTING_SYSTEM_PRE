#pragma once
#include <stdint.h>
#include <string>

// AES-256-GCM encrypt plaintext for backend, returns base64(IV||CT||TAG)
std::string crypto_aes_gcm_encrypt(const std::string &plaintext);

// SHA-256 hash of a string, returned as base64
std::string crypto_sha256_base64(const std::string &input);
