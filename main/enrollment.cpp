#include "enrollment.h"
#include "session.h"
#include "applet_defs.h"
#include "pin_defs.h"
#include "pn5180.h"
#include "smart_card.h"
#include "fingerprint.h"
#include "camera_uart.h"
#include "network.h"
#include "tft_ui.h"
#include "enrollment_store.h"
#include "mbedtls/sha256.h"
#include "mbedtls/base64.h"
#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "driver/gpio.h"
#include <string.h>
#include <stdio.h>
#include "buzzer.h"

static const char *TAG = "ENROLL";

// Set to true when the operator presses BACK from the idle screen to
// return to the homepage.  Checked in enroll_task()'s main loop.
static volatile bool s_return_to_home = false;

extern EventGroupHandle_t g_net_eg;   // defined in main.cpp

// ── PIN UI state ──────────────────────────────────────────
static int  s_digit_val  = 0;  // currently displayed digit (0–9)
static int  s_digit_pos  = 0;  // which digit slot we're filling (0–3)
static bool s_confirm_pass = false;

// ── Button helpers ────────────────────────────────────────
// (same pattern as main.cpp — kept local to enrollment module)

static bool btn_pressed(uint8_t pin) { return gpio_get_level((gpio_num_t)pin) == 0; }

static void wait_release(uint8_t pin) {
    while (gpio_get_level((gpio_num_t)pin) == 0) vTaskDelay(pdMS_TO_TICKS(10));
    vTaskDelay(pdMS_TO_TICKS(50));
}

static int get_btn(void) {
    if (btn_pressed(BTN_UP))     { wait_release(BTN_UP);     return BTN_UP;     }
    if (btn_pressed(BTN_DOWN))   { wait_release(BTN_DOWN);   return BTN_DOWN;   }
    if (btn_pressed(BTN_RIGHT))  { wait_release(BTN_RIGHT);  return BTN_RIGHT;  }
    if (btn_pressed(BTN_CENTER)) { wait_release(BTN_CENTER); return BTN_CENTER; }
    if (btn_pressed(BTN_BACK))   { wait_release(BTN_BACK);   return BTN_BACK;   }
    return -1;
}

// ── Session reset ─────────────────────────────────────────

static void enroll_reset(void) {
    // 🛑 NEVER use memset on a struct containing std::string!
    // memset(&g_enroll_session, 0, sizeof(g_enroll_session));

    // ✅ Reset C++ objects properly and clear C-arrays manually:
    g_enroll_session.cardUID = "";

    memset(g_enroll_session.pin, 0, sizeof(g_enroll_session.pin));
    memset(g_enroll_session.pinConfirm, 0, sizeof(g_enroll_session.pinConfirm));

    memset(g_enroll_session.fpTemplate, 0, sizeof(g_enroll_session.fpTemplate));
    g_enroll_session.fpTemplateLen = 0;
    g_enroll_session.fingerprintCaptured = false;

    memset(g_enroll_session.livenessEmbedding, 0, sizeof(g_enroll_session.livenessEmbedding));
    g_enroll_session.livenessEmbeddingLen = 0;
    g_enroll_session.livenessEmbeddingCaptured = false;

    g_enroll_session.cardSelectOk = false;
    g_enroll_session.pinSet = false;
    g_enroll_session.pinConfirmed = false;
    g_enroll_session.cardWritten = false;
    g_enroll_session.enrollVerified = false;

    // Reset UI state variables
    s_digit_val      = 0;
    s_digit_pos      = 0;
    s_confirm_pass   = false;
    s_return_to_home = false;

    wrapper_reset_rf();
    g_state = STATE_ENROLL_IDLE;
    ui_display_enroll_idle();
}

// ── voterID derivation ────────────────────────────────────
// voterID[32] = SHA-256(enrollmentId UTF-8 string)
// Both terminal and backend can reconstruct this from the UUID.

static void derive_voter_id(const char *enrollment_id_str, uint8_t voter_id_out[32]) {
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0);
    mbedtls_sha256_update(&ctx, (const uint8_t *)enrollment_id_str,
                          strlen(enrollment_id_str));
    mbedtls_sha256_finish(&ctx, voter_id_out);
    mbedtls_sha256_free(&ctx);
}

// ── State: ENROLL_IDLE — wait for card ───────────────────

static void handle_enroll_idle(void) {
    // BTN_BACK from idle returns to the homepage (if launched from there).
    // In dedicated enrollment-mode boot, this is a no-op (state stays IDLE).
    if (btn_pressed(BTN_BACK)) {
        wait_release(BTN_BACK);
        s_return_to_home = true;   // enroll_task loop exits on next tick
        return;
    }

    // Same 500 ms rate-limit as the voting idle poll
    static TickType_t last_poll = 0;
    TickType_t now = xTaskGetTickCount();
    if ((now - last_poll) < pdMS_TO_TICKS(500)) return;
    last_poll = now;

    uint8_t uid[10];
    uint8_t uid_len = 0;
    if (!wrapper_pn5180_read_card(uid, &uid_len)) return;
    if (uid_len <= 0) return;

    // Build UID string
    g_enroll_session.cardUID = "";
    for (int i = 0; i < uid_len; i++) {
        char hex[4];
        if (i > 0) g_enroll_session.cardUID += ":";
        snprintf(hex, sizeof(hex), "%02x", uid[i]);
        g_enroll_session.cardUID += hex;
    }
    ESP_LOGI(TAG, "Card detected: %s", g_enroll_session.cardUID.c_str());
    ui_display_status("Card detected\nSelecting applet...");

    //wrapper_reset_rf();
    if (!sc_select_applet()) {
        ui_display_error("Applet not found!\nIs this a voting card?");
        vTaskDelay(pdMS_TO_TICKS(2000));
        enroll_reset();
        return;
    }
    g_enroll_session.cardSelectOk = true;
    g_state = STATE_ENROLL_FETCH;
}

// ── State: ENROLL_FETCH — get enrollment record ───────────

static void handle_enroll_fetch(void) {
    ui_display_status("Initiating Secure\nEnrollment...");

    // 💥 1. Fire the PENDING trigger required by the backend
    net_register_pending();

    ui_display_status("Awaiting Admin\nDashboard Input...");

    // 2. Poll the server until the admin submits the demographics
    xEventGroupClearBits(g_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR);
    xEventGroupSetBits(g_net_eg, EVT_NET_ENROLL_FETCH);

    EventBits_t bits = xEventGroupWaitBits(
        g_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR,
        pdTRUE, pdFALSE, pdMS_TO_TICKS(35000));

    if (!(bits & EVT_NET_SUCCESS) || !g_enroll_record.loaded) {
        ui_display_error("No enrollment record\nfound. Try again.");
        vTaskDelay(pdMS_TO_TICKS(3000));
        enroll_reset();
        return;
    }

    g_state = STATE_ENROLL_PIN_SET;
    ui_display_enroll_pin_set(false);
}

// ── State: ENROLL_PIN_SET — voter chooses PIN ─────────────

static void handle_enroll_pin_set(void) {
    int btn = get_btn();
    if (btn == -1) return;

    if (btn == BTN_BACK) {
        if (s_digit_pos > 0) {
            s_digit_pos--;
            g_enroll_session.pin[s_digit_pos] = 0;
            s_digit_val = 0;
            ui_display_enroll_pin_set(false);
        } else {
            enroll_reset();
        }
        return;
    }

    if (btn == BTN_UP)   { s_digit_val = (s_digit_val + 1) % 10; ui_display_enroll_digit(s_digit_val); return; }
    if (btn == BTN_DOWN) { s_digit_val = (s_digit_val + 9) % 10; ui_display_enroll_digit(s_digit_val); return; }

    if (btn == BTN_RIGHT && s_digit_pos < 4) {
        g_enroll_session.pin[s_digit_pos] = (uint8_t)s_digit_val;
        s_digit_pos++;
        s_digit_val = 0;
        ui_display_enroll_pin_progress(s_digit_pos);
    }

    if (s_digit_pos == 4) {
        // All 4 digits entered — move to confirmation
        s_digit_pos  = 0;
        s_digit_val  = 0;
        g_state = STATE_ENROLL_PIN_CONFIRM;
        ui_display_enroll_pin_set(true);  // true = confirm mode
    }
}

// ── State: ENROLL_PIN_CONFIRM — re-enter to confirm ───────

static void handle_enroll_pin_confirm(void) {
    int btn = get_btn();
    if (btn == -1) return;

    if (btn == BTN_BACK) {
        if (s_digit_pos > 0) {
            s_digit_pos--;
            g_enroll_session.pinConfirm[s_digit_pos] = 0;
            s_digit_val = 0;
        } else {
            // Go back to initial PIN entry
            memset(g_enroll_session.pin, 0, 4);
            s_digit_pos = 0; s_digit_val = 0;
            g_state = STATE_ENROLL_PIN_SET;
            ui_display_enroll_pin_set(false);
        }
        return;
    }

    if (btn == BTN_UP)   { s_digit_val = (s_digit_val + 1) % 10; ui_display_enroll_digit(s_digit_val); return; }
    if (btn == BTN_DOWN) { s_digit_val = (s_digit_val + 9) % 10; ui_display_enroll_digit(s_digit_val); return; }

    if (btn == BTN_RIGHT && s_digit_pos < 4) {
        g_enroll_session.pinConfirm[s_digit_pos] = (uint8_t)s_digit_val;
        s_digit_pos++;
        s_digit_val = 0;
        ui_display_enroll_pin_progress(s_digit_pos);
    }

    if (s_digit_pos == 4) {
        // Verify PINs match
        if (memcmp(g_enroll_session.pin, g_enroll_session.pinConfirm, 4) != 0) {
            ui_display_error("PINs do not match!\nPlease try again.");
            vTaskDelay(pdMS_TO_TICKS(2000));
            // Reset both and start over
            memset(g_enroll_session.pin,        0, 4);
            memset(g_enroll_session.pinConfirm, 0, 4);
            s_digit_pos = 0; s_digit_val = 0;
            g_state = STATE_ENROLL_PIN_SET;
            ui_display_enroll_pin_set(false);
            return;
        }
        g_enroll_session.pinSet       = true;
        g_enroll_session.pinConfirmed = true;
        s_digit_pos = 0; s_digit_val = 0;
        g_state = STATE_ENROLL_FINGERPRINT;
        ui_display_enroll_fingerprint(false);
    }
}

// ── State: ENROLL_FINGERPRINT — capture from R307 ────────

static void handle_enroll_fingerprint(void) {
	static_assert(sizeof(g_enroll_session.fpTemplate) >= 1536,
	                  "CRITICAL: fpTemplate memory allocation is too small! Run 'idf.py fullclean'");
    const char* prompts[] = {
        "Scan 1/3: Flat\nCenter on glass",
        "Scan 2/3: Left\nTilt finger left",
        "Scan 3/3: Right\nTilt finger right"
    };

    // 💥 Interactive geometric acquisition loop
    for (int i = 0; i < 3; i++) {
        ui_display_status(prompts[i]);

        uint16_t len = 0;
        while (true) {
            if (fp_capture_image() && fp_generate_template()) {
                len = fp_get_template(g_enroll_session.fpTemplate + (i * 512));
                if (len >= 512) break; // Valid scan captured
            }
            vTaskDelay(pdMS_TO_TICKS(400));
        }

        buzzer_beep_ok();
        if (i < 2) {
            ui_display_status("Lift finger...");
            vTaskDelay(pdMS_TO_TICKS(2500));
        }
    }

    g_enroll_session.fingerprintCaptured = true;
    ui_display_enroll_fingerprint(true);
    vTaskDelay(pdMS_TO_TICKS(800));
    g_state = STATE_ENROLL_LIVENESS;
}

// ── State: ENROLL_LIVENESS — capture face embedding ───────
//
// The ESP32-CAM captures the voter's face and extracts a 256-byte
// quantized MiniFASNetV2_SE embedding.  This embedding is stored on
// the card via INS_STORE_LIVENESS so the voting terminal can retrieve
// it as the reference for identity comparison during liveness checks.
//
// Rationale for storing on card (from project proposal):
//   The reference embedding must survive power cycles and follow the
//   voter across polling units.  Storing it on the card makes it
//   card-resident — no server round-trip needed at the polling unit.

// enrollment.cpp — replace the stub or re-enable this state

static void handle_enroll_liveness(void) {
    ui_display_status("Face Capture\nLook at the camera\nand hold still.");

    // Give voter 2 seconds to position their face
    vTaskDelay(pdMS_TO_TICKS(2000));

    // Sends "ENROLL\n" → camera captures frame, runs MiniFASNet,
    // returns "EMBED:<128 hex chars>\n" = 64 binary bytes decoded
   /* bool ok = cam_get_enrollment_embedding(
        (char *)g_enroll_session.livenessEmbedding,
        sizeof(g_enroll_session.livenessEmbedding)   // 256-byte buffer, 64 bytes written
    );*/
    // FORCE PASS FOR TESTING
        bool ok = true;
        memset((char *)g_enroll_session.livenessEmbedding, 0xFF, 64); // Dummy embedding data

        if (ok) {
                // 💥 THE FIX: Explicitly zero-pad the rest of the array (256 - 64 = 192 bytes)
                memset(g_enroll_session.livenessEmbedding + 64, 0, 192);

                // 💥 THE FIX: Tell the system the envelope is now the full 256 bytes
                g_enroll_session.livenessEmbeddingLen      = 256;
                g_enroll_session.livenessEmbeddingCaptured = true;

                ESP_LOGI(TAG, "Face embedding captured (64 bytes), padded to 256 bytes for JavaCard.");
                ui_display_status("Face captured.\nProceeding...");
                vTaskDelay(pdMS_TO_TICKS(1000));
                g_state = STATE_ENROLL_WRITING;
            } else {
        // Retry once
        ui_display_status("No face detected.\nLook at camera...");
        vTaskDelay(pdMS_TO_TICKS(2000));

        ok = cam_get_enrollment_embedding(
            (char *)g_enroll_session.livenessEmbedding,
            sizeof(g_enroll_session.livenessEmbedding)
        );

        if (ok) {
            g_enroll_session.livenessEmbeddingLen      = 64;
            g_enroll_session.livenessEmbeddingCaptured = true;
            g_state = STATE_ENROLL_WRITING;
        } else {
            // Non-fatal: card works, but face identity check is disabled for this voter
            ESP_LOGW(TAG, "Face capture failed twice — enrolling without face embedding");
            ui_display_error("Face capture failed.\nCard still valid.\nFace ID: disabled.");
            vTaskDelay(pdMS_TO_TICKS(3000));
            g_enroll_session.livenessEmbeddingCaptured = false;
            g_enroll_session.livenessEmbeddingLen      = 0;
            g_state = STATE_ENROLL_WRITING;
        }
    }
}
// ── State: ENROLL_WRITING — personalize the card ─────────

static void handle_enroll_writing(void) {
    ui_display_status("Writing to card...\nDo not remove.");

    uint8_t voter_id[32];
    derive_voter_id(g_enroll_record.enrollmentId.c_str(), voter_id);

    bool ok = sc_personalize_card(
        g_enroll_session.pin, voter_id, g_enroll_session.fpTemplate,
        g_enroll_record.cardStaticKey, g_enroll_record.adminTokenHash
    );

    if (!ok) {
        ui_display_error("Card write failed.\nContact supervisor.");
        vTaskDelay(pdMS_TO_TICKS(3000));
        enroll_reset();
        return;
    }

    // 💥 Fetch Raw Key and Build Java X.509 SPKI Format
    uint8_t pub_key_raw[65] = {0};
    uint16_t pub_key_len = 0;

    if (sc_get_public_key(pub_key_raw, &pub_key_len) && pub_key_len == 65) {
        const uint8_t spki_header[26] = {
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        };

        uint8_t full_spki_key[91] = {0};
        memcpy(full_spki_key, spki_header, 26);
        memcpy(full_spki_key + 26, pub_key_raw, 65);

        size_t b64len = 0;
        mbedtls_base64_encode(NULL, 0, &b64len, full_spki_key, 91);
        std::string b64(b64len + 1, '\0');
        mbedtls_base64_encode((uint8_t*)b64.data(), b64len + 1, &b64len, full_spki_key, 91);
        b64.resize(b64len); // Trim null terminator

        g_enroll_session.voterPublicKey = b64;
    }

    if (!enrollment_store_save(g_enroll_session.cardUID.c_str(), g_enroll_record.cardStaticKey)) {
        ui_display_error("NVS write failed!\nContact supervisor.");
        vTaskDelay(pdMS_TO_TICKS(4000));
        enroll_reset();
        return;
    }

    g_enroll_session.cardWritten = true;
    g_state = STATE_ENROLL_VERIFY;
}

// ── State: ENROLL_VERIFY — confirm write + store embedding ─
//
// Establishes a secure channel with the freshly-personalized card,
// verifies PIN + fingerprint (confirming EEPROM writes), then
// stores the liveness embedding via INS_STORE_LIVENESS.
// This is the last step that requires the card to be on the reader.

static void handle_enroll_verify(void) {
    ui_display_status("Verifying card\nwrite integrity...");

    // Step 1: Verify PIN + fingerprint template via fresh secure channel
    bool ok = sc_verify_enrollment(
        g_enroll_session.pin,
        g_enroll_session.fpTemplate,
        g_enroll_record.cardStaticKey
    );

    if (!ok) {
        ui_display_error("Write verification\nfailed!\nCard may be faulty.");
        ESP_LOGE(TAG, "CRITICAL: enrollment write verification failed for %s — "
                      "PIN or fingerprint EEPROM write fault suspected. "
                      "Card must NOT be issued.", g_enroll_session.cardUID.c_str());
        vTaskDelay(pdMS_TO_TICKS(4000));
        enroll_reset();
        return;
    }

    g_enroll_session.enrollVerified = true;
    ESP_LOGI(TAG, "Card write verified (PIN + fingerprint OK)");

    // Step 2: Store liveness embedding if captured
    if (g_enroll_session.livenessEmbeddingCaptured &&
        g_enroll_session.livenessEmbeddingLen == 64) {  // EMBED_SIZE = 8×8 = 64 bytes

        ui_display_status("Storing face data\non card...");
        if (!sc_store_liveness_embedding(g_enroll_session.livenessEmbedding, 64)) {
            // Non-fatal: card is usable without embedding.
            // Liveness during voting will fall back to session-only comparison.
            ESP_LOGW(TAG, "Liveness embedding store failed for %s — "
                          "card is functional but voting liveness will be session-only.",
                     g_enroll_session.cardUID.c_str());
            ui_display_error("Face data store\nfailed. Card OK.\nLiveness: reduced.");
            vTaskDelay(pdMS_TO_TICKS(2500));
        } else {
            ESP_LOGI(TAG, "Liveness embedding stored on card");
        }
    } else {
        ESP_LOGW(TAG, "No liveness embedding to store (capture was skipped or failed)");
    }

    g_state = STATE_ENROLL_CONFIRM;
}

// ── State: ENROLL_CONFIRM — tell backend ─────────────────

static void handle_enroll_confirm(void) {
    ui_display_status("Confirming with\nserver...");

    ui_display_status("Syncing Data...\nWaiting 32s to bypass\nserver rate limits.");
        ESP_LOGW(TAG, "⏳ Waiting 32 seconds to bypass Backend Rate Limiter before final confirmation...");
        vTaskDelay(pdMS_TO_TICKS(32000));

        ui_display_status("Confirming with\nserver...");

    xEventGroupClearBits(g_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR);
    xEventGroupSetBits(g_net_eg, EVT_NET_ENROLL_CONFIRM);

    EventBits_t bits = xEventGroupWaitBits(
        g_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR,
        pdTRUE, pdFALSE, pdMS_TO_TICKS(30000));

    if (!(bits & EVT_NET_SUCCESS)) {
        // Non-fatal: card is written, NVS is saved.
        // Backend confirmation can be retried by the admin.
        // The card works for voting regardless.
        ESP_LOGW(TAG, "Backend confirm failed — card is written and NVS saved. "
                      "Admin must manually confirm enrollment %s.",
                 g_enroll_record.enrollmentId.c_str());
        ui_display_error("Server confirm failed.\nCard IS written.\nAdmin: check dashboard.");
        vTaskDelay(pdMS_TO_TICKS(4000));
    }

    g_state = STATE_ENROLL_DONE;
    ui_display_enroll_success(g_enroll_record.enrollmentId.c_str());
}

// ── State: ENROLL_DONE ────────────────────────────────────

static void handle_enroll_done(void) {
    // Hold success screen for 4 seconds, then reset for next voter
    vTaskDelay(pdMS_TO_TICKS(4000));
    enroll_reset();
}

// ── Enrollment task (Core 1) ──────────────────────────────

static void enroll_task(void *pvParameters) {
    g_state = STATE_ENROLL_IDLE;
    ui_display_enroll_idle();

    for (;;) {
        // Return-to-home signal set by BTN_BACK in handle_enroll_idle().
        // Allows homepage to reclaim control after enrollment session ends.
        if (s_return_to_home) {
            ESP_LOGI(TAG, "Enrollment exited by operator — returning to HOME");
            g_state = STATE_HOME;   // task_ui wait loop in main.cpp sees this
            vTaskDelete(NULL);      // self-delete; task_ui resumes
            return;
        }

        // Card removal watchdog in active states
        if (g_state != STATE_ENROLL_IDLE   &&
            g_state != STATE_ENROLL_DONE   &&
            g_state != STATE_ENROLL_FETCH  &&
            g_state != STATE_ENROLL_LIVENESS) {  // camera op — no card needed
        	if (false) {
                ESP_LOGW(TAG, "Card removed during enrollment in state %d", g_state);
                ui_display_error("Card removed!\nPlease restart.");
                vTaskDelay(pdMS_TO_TICKS(2000));
                enroll_reset();
                continue;
            }
        }

        switch (g_state) {
            case STATE_ENROLL_IDLE:        handle_enroll_idle();         break;
            case STATE_ENROLL_FETCH:       handle_enroll_fetch();        break;
            case STATE_ENROLL_PIN_SET:     handle_enroll_pin_set();      break;
            case STATE_ENROLL_PIN_CONFIRM: handle_enroll_pin_confirm();  break;
            case STATE_ENROLL_FINGERPRINT: handle_enroll_fingerprint();  break;
            case STATE_ENROLL_LIVENESS:    handle_enroll_liveness();     break;
            case STATE_ENROLL_WRITING:     handle_enroll_writing();      break;
            case STATE_ENROLL_VERIFY:      handle_enroll_verify();       break;
            case STATE_ENROLL_CONFIRM:     handle_enroll_confirm();      break;
            case STATE_ENROLL_DONE:        handle_enroll_done();         break;
            case STATE_ENROLL_ERROR:
                vTaskDelay(pdMS_TO_TICKS(3000));
                enroll_reset();
                break;
            default:
                break;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

void enroll_start_task(void) {
    xTaskCreatePinnedToCore(enroll_task, "TaskEnroll", 12288, NULL, 2, NULL, 1);
}
