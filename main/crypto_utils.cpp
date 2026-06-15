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
#include "esp_log.h"

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
// ─── DER to P1363 CONVERTER (AUTO-ALIGNING) ─────────────────────────

bool der_to_p1363(const uint8_t *der, size_t der_len, uint8_t *p1363_out) {
    if (!der || !p1363_out || der_len < 8) return false;

    // 1. AUTO-ALIGNMENT HOOK: Scan for the ASN.1 SEQUENCE tag (0x30)
    size_t offset = 0;
    while (offset < der_len && der[offset] != 0x30) {
        offset++;
    }

    if (offset > 0) {
        if (offset >= der_len) {
            ESP_LOGE("CRYPTO", "Invalid DER: 0x30 SEQUENCE tag not found.");
            return false;
        }
        ESP_LOGW("CRYPTO", "Found 0x30 tag at offset %zu. Auto-aligning...", offset);
    }

    const uint8_t *aligned_der = der + offset;
    size_t aligned_len = der_len - offset;

    // 2. PARSE ASN.1 DER SEQUENCE
    if (aligned_len < 4 || aligned_der[0] != 0x30) return false;
    size_t seq_len = aligned_der[1];
    if (seq_len + 2 > aligned_len) return false;

    size_t pos = 2;

    // Parse R Integer
    if (aligned_der[pos++] != 0x02) return false;
    size_t r_len = aligned_der[pos++];
    const uint8_t *r = &aligned_der[pos];
    pos += r_len;

    // Parse S Integer
    if (aligned_der[pos++] != 0x02) return false;
    size_t s_len = aligned_der[pos++];
    const uint8_t *s = &aligned_der[pos];

    // 3. FORMAT AS 64-BYTE P1363
    memset(p1363_out, 0, 64);

    // Strip ASN.1 leading zero padding if present (used to keep integers unsigned)
    if (r_len == 33 && r[0] == 0x00) { r++; r_len--; }
    if (r_len > 32) return false;
    memcpy(p1363_out + (32 - r_len), r, r_len);

    if (s_len == 33 && s[0] == 0x00) { s++; s_len--; }
    if (s_len > 32) return false;
    memcpy(p1363_out + 32 + (32 - s_len), s, s_len);

    return true;
}

std::string crypto_sha256_hex(const std::string& input) {
    uint8_t hash[32];
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0);
    mbedtls_sha256_update(&ctx, (const unsigned char*)input.c_str(), input.length());
    mbedtls_sha256_finish(&ctx, hash);
    mbedtls_sha256_free(&ctx);

    char hex[65];
    for (int i = 0; i < 32; i++) {
        sprintf(&hex[i * 2], "%02x", hash[i]);
    }
    return std::string(hex);
}
