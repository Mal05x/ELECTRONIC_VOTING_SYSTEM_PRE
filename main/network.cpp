#include "network.h"
#include "config.h"
#include "session.h"
#include "terminal_key.h"
#include "crypto_utils.h"
#include "pn5180.h"
#include "applet_defs.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_http_client.h"
#include "esp_tls.h"
#include "esp_sntp.h"
#include "esp_log.h"
#include "esp_random.h"
#include "cJSON.h"
#include "mbedtls/base64.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include "esp_crt_bundle.h"
#include "officer_auth.h"
#include "nvs.h"


static const char *TAG = "NET";

// ── WiFi event group ──────────────────────────────────────
static EventGroupHandle_t s_wifi_eg;
#define WIFI_CONNECTED_BIT BIT0
#define WIFI_FAIL_BIT      BIT1
static int s_retry_cnt = 0;
#define MAX_RETRY 10

static void wifi_event_handler(void *arg, esp_event_base_t base,
                                int32_t event_id, void *event_data) {
    if (base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        if (s_retry_cnt < MAX_RETRY) {
            esp_wifi_connect();
            s_retry_cnt++;
        } else {
            xEventGroupSetBits(s_wifi_eg, WIFI_FAIL_BIT);
        }
    } else if (base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        s_retry_cnt = 0;
        xEventGroupSetBits(s_wifi_eg, WIFI_CONNECTED_BIT);
    }
}

bool net_wifi_connect(void) {
    s_wifi_eg = xEventGroupCreate();

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    esp_event_handler_instance_t h1, h2;
    esp_event_handler_instance_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                        &wifi_event_handler, NULL, &h1);
    esp_event_handler_instance_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                        &wifi_event_handler, NULL, &h2);

    wifi_config_t wcfg = {};
    strncpy((char *)wcfg.sta.ssid,     WIFI_SSID,     sizeof(wcfg.sta.ssid));
    strncpy((char *)wcfg.sta.password, WIFI_PASSWORD, sizeof(wcfg.sta.password));
    wcfg.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wcfg));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_connect());

    EventBits_t bits = xEventGroupWaitBits(s_wifi_eg,
                                           WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
                                           pdFALSE, pdFALSE,
                                           pdMS_TO_TICKS(WIFI_CONNECT_TIMEOUT_MS));
    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "WiFi connected");
        return true;
    }
    ESP_LOGW(TAG, "WiFi failed");
    return false;
}

void net_wifi_reconnect_if_needed(void) {
    wifi_ap_record_t ap;
    if (esp_wifi_sta_get_ap_info(&ap) != ESP_OK) {
        s_retry_cnt = 0;
        esp_wifi_connect();
    }
}

// ── NTP ───────────────────────────────────────────────────

bool net_ntp_sync(void) {
    esp_sntp_setoperatingmode(SNTP_OPMODE_POLL);
    esp_sntp_setservername(0, NTP_SERVER_1);
    esp_sntp_setservername(1, NTP_SERVER_2);
    esp_sntp_init();

    time_t now = 0;
    int tries = 0;
    while (now < 1700000000UL && tries < 40) {
        vTaskDelay(pdMS_TO_TICKS(500));
        time(&now);
        tries++;
    }
    if (now < 1700000000UL) {
        ESP_LOGW(TAG, "NTP sync failed");
        return false;
    }
    ESP_LOGI(TAG, "NTP synced: %s", ctime(&now));
    return true;
}

// ── HTTP client helpers ───────────────────────────────────

// Accumulated response body for esp_http_client callbacks
struct HttpResp {
    char   *buf;
    size_t  len;
    size_t  cap;
};

static esp_err_t http_event_handler(esp_http_client_event_t *evt) {
    HttpResp *r = (HttpResp *)evt->user_data;
    if (!r) return ESP_OK;
    if (evt->event_id == HTTP_EVENT_ON_DATA) {
        size_t need = r->len + evt->data_len + 1;
        if (need > r->cap) {
            r->cap = need + 256;
            r->buf = (char *)realloc(r->buf, r->cap);
        }
        memcpy(r->buf + r->len, evt->data, evt->data_len);
        r->len += evt->data_len;
        r->buf[r->len] = '\0';
    }
    return ESP_OK;
}

// Build a fresh esp_http_client with mTLS
static esp_http_client_handle_t make_client(const char *path,
                                             esp_http_client_method_t method,
                                             HttpResp *user_data) {
    char url[256];
    snprintf(url, sizeof(url), "https://%s%s", BACKEND_HOST, path);

    esp_http_client_config_t cfg = {};
    cfg.url                   = url;
    cfg.method                = method;
    cfg.crt_bundle_attach     = esp_crt_bundle_attach;
    cfg.client_cert_pem       = CLIENT_CERT;
    cfg.client_key_pem        = CLIENT_KEY;
    cfg.event_handler         = http_event_handler;
    cfg.user_data             = user_data;
    cfg.timeout_ms            = 15000;
    cfg.skip_cert_common_name_check = false;
    cfg.transport_type        = HTTP_TRANSPORT_OVER_SSL;
    cfg.buffer_size           = 2048;

    return esp_http_client_init(&cfg);
}

// Add terminal auth headers (X-Terminal-Id, X-Request-Timestamp, X-Terminal-Signature)
static void add_auth_headers(esp_http_client_handle_t client, const std::string &body) {
    time_t ts = time(NULL);
    std::string body_hash = crypto_sha256_base64(body);
    std::string sig = terminal_key_sign(TERMINAL_ID, (uint64_t)ts, body_hash);

    esp_http_client_set_header(client, "X-Terminal-Id",        TERMINAL_ID);

    char ts_str[24];
    snprintf(ts_str, sizeof(ts_str), "%lld", (long long)ts);
    esp_http_client_set_header(client, "X-Request-Timestamp",  ts_str);
    esp_http_client_set_header(client, "X-Terminal-Signature", sig.c_str());
}

// ── Election config ───────────────────────────────────────

bool net_fetch_election_config(void) {
    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/config",
                                              HTTP_METHOD_GET, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, "");

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code != 200) {
        ESP_LOGW(TAG, "fetchElectionConfig HTTP=%d err=%s", code, esp_err_to_name(err));
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;

    g_electionCfg.electionId    = cJSON_GetStringValue(cJSON_GetObjectItem(json, "electionId"))    ?: "";
    g_electionCfg.electionTitle = cJSON_GetStringValue(cJSON_GetObjectItem(json, "electionTitle"))  ?: "";
    g_electionCfg.closingTime   = cJSON_GetStringValue(cJSON_GetObjectItem(json, "closingTime"))    ?: "";
    cJSON *pu = cJSON_GetObjectItem(json, "pollingUnitId");
    g_electionCfg.pollingUnitId = pu ? pu->valueint : 0;

    // BUG-8 FIX: Parse livenessMode so cam_perform_liveness() can route
    // to BURST: (PASSIVE/MiniFASNet) or ACTIVE: (MediaPipe challenge).
    // Defaults to "PASSIVE" when the field is absent (backward compatible).
    const char *lm = cJSON_GetStringValue(cJSON_GetObjectItem(json, "livenessMode"));
    g_electionCfg.livenessMode = (lm && strlen(lm) > 0) ? lm : "PASSIVE";

    g_electionCfg.loaded = true;
    cJSON_Delete(json);

    ESP_LOGI(TAG, "Election: %s [%s] livenessMode=%s",
             g_electionCfg.electionTitle.c_str(),
             g_electionCfg.electionId.c_str(),
             g_electionCfg.livenessMode.c_str());
    return true;
}

// ── Candidates ────────────────────────────────────────────

bool net_fetch_candidates(void) {
    if (!g_electionCfg.loaded) return false;

    char path[128];
    snprintf(path, sizeof(path), "/api/terminal/candidates/%s",
             g_electionCfg.electionId.c_str());


    HttpResp r = {};
    esp_http_client_handle_t h = make_client(path, HTTP_METHOD_GET, &r);
    add_auth_headers(h, "");

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code != 200) {
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;

    cJSON *arr = cJSON_GetObjectItem(json, "candidates");
    g_candidateCount = 0;
    cJSON *item;
    cJSON_ArrayForEach(item, arr) {
        if (g_candidateCount >= 10) break;
        g_candidates[g_candidateCount].id      = cJSON_GetStringValue(cJSON_GetObjectItem(item, "id"))       ?: "";
        g_candidates[g_candidateCount].name    = cJSON_GetStringValue(cJSON_GetObjectItem(item, "fullName")) ?: "";
        g_candidates[g_candidateCount].party   = cJSON_GetStringValue(cJSON_GetObjectItem(item, "partyAbbreviation")) ?: "";
        g_candidates[g_candidateCount].position= cJSON_GetStringValue(cJSON_GetObjectItem(item, "position")) ?: "";
        g_candidateCount++;
    }
    cJSON_Delete(json);
    ESP_LOGI(TAG, "%d candidates loaded", g_candidateCount);
    return true;
}

/**
 * net_fetch_officer_pin_hash
 *
 * Fetches the SHA-256 hex hash of the officer PIN from the backend and
 * writes it to the officer_auth NVS namespace.
 *
 * Called from task_election_config (Core 0) if officer_auth_is_set()
 * returns false after Wi-Fi connects.
 *
 * The endpoint is ECDSA-authenticated — add_auth_headers() signs the
 * empty body, just as it does for all other terminal API calls.
 *
 * Returns true if the hash was received and stored successfully.
 * Returns false if the server has no hash (admin has not provisioned
 * the PIN yet) or if the network request fails.
 */
bool net_fetch_officer_pin_hash(void) {
    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/officer-pin-hash",
                                              HTTP_METHOD_GET, &r);
    add_auth_headers(h, "");   // ECDSA sign the empty body

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code != 200) {
        ESP_LOGW(TAG, "officer-pin-hash fetch: HTTP=%d — admin may not have "
                      "provisioned the officer PIN yet", code);
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;

    const char *hash_hex = cJSON_GetStringValue(cJSON_GetObjectItem(json, "pinHash"));
    if (!hash_hex || strlen(hash_hex) != 64) {
        ESP_LOGE(TAG, "officer-pin-hash: invalid or missing pinHash field");
        cJSON_Delete(json);
        return false;
    }

    // Convert 64 hex chars → 32 raw bytes and store in NVS
    uint8_t hash_bytes[32];
    for (int i = 0; i < 32; i++) {
        unsigned int val = 0;
        sscanf(&hash_hex[i * 2], "%02x", &val);
        hash_bytes[i] = (uint8_t)val;
    }
    cJSON_Delete(json);

    // Write to NVS via officer_auth module's internal writer
    // (expose nvs_write_hash as a friend function or call officer_auth_store_hash)
    bool ok = officer_auth_store_fetched_hash(hash_bytes);
    if (ok) {
        ESP_LOGI(TAG, "Officer PIN hash fetched and stored in NVS");
    }
    return ok;
}

// ── Voter authentication ──────────────────────────────────

bool net_authenticate_voter(void) {
    // Get card ECDSA signature
    uint8_t sig_apdu[6] = { CLA_EVOTING, INS_GET_SIGNATURE, 0x00, 0x00, 0x01, 0x01 };
    uint8_t sig_resp[256]; uint16_t sig_rlen;
    std::string card_sig_b64;
    if (wrapper_send_apdu(sig_apdu, 6, sig_resp, &sig_rlen)) {
        uint16_t sw = ((uint16_t)sig_resp[sig_rlen-2] << 8) | sig_resp[sig_rlen-1];
        if (sw == SW_SUCCESS && sig_rlen > 2) {
            size_t b64len = 0;
            mbedtls_base64_encode(NULL, 0, &b64len, sig_resp, sig_rlen - 2);
            uint8_t *b64 = (uint8_t *)malloc(b64len + 1);
            mbedtls_base64_encode(b64, b64len + 1, &b64len, sig_resp, sig_rlen - 2);
            card_sig_b64 = std::string((char *)b64, b64len);
            free(b64);
        }
    }

    // Build inner JSON
    cJSON *inner = cJSON_CreateObject();
    std::string cardIdHash = crypto_sha256_base64(g_session.cardUID);
    cJSON_AddStringToObject(inner, "cardIdHash",     g_session.cardUID.c_str());
    cJSON_AddStringToObject(inner, "cardSignature",  card_sig_b64.c_str());
    cJSON_AddStringToObject(inner, "signedMessage",  "Identity Cryptographically Verified");
    cJSON_AddStringToObject(inner, "sessionId",      g_session.sessionId.c_str());
    cJSON_AddStringToObject(inner, "voterPublicKey", g_session.voterPublicKey.c_str());
    cJSON_AddStringToObject(inner, "electionId",     g_electionCfg.electionId.c_str());
    cJSON_AddStringToObject(inner, "terminalId",     TERMINAL_ID);
    char *inner_str = cJSON_PrintUnformatted(inner);
    cJSON_Delete(inner);

    std::string encrypted = crypto_aes_gcm_encrypt(std::string(inner_str));
    free(inner_str);

    cJSON *outer = cJSON_CreateObject();
    cJSON_AddStringToObject(outer, "payload", encrypted.c_str());
    char *body_str = cJSON_PrintUnformatted(outer);
    cJSON_Delete(outer);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/authenticate",
                                              HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code != 200) {
        ESP_LOGW(TAG, "Auth failed HTTP=%d", code);
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;
    g_session.sessionToken = cJSON_GetStringValue(cJSON_GetObjectItem(json, "sessionToken")) ?: "";
    cJSON_Delete(json);
    return true;
}

// ── Vote submission ───────────────────────────────────────

bool net_submit_vote(void) {
    cJSON *pkt = cJSON_CreateObject();
    cJSON_AddStringToObject(pkt, "sessionToken",  g_session.sessionToken.c_str());
    cJSON_AddStringToObject(pkt, "candidateId",   g_candidates[g_nav.currentSelection].id.c_str());
    std::string cardIdHash = crypto_sha256_base64(g_session.cardUID);
    cJSON_AddStringToObject(pkt, "cardIdHash",    g_session.cardUID.c_str());
    cJSON_AddStringToObject(pkt, "terminalId",    TERMINAL_ID);
    cJSON_AddStringToObject(pkt, "electionId",    g_electionCfg.electionId.c_str());
    cJSON_AddStringToObject(pkt, "cardBurnProof", g_session.cardBurnProof.c_str());
    char *pkt_str = cJSON_PrintUnformatted(pkt);
    cJSON_Delete(pkt);

    std::string encrypted = crypto_aes_gcm_encrypt(std::string(pkt_str));
    free(pkt_str);

    cJSON *outer = cJSON_CreateObject();
    cJSON_AddStringToObject(outer, "payload", encrypted.c_str());
    char *body_str = cJSON_PrintUnformatted(outer);
    cJSON_Delete(outer);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/vote",
                                              HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code != 200) {
        ESP_LOGW(TAG, "Vote failed HTTP=%d", code);
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;
    g_lastTransactionId = cJSON_GetStringValue(cJSON_GetObjectItem(json, "transactionId")) ?: "";
    cJSON_Delete(json);
    return true;
}

// ── Heartbeat ─────────────────────────────────────────────

void net_send_heartbeat(void) {
    // Read battery via ADC (reuse adc_oneshot pattern)
	int battery = read_battery_percent();

    char ip_str[20] = "0.0.0.0";
    esp_netif_ip_info_t ip_info;
    esp_netif_t *netif = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    if (netif && esp_netif_get_ip_info(netif, &ip_info) == ESP_OK)
        snprintf(ip_str, sizeof(ip_str), IPSTR, IP2STR(&ip_info.ip));

    cJSON *doc = cJSON_CreateObject();
    cJSON_AddStringToObject(doc, "terminalId",   TERMINAL_ID);
    cJSON_AddNumberToObject(doc, "batteryLevel", battery);
    cJSON_AddFalseToObject( doc, "tamperFlag");   // tamper via GPIO not yet wired in IDF
    cJSON_AddStringToObject(doc, "ipAddress",    ip_str);
    char *body_str = cJSON_PrintUnformatted(doc);
    cJSON_Delete(doc);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/heartbeat",
                                              HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    // --> ADD THIS LINE TO FIX THE HEARTBEAT <--
        add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());
    esp_http_client_perform(h);
    esp_http_client_cleanup(h);
    free(r.buf);
    ESP_LOGI(TAG, "Heartbeat sent battery=%d%%", battery);
}

// ── Pending registration ──────────────────────────────────

bool net_register_pending(void) {
    if (g_session.voterPublicKey.empty()) return false;

    cJSON *doc = cJSON_CreateObject();
    cJSON_AddStringToObject(doc, "terminalId",     TERMINAL_ID);
    cJSON_AddNumberToObject(doc, "pollingUnitId",  g_electionCfg.pollingUnitId);
    std::string cardIdHash = crypto_sha256_base64(g_session.cardUID);
    cJSON_AddStringToObject(doc, "cardIdHash",     g_session.cardUID.c_str());
    cJSON_AddStringToObject(doc, "voterPublicKey", g_session.voterPublicKey.c_str());
    char *body_str = cJSON_PrintUnformatted(doc);
    cJSON_Delete(doc);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/pending-registration",
                                              HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err == ESP_OK && code == 200) {
        cJSON *json = cJSON_Parse(r.buf);
        if (json) {
            g_session.pendingRegId = cJSON_GetStringValue(cJSON_GetObjectItem(json, "pendingId")) ?: "";
            cJSON_Delete(json);
        }
        ESP_LOGI(TAG, "Pending reg created: %s", g_session.pendingRegId.c_str());
        free(r.buf);
        return true;
    }
    free(r.buf);
    if (code == 409 || code == 400) ESP_LOGI(TAG, "Card already registered (HTTP %d)", code);
    else ESP_LOGW(TAG, "Pending reg failed HTTP=%d", code);
    return false;
}

// ── Fetch pending enrollment ──────────────────────────────

bool net_fetch_pending_enrollment(void) {
    char path[128];
    snprintf(path, sizeof(path), "/api/terminal/pending_enrollment?terminalId=%s",
             TERMINAL_ID);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client(path, HTTP_METHOD_GET, &r);
    add_auth_headers(h, "");

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code == 204) {
        // 204 = no enrollment queued for this terminal
        ESP_LOGI(TAG, "No pending enrollment (HTTP %d)", code);
        free(r.buf);
        return false;
    }
    if (code != 200) {
        ESP_LOGW(TAG, "fetchPendingEnrollment HTTP=%d", code);
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;

    g_enroll_record.enrollmentId        = cJSON_GetStringValue(cJSON_GetObjectItem(json, "enrollmentId"))         ?: "";
    g_enroll_record.electionId          = cJSON_GetStringValue(cJSON_GetObjectItem(json, "electionId"))           ?: "";
    g_enroll_record.voterPublicKey      = cJSON_GetStringValue(cJSON_GetObjectItem(json, "voterPublicKey"))       ?: "";
    g_enroll_record.encryptedDemographic= cJSON_GetStringValue(cJSON_GetObjectItem(json, "encryptedDemographic")) ?: "";

    cJSON *puId = cJSON_GetObjectItem(json, "pollingUnitId");
    g_enroll_record.pollingUnitId = puId ? (long)puId->valuedouble : 0;

    // Decode cardStaticKey (Base64 → 16 bytes)
    const char *csk = cJSON_GetStringValue(cJSON_GetObjectItem(json, "cardStaticKey"));
    if (csk) {
        size_t olen = 0;
        mbedtls_base64_decode(g_enroll_record.cardStaticKey, 16, &olen,
                              (const uint8_t *)csk, strlen(csk));
        if (olen != 16) {
            ESP_LOGE(TAG, "cardStaticKey decode failed: got %d bytes", (int)olen);
            cJSON_Delete(json);
            return false;
        }
    } else {
        ESP_LOGE(TAG, "cardStaticKey missing from enrollment record");
        cJSON_Delete(json);
        return false;
    }

    // Decode adminTokenHash (Base64 → 32 bytes)
    // NOTE: TerminalEnrollmentRecordDTO needs adminTokenHash field added
    // in the backend (see session.h EnrollmentRecord comment).
    // Until that field exists, this falls back to zeros — the applet will
    // accept any admin token that SHA-256-hashes to 32 zero bytes.
    const char *ath = cJSON_GetStringValue(cJSON_GetObjectItem(json, "adminTokenHash"));
    if (ath) {
        size_t olen = 0;
        mbedtls_base64_decode(g_enroll_record.adminTokenHash, 32, &olen,
                              (const uint8_t *)ath, strlen(ath));
        if (olen != 32) {
            ESP_LOGW(TAG, "adminTokenHash decode failed: got %d bytes — using zeros", (int)olen);
            memset(g_enroll_record.adminTokenHash, 0, 32);
        }
    } else {
        ESP_LOGW(TAG, "adminTokenHash not present in DTO — using zeros. "
                      "Add adminTokenHash to TerminalEnrollmentRecordDTO in backend.");
        memset(g_enroll_record.adminTokenHash, 0, 32);
    }

    g_enroll_record.loaded = true;
    cJSON_Delete(json);

    ESP_LOGI(TAG, "Enrollment record: %s pollingUnit=%ld",
             g_enroll_record.enrollmentId.c_str(),
             g_enroll_record.pollingUnitId);
    return true;
}

// ── Complete enrollment ───────────────────────────────────

bool net_complete_enrollment(void) {
    cJSON *doc = cJSON_CreateObject();
    cJSON_AddStringToObject(doc, "enrollmentId", g_enroll_record.enrollmentId.c_str());
    cJSON_AddStringToObject(doc, "cardIdHash",   g_enroll_session.cardUID.c_str());
    cJSON_AddStringToObject(doc, "terminalId",   TERMINAL_ID);
    char *body_str = cJSON_PrintUnformatted(doc);
    cJSON_Delete(doc);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/enrollment",
                                              HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    esp_err_t err  = esp_http_client_perform(h);
    int        code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);
    free(r.buf);

    if (err == ESP_OK && code == 200) {
        ESP_LOGI(TAG, "Enrollment %s confirmed by backend",
                 g_enroll_record.enrollmentId.c_str());
        return true;
    }
    ESP_LOGW(TAG, "completeEnrollment HTTP=%d err=%s", code, esp_err_to_name(err));
    return false;
}

// --- NEW BOOT RECOVERY LOOP ---
bool net_check_and_recover_vote(void) {
    nvs_handle_t h_rec;
    if (nvs_open("vote_recovery", NVS_READONLY, &h_rec) != ESP_OK) {
        return false;
    }

    size_t required_len = 0;
    if (nvs_get_str(h_rec, "pending_burn", NULL, &required_len) != ESP_OK) {
        nvs_close(h_rec);
        return false;
    }

    ESP_LOGW(TAG, "ALERT: Unsubmitted vote payload found in flash memory. Initiating recovery...");

    char burn[128] = {}, token[64] = {}, candidate[64] = {}, uid[32] = {};
    size_t len_b = sizeof(burn), len_t = sizeof(token), len_c = sizeof(candidate), len_u = sizeof(uid);

    nvs_get_str(h_rec, "pending_burn",      burn,      &len_b);
    nvs_get_str(h_rec, "pending_token",     token,     &len_t);
    nvs_get_str(h_rec, "pending_candidate", candidate, &len_c);
    nvs_get_str(h_rec, "pending_uid",       uid,       &len_u);
    nvs_close(h_rec);

    cJSON *pkt = cJSON_CreateObject();
    cJSON_AddStringToObject(pkt, "sessionToken",  token);
    cJSON_AddStringToObject(pkt, "candidateId",   candidate);
    cJSON_AddStringToObject(pkt, "cardIdHash",    crypto_sha256_base64(std::string(uid)).c_str());
    cJSON_AddStringToObject(pkt, "terminalId",    TERMINAL_ID);
    cJSON_AddStringToObject(pkt, "electionId",    g_electionCfg.electionId.c_str());
    cJSON_AddStringToObject(pkt, "cardBurnProof", burn);
    char *pkt_str = cJSON_PrintUnformatted(pkt);
    cJSON_Delete(pkt);

    std::string encrypted = crypto_aes_gcm_encrypt(std::string(pkt_str));
    free(pkt_str);

    cJSON *outer = cJSON_CreateObject();
    cJSON_AddStringToObject(outer, "payload", encrypted.c_str());
    char *body_str = cJSON_PrintUnformatted(outer);
    cJSON_Delete(outer);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/vote", HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    net_wifi_reconnect_if_needed();
    esp_err_t err = esp_http_client_perform(h);
    int code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);
    free(r.buf);

    if (err == ESP_OK && code == 200) {
        ESP_LOGI(TAG, "Recovery successful! Clearing stale NVS tracking allocation.");
        if (nvs_open("vote_recovery", NVS_READWRITE, &h_rec) == ESP_OK) {
            nvs_erase_key(h_rec, "pending_burn");
            nvs_erase_key(h_rec, "pending_token");
            nvs_erase_key(h_rec, "pending_candidate");
            nvs_erase_key(h_rec, "pending_uid");
            nvs_commit(h_rec);
            nvs_close(h_rec);
        }
        return true;
    }

    ESP_LOGE(TAG, "Recovery push failed (HTTP %d). Payload preserved for next retry cycle.", code);
    return false;
}
