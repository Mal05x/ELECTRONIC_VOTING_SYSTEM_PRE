#include "smart_card.h"
#include "applet_crypto.h"
#include "pn5180.h"        // To access wrapper_send_apdu
#include "session.h"
#include "nvs.h"
#include "mbedtls/base64.h"
#include "mbedtls/aes.h"
#include <string.h>
#include "esp_log.h"
#include "esp_random.h"

static const char *TAG = "SMART_CARD";

// ─── APDU BUILDER HELPERS ───

static inline uint16_t get_sw(const uint8_t *resp, uint16_t len) {
    if (len < 2) return 0;
    return (uint16_t)((resp[len - 2] << 8) | resp[len - 1]);
}

static uint16_t apdu_build_short(uint8_t *buf, uint8_t cla, uint8_t ins, uint8_t p1, uint8_t p2, const uint8_t *data, uint8_t lc, bool include_le, uint8_t le) {
    uint16_t n = 0;
    buf[n++] = cla; buf[n++] = ins; buf[n++] = p1; buf[n++] = p2;
    if (data && lc > 0) { buf[n++] = lc; memcpy(&buf[n], data, lc); n += lc; }
    if (include_le) { buf[n++] = le; }
    return n;
}

static uint16_t apdu_build_extended(uint8_t *buf, uint8_t cla, uint8_t ins, uint8_t p1, uint8_t p2, const uint8_t *data, uint16_t lc, bool include_le) {
    uint16_t n = 0;
    buf[n++] = cla; buf[n++] = ins; buf[n++] = p1; buf[n++] = p2;
    if (data && lc > 0) {
        buf[n++] = 0x00; buf[n++] = (uint8_t)(lc >> 8); buf[n++] = (uint8_t)(lc & 0xFF);
        memcpy(&buf[n], data, lc); n += lc;
    }
    if (include_le) { buf[n++] = 0x00; buf[n++] = 0x00; }
    return n;
}

static esp_err_t send_apdu(const uint8_t *cmd, uint16_t cmd_len, uint8_t *resp_data, uint16_t *resp_data_len, uint16_t expected_sw) {
    static uint8_t resp_buf[514];
    uint16_t resp_len = 0;

    if (!wrapper_send_apdu((uint8_t*)cmd, cmd_len, resp_buf, &resp_len)) {
        ESP_LOGE(TAG, "Hardware APDU Transceive failed");
        return ESP_FAIL;
    }

    if (resp_len < 2) return ESP_FAIL;
    uint16_t sw = get_sw(resp_buf, resp_len);
    uint16_t data_len = resp_len - 2;

    if (expected_sw != 0xFFFFu) {
        uint16_t check = (expected_sw == 0) ? 0x9000 : expected_sw;
        if (sw != check) {
            ESP_LOGW(TAG, "APDU SW=0x%04X", sw);
            if (sw == 0x6983) return ESP_ERR_INVALID_STATE; // CARD_LOCKED
            if (sw == 0x6A81) return ESP_ERR_NOT_ALLOWED;   // ALREADY_VOTED
            if (sw == 0x6A82) return ESP_ERR_INVALID_RESPONSE; // FP_MISMATCH
            if (sw == 0x63C0) return ESP_ERR_INVALID_STATE; // PIN_BLOCKED
            if ((sw & 0xFFF0u) == 0x63C0) return ESP_ERR_INVALID_ARG; // PIN_WRONG
            return ESP_FAIL;
        }
    }

    if (resp_data && resp_data_len) {
        if (data_len > 0) memcpy(resp_data, resp_buf, data_len);
        *resp_data_len = data_len;
    }
    return ESP_OK;
}

static esp_err_t send_apdu_nodata(const uint8_t *cmd, uint16_t cmd_len) {
    return send_apdu(cmd, cmd_len, NULL, NULL, 0);
}


// ─── TERMINAL VOTING FUNCTIONS ───

bool sc_select_applet(void) {
    const uint8_t aid[] = {0xA0, 0x00, 0x00, 0x00, 0x03, 0x45, 0x56, 0x4F, 0x54, 0x45};
    uint8_t cmd[16];
    uint16_t cmd_len = apdu_build_short(cmd, 0x00, 0xA4, 0x04, 0x00, aid, 10, true, 0x00);
    return (send_apdu_nodata(cmd, cmd_len) == ESP_OK);
}

bool sc_establish_secure_channel(void) {
    uint8_t term_random[16];
    esp_fill_random(term_random, sizeof(term_random));

    uint8_t cmd[22];
    uint16_t cmd_len = apdu_build_short(cmd, 0x80, 0x12, 0x00, 0x00, term_random, 16, true, 0x20);

    uint8_t resp[32];
    uint16_t resp_len;
    if (send_apdu(cmd, cmd_len, resp, &resp_len, 0) != ESP_OK) return false;

    const uint8_t *card_random = resp;
    const uint8_t *card_cryptogram = resp + 16;

    crypto_derive_session_key(term_random, card_random, g_session.applet_session.card_static_key, g_session.applet_session.session_key);

    if (crypto_verify_card_cryptogram(g_session.applet_session.session_key, card_random, card_cryptogram) != ESP_OK) {
        return false;
    }

    uint8_t term_cryptogram[16];
    crypto_compute_term_cryptogram(g_session.applet_session.session_key, term_random, card_random, term_cryptogram);

    uint8_t cmd2[21];
    uint16_t cmd2_len = apdu_build_short(cmd2, 0x80, 0x13, 0x00, 0x00, term_cryptogram, 16, false, 0);

    bool ok = (send_apdu_nodata(cmd2, cmd2_len) == ESP_OK);
    if (ok) g_session.applet_session.established = true;
    return ok;
}

bool sc_verify_pin(const std::string &pin) {
    if (pin.length() != 4) return false;
    uint8_t pin_digits[4] = {(uint8_t)pin[0], (uint8_t)pin[1], (uint8_t)pin[2], (uint8_t)pin[3]};

    uint8_t enc_pin_hash[32];
    crypto_encrypt_pin(g_session.applet_session.session_key, pin_digits, enc_pin_hash);

    uint8_t cmd[37];
    uint16_t cmd_len = apdu_build_short(cmd, 0x80, 0x20, 0x00, 0x00, enc_pin_hash, 32, false, 0);

    bool ok = (send_apdu_nodata(cmd, cmd_len) == ESP_OK);
    if (ok) g_session.applet_session.pin_verified = true;
    return ok;
}

bool sc_verify_fingerprint(const uint8_t *tmpl, uint16_t tmpl_len) {
    if (tmpl_len != 512) return false;
    uint8_t iv[16];
    esp_fill_random(iv, sizeof(iv));

    static uint8_t enc_template[512];
    crypto_aes128_cbc_enc_with_iv(g_session.applet_session.session_key, iv, tmpl, 512, enc_template);

    static uint8_t payload[528];
    memcpy(payload, iv, 16);
    memcpy(payload + 16, enc_template, 512);

    static uint8_t cmd[537];
    uint16_t cmd_len = apdu_build_extended(cmd, 0x80, 0x31, 0x01, 0x00, payload, 528, false);

    bool ok = (send_apdu_nodata(cmd, cmd_len) == ESP_OK);
    if (ok) g_session.applet_session.fp_verified = true;
    return ok;
}

bool sc_check_already_voted(void) {
    uint8_t election_id_bytes[36] = {0};
    memcpy(election_id_bytes, g_electionCfg.electionId.c_str(),
           g_electionCfg.electionId.length() > 36 ? 36 : g_electionCfg.electionId.length());

    uint8_t cmd[42];
    uint16_t cmd_len = apdu_build_short(cmd, 0x80, 0x50, 0x00, 0x00, election_id_bytes, 36, true, 0x01);

    uint8_t resp[1];
    uint16_t resp_len = 0;
    if (send_apdu(cmd, cmd_len, resp, &resp_len, 0) != ESP_OK) return false;

    return (resp_len >= 1 && resp[0] == 0x01);
}

bool sc_set_voted_capture_burn_proof(void) {
    uint8_t ballot_data[2] = {0x01, 0x01};
    uint8_t election_id_bytes[36] = {0};
    memcpy(election_id_bytes, g_electionCfg.electionId.c_str(),
           g_electionCfg.electionId.length() > 36 ? 36 : g_electionCfg.electionId.length());

    static uint8_t payload[512];
    memcpy(payload, ballot_data, 2);
    memcpy(payload + 2, election_id_bytes, 36);

    static uint8_t cmd[521];
    uint16_t cmd_len = apdu_build_extended(cmd, 0x80, 0x51, 0x00, 0x00, payload, 38, true);

    uint8_t resp_sig[72];
    uint16_t sig_len = 0;

    esp_err_t ret = send_apdu(cmd, cmd_len, resp_sig, &sig_len, 0);
    if (ret != ESP_OK || sig_len == 0) return false;

    size_t b64len = 0;
    mbedtls_base64_encode(NULL, 0, &b64len, resp_sig, sig_len);
    uint8_t *b64 = (uint8_t *)malloc(b64len + 1);
    if (!b64) return false;

    mbedtls_base64_encode(b64, b64len + 1, &b64len, resp_sig, sig_len);
    g_session.cardBurnProof = std::string((char *)b64, b64len);
    free(b64);

    ESP_LOGI(TAG, "Burn proof captured and Base64 encoded (%d bytes)", sig_len);

    nvs_handle_t h_rec;
    if (nvs_open("vote_recovery", NVS_READWRITE, &h_rec) == ESP_OK) {
        nvs_set_str(h_rec, "pending_burn",      g_session.cardBurnProof.c_str());
        nvs_set_str(h_rec, "pending_token",     g_session.sessionToken.c_str());
        nvs_set_str(h_rec, "pending_candidate", g_candidates[g_nav.currentSelection].id.c_str());
        nvs_set_str(h_rec, "pending_uid",       g_session.cardUID.c_str());
        nvs_commit(h_rec);
        nvs_close(h_rec);
    }
    return true;
}

bool sc_get_public_key(void) {
    uint8_t cmd[5];
    uint16_t cmd_len = apdu_build_short(cmd, 0x80, 0x72, 0x00, 0x00, NULL, 0, true, 0x41);
    uint8_t pubkey[65];
    uint16_t pubkey_len;
    return (send_apdu(cmd, cmd_len, pubkey, &pubkey_len, 0) == ESP_OK);
}

bool sc_lock_card_admin_token(const uint8_t *admin_token_32) {
    uint8_t enc_token[32] = {0};
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    mbedtls_aes_setkey_enc(&aes, g_session.applet_session.session_key, 128);

    mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, admin_token_32, enc_token);
    mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, admin_token_32 + 16, enc_token + 16);
    mbedtls_aes_free(&aes);

    uint8_t cmd[37];
    uint16_t cmd_len = apdu_build_short(cmd, 0x80, 0x90, 0x00, 0x00, enc_token, 32, false, 0);
    return (send_apdu_nodata(cmd, cmd_len) == ESP_OK);
}


// ─── ENROLLMENT & LIVENESS FUNCTIONS ───

bool sc_personalize_card(const uint8_t pin[4], const uint8_t voter_id[32], const uint8_t fp_tmpl[512], const uint8_t card_static_key[16], const uint8_t admin_token_hash[32]) {
    static uint8_t payload[596];
    uint16_t off = 0;
    memcpy(payload + off, card_static_key, 16); off += 16;
    memcpy(payload + off, pin,             4);  off += 4;
    memcpy(payload + off, voter_id,        32); off += 32;
    memcpy(payload + off, fp_tmpl,         512); off += 512;
    memcpy(payload + off, admin_token_hash,32); off += 32;

    static uint8_t cmd[605];
    uint16_t cmd_len = apdu_build_extended(cmd, 0x80, 0x10, 0x00, 0x00, payload, 596, false);

    if (send_apdu_nodata(cmd, cmd_len) != ESP_OK) return false;

    uint8_t cmd2[5];
    uint16_t cmd2_len = apdu_build_short(cmd2, 0x80, 0x11, 0x00, 0x00, NULL, 0, true, 32);
    uint8_t commitment[32];
    uint16_t resp_len;
    if (send_apdu(cmd2, cmd2_len, commitment, &resp_len, 0) != ESP_OK) return false;

    uint8_t pin_hash[32];
    crypto_sha256(pin, 4, pin_hash);
    uint8_t local_commitment[32];
    crypto_compute_personalize_commitment(card_static_key, pin_hash, voter_id, admin_token_hash, local_commitment);

    uint8_t diff = 0;
    for (int i = 0; i < 32; i++) diff |= local_commitment[i] ^ commitment[i];
    if (diff != 0) {
        ESP_LOGE(TAG, "Personalize commitment mismatch!");
        return false;
    }
    return true;
}

bool sc_verify_enrollment(const uint8_t pin[4], const uint8_t fp_tmpl[512], const uint8_t card_static_key[16]) {
    memcpy(g_session.applet_session.card_static_key, card_static_key, 16);
    if (!sc_establish_secure_channel()) return false;

    std::string pin_str;
    pin_str += (char)pin[0]; pin_str += (char)pin[1]; pin_str += (char)pin[2]; pin_str += (char)pin[3];
    if (!sc_verify_pin(pin_str)) return false;

    if (!sc_verify_fingerprint(fp_tmpl, 512)) return false;
    return true;
}

bool sc_store_liveness_embedding(const uint8_t *embedding, uint16_t len) {
    if (len != 256) return false;
    static uint8_t enc[256];
    crypto_aes128_cbc_enc_zero_iv(g_session.applet_session.session_key, embedding, 256, enc);

    static uint8_t cmd[265];
    uint16_t cmd_len = apdu_build_extended(cmd, 0x80, 0x32, 0x00, 0x00, enc, 256, false);

    return (send_apdu_nodata(cmd, cmd_len) == ESP_OK);
}

bool sc_get_liveness_embedding(uint8_t *out, uint16_t *len_out) {
    uint8_t cmd[5];
    uint16_t cmd_len = apdu_build_short(cmd, 0x80, 0x43, 0x00, 0x00, NULL, 0, true, 0x00);

    uint8_t enc_resp[256];
    uint16_t resp_len = 0;

    if (send_apdu(cmd, cmd_len, enc_resp, &resp_len, 0) != ESP_OK) return false;
    if (resp_len != 256) return false;

    // Decrypt using zero IV
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    mbedtls_aes_setkey_dec(&aes, g_session.applet_session.session_key, 128);
    uint8_t iv[16] = {0};
    mbedtls_aes_crypt_cbc(&aes, MBEDTLS_AES_DECRYPT, 256, iv, enc_resp, out);
    mbedtls_aes_free(&aes);

    *len_out = 256;
    return true;
}

// ─── UNUSED INTERNALS (Left blank to satisfy linker if called) ───
void sc_derive_session_key(void) {}
bool sc_verify_card_cryptogram(const uint8_t *received) { return true; }
void sc_generate_terminal_cryptogram(uint8_t *out) {}
