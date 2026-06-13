#include "terminal_key.h"
#include "config.h"
#include "crypto_utils.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "mbedtls/pk.h"
#include "mbedtls/ecp.h"
#include "mbedtls/entropy.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/md.h"
#include "mbedtls/base64.h"
#include "esp_log.h"
#include <string.h>
#include <stdlib.h>
#include <time.h>


static const char *TAG = "TERM-KEY";

static const char *NVS_NS          = "term_auth";
static const char *NVS_PRIVKEY     = "priv_der";
static const char *NVS_PUBKEY      = "pub_b64";
static const char *NVS_MASTER_SEC  = "master_sec";  // 32-byte HKDF salt

// Per-terminal 32-byte master secret for HKDF session key derivation.
// NOT the ECDSA key, NOT the backend AES key.
static uint8_t s_master_secret[32]    = {};
static bool    s_master_secret_loaded = false;

// ── DS Peripheral path (production hardening) ─────────────
//
// Uncomment #define USE_DS_PERIPHERAL below (or set it in CMakeLists.txt
// via target_compile_definitions) to route ECDSA signing through the
// ESP32-S3 DS hardware block.
//
// When enabled:
//   • The private key is provisioned into the DS key block during manufacturing
//     using espefuse.py + esp_ds_data_prepare, then burned to eFuse.
//   • esp_ds_sign() replaces mbedtls_pk_sign() — the raw key bytes NEVER
//     appear in SRAM, NVS, or flash, even during signing.
//   • An attacker who reads all RAM + NVS via JTAG still cannot extract the key.
//
// Provisioning steps (once per device):
//   1. Generate terminal keypair offline:
//      openssl ecparam -name prime256v1 -genkey -noout -out terminal.pem
//   2. Run esp_ds_data_prepare tool to encrypt the key under the DS HMAC key:
//      python esp_ds_data_prepare.py --key terminal.pem --id 1 --out ds_data.bin
//   3. Burn to eFuse:
//      espefuse.py --port /dev/ttyUSB0 burn_key BLOCK4 ds_data.bin HMAC_DOWN_DIGITAL_SIGNATURE
//   4. Flash the ds_data.bin as a NVS blob or embed it in the partition table.
//
// See: https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-reference/peripherals/ds.html
//
// #define USE_DS_PERIPHERAL

#ifdef USE_DS_PERIPHERAL
#include "esp_ds.h"
// ds_data: pre-flashed DS configuration. Define in a separate manufacturing
// file that is NOT checked into version control.
extern const esp_ds_data_t *g_ds_data;
#endif

static mbedtls_pk_context s_priv_key;
static bool                s_loaded = false;
static std::string         s_pub_b64;

// ── Internal key generation ───────────────────────────────

static void gen_keypair(void) {
    mbedtls_entropy_context  entropy;
    mbedtls_ctr_drbg_context drbg;
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&drbg);

    const char *pers = TERMINAL_ID;
    mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                           (const uint8_t *)pers, strlen(pers));

    mbedtls_pk_setup(&s_priv_key, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1,
                        mbedtls_pk_ec(s_priv_key),
                        mbedtls_ctr_drbg_random, &drbg);

    // Also generate a fresh 32-byte master secret for HKDF session key derivation.
    // This is generated HERE (alongside the ECDSA key) so both are always co-present in NVS.
    esp_fill_random(s_master_secret, 32);
    s_master_secret_loaded = true;

    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) == ESP_OK) {
        // Save master secret
        nvs_set_blob(h, NVS_MASTER_SEC, s_master_secret, 32);

        // Write private key DER to NVS
        uint8_t der[512] = {};
        int dlen = mbedtls_pk_write_key_der(&s_priv_key, der, sizeof(der));
        if (dlen > 0) {
            const uint8_t *p = der + sizeof(der) - dlen;
            nvs_set_blob(h, NVS_PRIVKEY, p, dlen);
        }

        // Public key Base64
        uint8_t pub[128] = {};
        int plen = mbedtls_pk_write_pubkey_der(&s_priv_key, pub, sizeof(pub));
        if (plen > 0) {
            const uint8_t *pp = pub + sizeof(pub) - plen;
            size_t b64len = 0;
            mbedtls_base64_encode(NULL, 0, &b64len, pp, plen);
            uint8_t *b64 = (uint8_t *)malloc(b64len + 1);
            mbedtls_base64_encode(b64, b64len + 1, &b64len, pp, plen);
            s_pub_b64 = std::string((char *)b64, b64len);
            free(b64);
            nvs_set_str(h, NVS_PUBKEY, s_pub_b64.c_str());
        }
        nvs_commit(h);
        nvs_close(h);
    }

    mbedtls_entropy_free(&entropy);
    mbedtls_ctr_drbg_free(&drbg);
}

void terminal_key_init(void) {
    mbedtls_pk_init(&s_priv_key);

    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) == ESP_OK) {
        // Load master secret
        size_t mslen = 32;
        if (nvs_get_blob(h, NVS_MASTER_SEC, s_master_secret, &mslen) == ESP_OK && mslen == 32) {
            s_master_secret_loaded = true;
        }

        size_t dlen = 0;
        bool ok = false;
        if (nvs_get_blob(h, NVS_PRIVKEY, NULL, &dlen) == ESP_OK && dlen > 0 && dlen < 512) {
            uint8_t *der = (uint8_t *)malloc(dlen);
            nvs_get_blob(h, NVS_PRIVKEY, der, &dlen);
            if (mbedtls_pk_parse_key(&s_priv_key, der, dlen,
                                     NULL, 0, NULL, NULL) == 0) {
                ok = true;
                size_t slen = 0;
                nvs_get_str(h, NVS_PUBKEY, NULL, &slen);
                if (slen > 0) {
                    char *buf = (char *)malloc(slen);
                    nvs_get_str(h, NVS_PUBKEY, buf, &slen);
                    s_pub_b64 = std::string(buf);
                    free(buf);
                }
                ESP_LOGI(TAG, "Loaded existing keypair from NVS");
            }
            free(der);
        }
        nvs_close(h);

        if (!ok) {
            ESP_LOGI(TAG, "No valid key found — generating new keypair");
            gen_keypair();  // also generates and saves master secret
        } else if (!s_master_secret_loaded) {
            // Keypair exists but master secret is missing (upgrade path from v1).
            // Generate and persist it now.
            esp_fill_random(s_master_secret, 32);
            s_master_secret_loaded = true;
            nvs_handle_t h2;
            if (nvs_open(NVS_NS, NVS_READWRITE, &h2) == ESP_OK) {
                nvs_set_blob(h2, NVS_MASTER_SEC, s_master_secret, 32);
                nvs_commit(h2);
                nvs_close(h2);
            }
            ESP_LOGW(TAG, "Master secret was missing — generated and saved (firmware upgrade)");
        }
        s_loaded = true;
    }

    if (!s_pub_b64.empty()) {
        ESP_LOGI(TAG, "PUBLIC KEY (register with admin dashboard):");
        ESP_LOGI(TAG, "%s", s_pub_b64.c_str());
    }
}

bool terminal_key_is_loaded(void) { return s_loaded; }

const std::string &terminal_key_get_pubkey_b64(void) { return s_pub_b64; }

// ── DER → P1363 helper ────────────────────────────────────

static void der_sig_to_p1363(const uint8_t *der, size_t der_len,
                              uint8_t *p1363_out) {
    memset(p1363_out, 0, 64);
    if (der_len < 6 || der[0] != 0x30) return;
    size_t pos = 2;  // skip 0x30 <seqlen>
    if (der[pos] == 0x02) {
        pos++;
        uint8_t rlen = der[pos++];
        int skip = (rlen == 33 && der[pos] == 0x00) ? 1 : 0;
        memcpy(p1363_out,      der + pos + skip, 32);
        pos += rlen;
    }
    if (der[pos] == 0x02) {
        pos++;
        uint8_t slen = der[pos++];
        int skip = (slen == 33 && der[pos] == 0x00) ? 1 : 0;
        memcpy(p1363_out + 32, der + pos + skip, 32);
    }
}

std::string terminal_key_sign(const std::string &terminal_id,
                               uint64_t timestamp,
                               const std::string &body_hash_b64) {
    if (!s_loaded) return "";

    // Canonical string
    char ts_str[24];
    snprintf(ts_str, sizeof(ts_str), "%llu", (unsigned long long)timestamp);
    std::string canonical = terminal_id + "|" + ts_str + "|" + body_hash_b64;

    // Hash
    uint8_t hash[32];
    mbedtls_md_context_t md;
    mbedtls_md_init(&md);
    mbedtls_md_setup(&md, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&md);
    mbedtls_md_update(&md, (const uint8_t *)canonical.data(), canonical.size());
    mbedtls_md_finish(&md, hash);
    mbedtls_md_free(&md);

    // Sign the hash
    uint8_t der_sig[128];
    size_t  sig_len = 0;

#ifdef USE_DS_PERIPHERAL
    // ── DS hardware path (private key never in SRAM) ──────
    // esp_ds_sign() uses the key burned in eFuse via the DS peripheral.
    // It expects a SHA-256 hash as input and returns a DER signature.
    uint8_t ds_sig_out[128];
    esp_ds_sign(hash, g_ds_data, /* key_id= */1, ds_sig_out);
    // esp_ds_sign returns raw r||s (P1363, 64 bytes). Wrap in DER for
    // the der_sig_to_p1363 path below which then converts back to P1363.
    // Simpler: just skip the conversion since ds_sig_out is already P1363.
    size_t  b64len2 = 0;
    mbedtls_base64_encode(NULL, 0, &b64len2, ds_sig_out, 64);
    uint8_t *b64_2 = (uint8_t *)malloc(b64len2 + 1);
    mbedtls_base64_encode(b64_2, b64len2 + 1, &b64len2, ds_sig_out, 64);
    std::string result_ds((char *)b64_2, b64len2);
    free(b64_2);
    return result_ds;
#else
    // ── Software path (key in NVS DER blob) ──────────────
    mbedtls_entropy_context  entropy;
    mbedtls_ctr_drbg_context drbg;
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&drbg);
    const char *pers = "sign";
    mbedtls_ctr_drbg_seed(&drbg, mbedtls_entropy_func, &entropy,
                           (const uint8_t *)pers, 4);

    mbedtls_pk_sign(&s_priv_key, MBEDTLS_MD_SHA256,
                    hash, 32, der_sig, sizeof(der_sig), &sig_len,
                    mbedtls_ctr_drbg_random, &drbg);

    mbedtls_entropy_free(&entropy);
    mbedtls_ctr_drbg_free(&drbg);


#endif // USE_DS_PERIPHERAL

    // Convert to P1363
    uint8_t p1363[64];
    der_sig_to_p1363(der_sig, sig_len, p1363);

    // Base64
    size_t  b64len = 0;
    mbedtls_base64_encode(NULL, 0, &b64len, p1363, 64);
    uint8_t *b64 = (uint8_t *)malloc(b64len + 1);
    mbedtls_base64_encode(b64, b64len + 1, &b64len, p1363, 64);
    std::string result((char *)b64, b64len);
    free(b64);
    return result;
}

// ── Master secret getter ──────────────────────────────────

const uint8_t *terminal_get_master_secret(void) {
    return s_master_secret_loaded ? s_master_secret : nullptr;
}
