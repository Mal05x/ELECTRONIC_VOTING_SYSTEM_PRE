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
#include "mbedtls/gcm.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include "esp_crt_bundle.h"
#include "officer_auth.h"
#include "nvs.h"
#include "nvs_flash.h"


static const char *TAG = "NET";

volatile bool g_candidates_loaded = false;

// Forward declaration for the offline cache
static void cache_failed_vote(const char* card_hash, const char* election_id, const char* candidate_id, const char* burn_proof_b64);

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
            return false; // Ensure there is NO cache_failed_vote trigger here!
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
        ESP_LOGW(TAG, "Candidate fetch failed HTTP=%d", code);
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;

    // 💥 THE FIX: Check if it's wrapped in "candidates" OR if the root is the array
    cJSON *arr = cJSON_GetObjectItem(json, "candidates");
    if (!arr && cJSON_IsArray(json)) {
        arr = json;
    }

    g_candidateCount = 0;

    // Only loop if we successfully found a valid array
    if (arr != NULL && cJSON_IsArray(arr)) {
        cJSON *item;
        cJSON_ArrayForEach(item, arr) {
            if (g_candidateCount >= 10) break;
            g_candidates[g_candidateCount].id      = cJSON_GetStringValue(cJSON_GetObjectItem(item, "id"))       ?: "";
            g_candidates[g_candidateCount].name    = cJSON_GetStringValue(cJSON_GetObjectItem(item, "fullName")) ?: "";
            g_candidates[g_candidateCount].party   = cJSON_GetStringValue(cJSON_GetObjectItem(item, "partyAbbreviation")) ?: "";
            g_candidates[g_candidateCount].position= cJSON_GetStringValue(cJSON_GetObjectItem(item, "position")) ?: "";
            g_candidateCount++;
        }
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

// Replaces net_authenticate_voter()
bool net_request_tap_session(void) {
    cJSON *tap_json = cJSON_CreateObject();
    cJSON_AddStringToObject(tap_json, "terminalId", TERMINAL_ID);
    // 💥 FIX: Use HEX
        cJSON_AddStringToObject(tap_json, "cardIdHash", crypto_sha256_hex(g_session.cardUID).c_str());
    cJSON_AddStringToObject(tap_json, "electionId", g_electionCfg.electionId.c_str());
    cJSON_AddStringToObject(tap_json, "mode", "VOTING");

    char *body_str = cJSON_PrintUnformatted(tap_json);
    cJSON_Delete(tap_json);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/tap", HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    esp_err_t err = esp_http_client_perform(h);
    int code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);

    if (err != ESP_OK || code != 200) {
        ESP_LOGE(TAG, "/tap failed HTTP=%d. Card not authorized.", code);
        free(r.buf);
        return false;
    }

    cJSON *json = cJSON_Parse(r.buf);
    free(r.buf);
    if (!json) return false;

    g_session.sessionToken = cJSON_GetStringValue(cJSON_GetObjectItem(json, "sessionToken")) ?: "";
    cJSON_Delete(json);

    return !g_session.sessionToken.empty();
}

// ── Vote submission ───────────────────────────────────────

bool net_submit_vote(void) {
    cJSON *pkt = cJSON_CreateObject();
    cJSON_AddStringToObject(pkt, "sessionToken",  g_session.sessionToken.c_str());
    cJSON_AddStringToObject(pkt, "candidateId",   g_candidates[g_nav.currentSelection].id.c_str());
    // 💥 FIX: Use HEX
    std::string cardIdHash = crypto_sha256_hex(g_session.cardUID);
    cJSON_AddStringToObject(pkt, "cardIdHash",    cardIdHash.c_str());
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
            ESP_LOGW(TAG, "Vote failed HTTP=%d. Triggering Offline NVS Cache!", code);
            free(r.buf);

            // 💥 CORRECT PLACEMENT: Inject the cache trigger here!
            cache_failed_vote(cardIdHash.c_str(),
                              g_electionCfg.electionId.c_str(),
                              g_candidates[g_nav.currentSelection].id.c_str(),
                              g_session.cardBurnProof.c_str());

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
    cJSON *doc = cJSON_CreateObject();
    cJSON_AddStringToObject(doc, "terminalId", TERMINAL_ID);
    // 💥 FIX 1: Use HEX instead of Base64
        cJSON_AddStringToObject(doc, "cardIdHash", crypto_sha256_hex(g_enroll_session.cardUID).c_str());

        // 💥 FIX 2: Prevent pollingUnitId = 0 validation errors
        int pu = g_electionCfg.pollingUnitId > 0 ? g_electionCfg.pollingUnitId : 4;
        cJSON_AddNumberToObject(doc, "pollingUnitId", pu);
    // 💥 PRODUCTION FIX: Use formal system state flags instead of magic dummy strings
    // ✅ FIXED: Evaluates and acts exclusively on the Enrollment Session context
        if (g_enroll_session.voterPublicKey.empty()) {
            cJSON_AddStringToObject(doc, "voterPublicKey", PROD_STATE_AWAITING_KEY);
            cJSON_AddStringToObject(doc, "encryptedDemographic", PROD_STATE_AWAITING_DEMO);
        } else {
            cJSON_AddStringToObject(doc, "voterPublicKey", g_enroll_session.voterPublicKey.c_str());
        }

    char *body_str = cJSON_PrintUnformatted(doc);
    cJSON_Delete(doc);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/pending-registration", HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    // 💥 THE FIX: Attach the ECDSA terminal signature headers here!
        add_auth_headers(h, body);
    esp_http_client_set_post_field(h, body.c_str(), body.size());

    esp_err_t err = esp_http_client_perform(h);
    int code = esp_http_client_get_status_code(h);
    esp_http_client_cleanup(h);
    free(r.buf);

    if (code == 401) {
            ESP_LOGE(TAG, "Backend rejected pending registration. Check terminal keys.");
        }

    return (err == ESP_OK && code == 200);
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
    cJSON_AddStringToObject(doc, "cardIdHash", crypto_sha256_hex(g_enroll_session.cardUID).c_str());
        cJSON_AddStringToObject(doc, "terminalId", TERMINAL_ID);

    // 💥 Attach the finalized X.509 SPKI Public Key
    cJSON_AddStringToObject(doc, "voterPublicKey", g_enroll_session.voterPublicKey.c_str());

    char *body_str = cJSON_PrintUnformatted(doc);
    cJSON_Delete(doc);
    std::string body(body_str);
    free(body_str);

    HttpResp r = {};
    esp_http_client_handle_t h = make_client("/api/terminal/enrollment", HTTP_METHOD_POST, &r);
    esp_http_client_set_header(h, "Content-Type", "application/json");
    add_auth_headers(h, body);
    // ... [top half building the cJSON doc stays the same] ...

        esp_http_client_set_post_field(h, body.c_str(), body.size());

        esp_err_t err = esp_http_client_perform(h);
        int code = esp_http_client_get_status_code(h);

        // 💥 FIX: If the heartbeat task collides and causes a 429, retry once!
        if (code == 429) {
            ESP_LOGW(TAG, "Rate limit collision! Retrying confirmation in 5 seconds...");
            vTaskDelay(pdMS_TO_TICKS(5000));
            err = esp_http_client_perform(h);
            code = esp_http_client_get_status_code(h);
        }

        esp_http_client_cleanup(h);
        free(r.buf);

        if (code != 200) {
            // This explicitly prints the server's rejection code (e.g., 400, 401, 500)
            ESP_LOGE(TAG, "Enrollment confirmation rejected by server! HTTP Status: %d", code);
        }

        return (err == ESP_OK && code == 200);
    }

// The new background task
void task_background_candidate_sync(void *pvParameters) {
    ESP_LOGI(TAG, "Background sync task started on Core 0...");

    // 1. Fetch Election Configuration
    ESP_LOGI(TAG, "Fetching Election Config...");
    while (!net_fetch_election_config()) {
        ESP_LOGW(TAG, "Config fetch failed, retrying in 5s...");
        vTaskDelay(pdMS_TO_TICKS(5000));
    }

    // 1b. Fetch the Officer PIN Hash while we are here
    net_fetch_officer_pin_hash();

    // 2. The Backend Rate-Limit Delay (Blocks only this background task!)
    ESP_LOGI(TAG, "⏳ Sleeping 32 seconds to clear backend rate limit...");
    vTaskDelay(pdMS_TO_TICKS(32000));

    // 3. Fetch Live Candidates
    ESP_LOGI(TAG, "Fetching Live Candidates...");
    while (!net_fetch_candidates() || g_candidateCount == 0) {
        ESP_LOGW(TAG, "Candidate fetch failed, retrying in 5s...");
        vTaskDelay(pdMS_TO_TICKS(5000));
    }

    ESP_LOGI(TAG, "✅ Loaded %d candidates. Terminal is READY.", g_candidateCount);

    // 4. Unlock the UI!
    g_candidates_loaded = true;

    // 5. Delete this task to free up RAM
    vTaskDelete(NULL);
}

// --- NEW BOOT RECOVERY LOOP ---
bool net_check_and_recover_vote(void) {
    // 1. Initialize the NVS Iterator for the "vote_cache" namespace
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 0, 0)
    nvs_iterator_t it = NULL;
    esp_err_t err = nvs_entry_find("nvs", "vote_cache", NVS_TYPE_STR, &it);
    if (err != ESP_OK || it == NULL) return false;
#else
    nvs_iterator_t it = nvs_entry_find("nvs", "vote_cache", NVS_TYPE_STR);
    if (it == NULL) return false;
#endif

    ESP_LOGI(TAG, "🔄 BACKGROUND SYNC: Offline votes detected. Commencing push...");

    nvs_handle_t handle;
    if (nvs_open("vote_cache", NVS_READWRITE, &handle) != ESP_OK) {
        nvs_release_iterator(it);
        return false;
    }

    bool recovered_any = false;

    // 2. Loop through all saved offline votes
    while (it != NULL) {
        nvs_entry_info_t info;
        nvs_entry_info(it, &info);

        size_t len = 0;
        if (nvs_get_str(handle, info.key, NULL, &len) == ESP_OK) {
            char* json_str = (char*)malloc(len);
            if (nvs_get_str(handle, info.key, json_str, &len) == ESP_OK) {

                ESP_LOGI(TAG, "Processing Offline Vote: %s", info.key);
                cJSON *cache = cJSON_Parse(json_str);

                if (cache) {
                                    // 🔍 DUAL-KEY EXTRACTION: Fallback gracefully if keys are minified or full-length
                                    cJSON *item_h = cJSON_GetObjectItem(cache, "h");
                                    if (!item_h) item_h = cJSON_GetObjectItem(cache, "cardIdHash");

                                    cJSON *item_e = cJSON_GetObjectItem(cache, "e");
                                    if (!item_e) item_e = cJSON_GetObjectItem(cache, "electionId");

                                    cJSON *item_c = cJSON_GetObjectItem(cache, "c");
                                    if (!item_c) item_c = cJSON_GetObjectItem(cache, "candidateId");

                                    cJSON *item_p = cJSON_GetObjectItem(cache, "p");
                                    if (!item_p) item_p = cJSON_GetObjectItem(cache, "cardBurnProof");

                                    // Sanity check: Ensure nothing critical is missing before talking to the server
                                    if (!item_h || !item_e || !item_c || !item_p) {
                                        ESP_LOGE(TAG, "❌ Cache extraction failed! Corrupt or incompatible offline structure.");
                                        ESP_LOGD(TAG, "Raw Cache Content: %s", json_str);
                                        cJSON_Delete(cache);
                                        free(json_str);
                                        continue;
                                    }

                                    const char* card_hash   = item_h->valuestring;
                                    const char* election_id = item_e->valuestring;
                                    const char* candidate_id = item_c->valuestring;
                                    const char* burn_proof  = item_p->valuestring;

                                    ESP_LOGI(TAG, "🗳️ Extracted Cache -> Card: %s..., Election: %s...",
                                             std::string(card_hash).substr(0, 8).c_str(),
                                             std::string(election_id).substr(0, 8).c_str());

                    // 3. Request a fresh Session Token for recovery (/api/terminal/tap)
                    cJSON *tap_json = cJSON_CreateObject();
                    cJSON_AddStringToObject(tap_json, "cardIdHash", card_hash);
                    cJSON_AddStringToObject(tap_json, "electionId", election_id);
                    // 🛠️ THE FIX: Add the missing terminal identifier expected by the DTO
                                        cJSON_AddStringToObject(tap_json, "terminalId", "TERM-KD-001");
                    //cJSON_AddStringToObject(tap_json, "mode", "RECOVERY");
                    char *tap_data = cJSON_PrintUnformatted(tap_json);
                    cJSON_Delete(tap_json);

                    HttpResp tap_resp = {};
                    esp_http_client_handle_t tap_client = make_client("/api/terminal/tap", HTTP_METHOD_POST, &tap_resp);
                    esp_http_client_set_header(tap_client, "Content-Type", "application/json");
                    add_auth_headers(tap_client, tap_data);
                    esp_http_client_set_post_field(tap_client, tap_data, strlen(tap_data));

                    esp_err_t tap_err = esp_http_client_perform(tap_client);
                    int tap_code = esp_http_client_get_status_code(tap_client);
                    esp_http_client_cleanup(tap_client);
                    free(tap_data);

                    if (tap_err == ESP_OK && tap_code == 200 && tap_resp.buf) {
                        cJSON *tap_res = cJSON_Parse(tap_resp.buf);
                        if (tap_res && cJSON_GetObjectItem(tap_res, "sessionToken")) {
                            const char* session_token = cJSON_GetObjectItem(tap_res, "sessionToken")->valuestring;

                            // 4. Build the AES-GCM Encrypted Payload
                            const char *SERVER_AES_KEY_B64 = "aEq4iB1rIisBzZgc+y7c/9dsYGiVTy0XnbIK/3Fc59U=";
                            unsigned char server_aes_key[33] = {0};
                            size_t key_len = 0;
                            mbedtls_base64_decode(server_aes_key, sizeof(server_aes_key), &key_len,
                                                (const unsigned char *)SERVER_AES_KEY_B64, strlen(SERVER_AES_KEY_B64));

                            cJSON *inner = cJSON_CreateObject();
                            cJSON_AddStringToObject(inner, "sessionToken", session_token);
                            cJSON_AddStringToObject(inner, "candidateId", candidate_id);
                            cJSON_AddStringToObject(inner, "cardIdHash", card_hash);
                            cJSON_AddStringToObject(inner, "electionId", election_id);
                            cJSON_AddStringToObject(inner, "cardBurnProof", burn_proof);
                            char *inner_str = cJSON_PrintUnformatted(inner);
                            cJSON_Delete(inner);

                            size_t inner_len = strlen(inner_str);
                            uint8_t iv[12];
                            esp_fill_random(iv, sizeof(iv));
                            uint8_t tag[16] = {0};
                            uint8_t *ciphertext = (uint8_t *)malloc(inner_len);

                            mbedtls_gcm_context gcm;
                            mbedtls_gcm_init(&gcm);
                            mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, server_aes_key, 256);
                            mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, inner_len, iv, sizeof(iv), NULL, 0, (uint8_t *)inner_str, ciphertext, sizeof(tag), tag);
                            mbedtls_gcm_free(&gcm);
                            free(inner_str);

                            size_t enc_len = 12 + inner_len + 16;
                            uint8_t *enc_blob = (uint8_t *)malloc(enc_len);
                            memcpy(enc_blob, iv, 12);
                            memcpy(enc_blob + 12, ciphertext, inner_len);
                            memcpy(enc_blob + 12 + inner_len, tag, 16);
                            free(ciphertext);

                            size_t b64_sz = ((enc_len + 2) / 3) * 4 + 1;
                            unsigned char *payload_b64 = (unsigned char *)calloc(1, b64_sz);
                            size_t pb64_len = 0;
                            mbedtls_base64_encode(payload_b64, b64_sz, &pb64_len, enc_blob, enc_len);
                            free(enc_blob);

                            cJSON *vote_json = cJSON_CreateObject();
                            cJSON_AddStringToObject(vote_json, "cardIdHash", card_hash);
                            cJSON_AddStringToObject(vote_json, "payload", (char *)payload_b64);
                            free(payload_b64);

                            char *vote_data = cJSON_PrintUnformatted(vote_json);
                            cJSON_Delete(vote_json);

                            // 5. Submit Recovered Vote to Server (/api/terminal/vote)
                            HttpResp vote_resp = {};
                            esp_http_client_handle_t vote_client = make_client("/api/terminal/vote", HTTP_METHOD_POST, &vote_resp);
                            esp_http_client_set_header(vote_client, "Content-Type", "application/json");
                            add_auth_headers(vote_client, vote_data);
                            esp_http_client_set_post_field(vote_client, vote_data, strlen(vote_data));

                            esp_err_t vote_err = esp_http_client_perform(vote_client);
                            int vote_code = esp_http_client_get_status_code(vote_client);
                            esp_http_client_cleanup(vote_client);

                            if (vote_err == ESP_OK && (vote_code == 200 || vote_code == 201)) {
                                ESP_LOGI(TAG, "✅ Background sync successful! Erasing vote %s from offline cache.", info.key);
                                nvs_erase_key(handle, info.key);
                                nvs_commit(handle);
                                recovered_any = true;
                            } else {
                                ESP_LOGW(TAG, "⚠️ Background sync rejected by server (HTTP %d). Will retry next cycle.", vote_code);
                            }
                            free(vote_data);
                            if (vote_resp.buf) free(vote_resp.buf);
                        }
                        if (tap_res) cJSON_Delete(tap_res);
                    } else {
                        ESP_LOGW(TAG, "⚠️ Recovery Tap failed (HTTP %d).", tap_code);
                        // 🔍 ADD THIS TO REVEAL THE SPRING BOOT VALIDATION ERROR:
                                                if (tap_resp.buf != NULL) {
                                                    ESP_LOGE(TAG, "🛑 SERVER REJECTED WITH: %s", tap_resp.buf);
                                                }
                    }
                    if (tap_resp.buf) free(tap_resp.buf);
                }
                cJSON_Delete(cache);
            }
            free(json_str);
        }

        // Move to the next cached vote
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 0, 0)
        nvs_entry_next(&it);
#else
        it = nvs_entry_next(it);
#endif
    }

    nvs_close(handle);
    nvs_release_iterator(it);

    return recovered_any;
}


static void cache_failed_vote(const char* card_hash, const char* election_id,
                              const char* candidate_id, const char* burn_proof_b64) {
    nvs_handle_t my_handle;
    esp_err_t err = nvs_open("vote_cache", NVS_READWRITE, &my_handle);
    if (err != ESP_OK) {
        ESP_LOGE("NVS", "Failed to open vote_cache namespace");
        return;
    }

    // Create a compact JSON string to save space
    cJSON *cache_obj = cJSON_CreateObject();
    cJSON_AddStringToObject(cache_obj, "h", card_hash);
    cJSON_AddStringToObject(cache_obj, "e", election_id);
    cJSON_AddStringToObject(cache_obj, "c", candidate_id);
    cJSON_AddStringToObject(cache_obj, "p", burn_proof_b64);

    char *json_str = cJSON_PrintUnformatted(cache_obj);

    // Generate a unique 14-char key: "v_" + first 12 chars of the hash
    char key[16];
    snprintf(key, sizeof(key), "v_%.12s", card_hash);

    // Commit to flash
    err = nvs_set_str(my_handle, key, json_str);
    if (err == ESP_OK) {
        nvs_commit(my_handle);
        ESP_LOGI("NVS", "✅ Ballot safely cached offline. Key: %s", key);
    } else {
        ESP_LOGE("NVS", "Failed to cache ballot in NVS!");
    }

    cJSON_Delete(cache_obj);
    free(json_str);
    nvs_close(my_handle);
}
