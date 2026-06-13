#include "enrollment_store.h"
#include "mbedtls/sha256.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include <string.h>
#include <stdio.h>

static const char *TAG     = "ENROLL-STORE";
static const char *NVS_NS  = "card_keys";

// ── NVS key derivation ────────────────────────────────────
// SHA-256(card_uid_str) → first 14 hex chars (7 bytes of hash)
// NVS keys are capped at 15 chars; 14 gives us plenty of collision
// resistance for a polling-unit scale (~500 voters per terminal).

void enrollment_store_make_key(const char *card_uid_str, char *key_out) {
    uint8_t hash[32];
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0);
    mbedtls_sha256_update(&ctx, (const uint8_t *)card_uid_str, strlen(card_uid_str));
    mbedtls_sha256_finish(&ctx, hash);
    mbedtls_sha256_free(&ctx);

    // First 7 bytes → 14 hex chars + null terminator
    for (int i = 0; i < 7; i++)
        snprintf(&key_out[i * 2], 3, "%02x", hash[i]);
    key_out[14] = '\0';
}

// ── Save ──────────────────────────────────────────────────

bool enrollment_store_save(const char *card_uid_str,
                           const uint8_t card_static_key[16]) {
    char nvs_key[15];
    enrollment_store_make_key(card_uid_str, nvs_key);

    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NS, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_open failed: %s", esp_err_to_name(err));
        return false;
    }

    err = nvs_set_blob(h, nvs_key, card_static_key, 16);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_set_blob failed: %s", esp_err_to_name(err));
        nvs_close(h);
        return false;
    }

    err = nvs_commit(h);
    nvs_close(h);

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nvs_commit failed: %s", esp_err_to_name(err));
        return false;
    }

    ESP_LOGI(TAG, "Saved cardStaticKey for card %s (nvs_key=%s)",
             card_uid_str, nvs_key);
    return true;
}

// ── Load ──────────────────────────────────────────────────

bool enrollment_store_load(const char *card_uid_str, uint8_t key_out[16]) {
    char nvs_key[15];
    enrollment_store_make_key(card_uid_str, nvs_key);

    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NS, NVS_READONLY, &h);
    if (err != ESP_OK) {
        // Namespace doesn't exist yet — no cards enrolled on this terminal
        ESP_LOGW(TAG, "No card_keys namespace in NVS — card not enrolled here");
        return false;
    }

    size_t len = 16;
    err = nvs_get_blob(h, nvs_key, key_out, &len);
    nvs_close(h);

    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "cardStaticKey not found for card %s — "
                      "voter was enrolled at a different terminal. "
                      "Voting cannot proceed on this terminal.",
                 card_uid_str);
        return false;
    }
    if (err != ESP_OK || len != 16) {
        ESP_LOGE(TAG, "nvs_get_blob error: %s (len=%d)", esp_err_to_name(err), (int)len);
        return false;
    }

    ESP_LOGI(TAG, "Loaded cardStaticKey for card %s", card_uid_str);
    return true;
}

// ── Delete ────────────────────────────────────────────────

void enrollment_store_delete(const char *card_uid_str) {
    char nvs_key[15];
    enrollment_store_make_key(card_uid_str, nvs_key);

    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) != ESP_OK) return;
    nvs_erase_key(h, nvs_key);
    nvs_commit(h);
    nvs_close(h);
    ESP_LOGI(TAG, "Deleted cardStaticKey for card %s", card_uid_str);
}
