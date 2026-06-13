#include "crypto_utils.h"
#include "terminal_key.h"
#include "config.h"
#include "mbedtls/gcm.h"
#include "mbedtls/sha256.h"
#include "mbedtls/base64.h"
#include "esp_random.h"
#include <stdlib.h>
#include <cstring>
#include <vector>

// ─── BACKEND NETWORK CRYPTO (RESTORED) ─────────────────────────

std::string crypto_sha256_base64(const std::string& input) {
    uint8_t hash[32];
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0); // 0 indicates SHA-256
    mbedtls_sha256_update(&ctx, (const unsigned char*)input.c_str(), input.length());
    mbedtls_sha256_finish(&ctx, hash);
    mbedtls_sha256_free(&ctx);

    size_t olen = 0;
    mbedtls_base64_encode(nullptr, 0, &olen, hash, 32);
    std::vector<unsigned char> b64(olen + 1);
    mbedtls_base64_encode(b64.data(), b64.size(), &olen, hash, 32);

    return std::string((char*)b64.data(), olen);
}

std::string crypto_aes_gcm_encrypt(const std::string& input) {
    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);

    // 💥 CHANGED: Using BACKEND_AES_KEY from config.h instead of g_terminal_aes_key
    mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, BACKEND_AES_KEY, 256);

    // Generate 12-byte random IV
    unsigned char iv[12];
    esp_fill_random(iv, sizeof(iv));

    std::vector<unsigned char> ciphertext(input.length());
    unsigned char tag[16];

    // Encrypt payload
    mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, input.length(),
                              iv, sizeof(iv), nullptr, 0,
                              (const unsigned char*)input.c_str(), ciphertext.data(), 16, tag);
    mbedtls_gcm_free(&gcm);

    // Standard Backend Payload = IV (12) + Ciphertext + Tag (16)
    std::vector<unsigned char> payload;
    payload.insert(payload.end(), iv, iv + 12);
    payload.insert(payload.end(), ciphertext.begin(), ciphertext.end());
    payload.insert(payload.end(), tag, tag + 16);

    // Base64 encode the final payload
    size_t olen = 0;
    mbedtls_base64_encode(nullptr, 0, &olen, payload.data(), payload.size());
    std::vector<unsigned char> b64(olen + 1);
    mbedtls_base64_encode(b64.data(), b64.size(), &olen, payload.data(), payload.size());

    return std::string((char*)b64.data(), olen);
}
