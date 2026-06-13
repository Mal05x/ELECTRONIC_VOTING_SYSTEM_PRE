#include "applet_crypto.h"
#include <string.h>
#include "esp_log.h"
#include "esp_random.h"
#include "mbedtls/aes.h"
#include "mbedtls/sha256.h"

static const char *TAG = "CRYPTO";

// ── SHA-256 ───────────────────────────────────────────────────

esp_err_t crypto_sha256(const uint8_t *data, size_t len, uint8_t out32[32]) {
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);

    int rc = mbedtls_sha256_starts(&ctx, 0);
    if (rc != 0) goto fail;
    rc = mbedtls_sha256_update(&ctx, data, len);
    if (rc != 0) goto fail;
    rc = mbedtls_sha256_finish(&ctx, out32);
    if (rc != 0) goto fail;

    mbedtls_sha256_free(&ctx);
    return ESP_OK;

fail:
    mbedtls_sha256_free(&ctx);
    ESP_LOGE(TAG, "SHA-256 failed: -0x%04x", -rc);
    return ESP_FAIL;
}

esp_err_t crypto_sha256_parts(const sha256_part_t *parts, int n_parts, uint8_t out32[32]) {
    if (!parts || n_parts < 1) return ESP_ERR_INVALID_ARG;

    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);

    int rc = mbedtls_sha256_starts(&ctx, 0);
    if (rc != 0) goto fail;

    for (int i = 0; i < n_parts; i++) {
        if (!parts[i].buf || parts[i].len == 0) continue;
        rc = mbedtls_sha256_update(&ctx, parts[i].buf, parts[i].len);
        if (rc != 0) goto fail;
    }

    rc = mbedtls_sha256_finish(&ctx, out32);
    if (rc != 0) goto fail;

    mbedtls_sha256_free(&ctx);
    return ESP_OK;

fail:
    mbedtls_sha256_free(&ctx);
    ESP_LOGE(TAG, "SHA-256 parts failed: -0x%04x", -rc);
    return ESP_FAIL;
}

// ── AES-128-CBC ───────────────────────────────────────────────

static esp_err_t _aes128_cbc_enc(const uint8_t key16[16], uint8_t iv16[16], const uint8_t *plaintext, size_t len, uint8_t *ciphertext) {
    if (len == 0 || (len & 0x0F) != 0) {
        ESP_LOGE(TAG, "AES-CBC len %zu is not a non-zero multiple of 16", len);
        return ESP_ERR_INVALID_ARG;
    }

    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);

    int rc = mbedtls_aes_setkey_enc(&aes, key16, 128);
    if (rc != 0) { mbedtls_aes_free(&aes); return ESP_FAIL; }

    rc = mbedtls_aes_crypt_cbc(&aes, MBEDTLS_AES_ENCRYPT, len, iv16, plaintext, ciphertext);
    mbedtls_aes_free(&aes);

    if (rc != 0) {
        ESP_LOGE(TAG, "AES-CBC encrypt failed: -0x%04x", -rc);
        return ESP_FAIL;
    }
    return ESP_OK;
}

esp_err_t crypto_aes128_cbc_enc_zero_iv(const uint8_t key16[16], const uint8_t *plaintext, size_t len, uint8_t *ciphertext) {
    uint8_t iv[16] = {0};
    return _aes128_cbc_enc(key16, iv, plaintext, len, ciphertext);
}

esp_err_t crypto_aes128_cbc_enc_with_iv(const uint8_t key16[16], const uint8_t iv16[16], const uint8_t *plaintext, size_t len, uint8_t *ciphertext) {
    uint8_t iv_copy[16];
    memcpy(iv_copy, iv16, 16);
    return _aes128_cbc_enc(key16, iv_copy, plaintext, len, ciphertext);
}

// ── Secure Channel Helpers ────────────────────────────────────

esp_err_t crypto_derive_session_key(const uint8_t term_random[16], const uint8_t card_random[16], const uint8_t card_static_key[16], uint8_t session_key_out[16]) {
    uint8_t digest[32];
    sha256_part_t parts[] = {
        { term_random,     16 },
        { card_random,     16 },
        { card_static_key, 16 },
    };
    esp_err_t ret = crypto_sha256_parts(parts, 3, digest);
    if (ret == ESP_OK) {
        memcpy(session_key_out, digest, 16);
    }
    memset(digest, 0, sizeof(digest));
    return ret;
}

esp_err_t crypto_compute_term_cryptogram(const uint8_t session_key[16], const uint8_t term_random[16], const uint8_t card_random[16], uint8_t term_cryptogram[16]) {
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);

    int rc = mbedtls_aes_setkey_enc(&aes, session_key, 128);
    if (rc != 0) {
        mbedtls_aes_free(&aes);
        return ESP_FAIL;
    }

    uint8_t input[16];
    for (int i = 0; i < 16; i++) {
        input[i] = term_random[i] ^ card_random[i];
    }

    rc = mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, input, term_cryptogram);
    mbedtls_aes_free(&aes);

    return (rc == 0) ? ESP_OK : ESP_FAIL;
}

esp_err_t crypto_verify_card_cryptogram(const uint8_t session_key[16], const uint8_t card_random[16], const uint8_t card_cryptogram[16]) {
    uint8_t expected[16];
    esp_err_t ret = crypto_aes128_cbc_enc_zero_iv(session_key, card_random, 16, expected);
    if (ret != ESP_OK) return ret;

    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) {
        diff |= expected[i] ^ card_cryptogram[i];
    }
    memset(expected, 0, sizeof(expected));

    if (diff != 0) {
        ESP_LOGE(TAG, "Card cryptogram mismatch — possible rogue card or wrong static key");
        return ESP_ERR_INVALID_RESPONSE;
    }
    return ESP_OK;
}

esp_err_t crypto_encrypt_pin(const uint8_t session_key[16], const uint8_t pin_digits[4], uint8_t enc_pin_hash_out[32]) {
    uint8_t pin_hash[32];
    esp_err_t ret = crypto_sha256(pin_digits, 4, pin_hash);
    if (ret != ESP_OK) return ret;

    ret = crypto_aes128_cbc_enc_zero_iv(session_key, pin_hash, 32, enc_pin_hash_out);
    memset(pin_hash, 0, sizeof(pin_hash));
    return ret;
}

esp_err_t crypto_compute_personalize_commitment(const uint8_t card_static_key[16], const uint8_t stored_pin_hash[32], const uint8_t voter_id[32], const uint8_t admin_token_hash[32], uint8_t commitment_out[32]) {
    sha256_part_t parts[] = {
        { card_static_key,  16 },
        { stored_pin_hash,  32 },
        { voter_id,         32 },
        { admin_token_hash, 32 },
    };
    return crypto_sha256_parts(parts, 4, commitment_out);
}
