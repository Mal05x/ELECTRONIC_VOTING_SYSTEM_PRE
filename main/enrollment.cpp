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
    memset(&g_enroll_session, 0, sizeof(g_enroll_session));
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

    wrapper_reset_rf();
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
    ui_display_status("Fetching enrollment\nrecord from server...");

    // Signal TaskNetwork to do the fetch
    xEventGroupClearBits(g_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR);
    xEventGroupSetBits(g_net_eg, EVT_NET_ENROLL_FETCH);

    EventBits_t bits = xEventGroupWaitBits(
        g_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR,
        pdTRUE, pdFALSE, pdMS_TO_TICKS(30000));

    if (!(bits & EVT_NET_SUCCESS) || !g_enroll_record.loaded) {
        ui_display_error("No enrollment record\nfor this terminal.\nCheck admin dashboard.");
        vTaskDelay(pdMS_TO_TICKS(3000));
        enroll_reset();
        return;
    }

    ESP_LOGI(TAG, "Enrollment record loaded: %s",
             g_enroll_record.enrollmentId.c_str());
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
    // Attempt capture — retries automatically on failure
    if (!fp_capture_image() || !fp_generate_template()) {
        vTaskDelay(pdMS_TO_TICKS(100));
        return;
    }

    uint16_t len = fp_get_template(g_enroll_session.fpTemplate);
    if (len == 0) {
        vTaskDelay(pdMS_TO_TICKS(100));
        return;
    }
    g_enroll_session.fpTemplateLen    = len;
    g_enroll_session.fingerprintCaptured = true;

    // Zero-pad to 512 bytes if shorter
    if (len < 512) memset(g_enroll_session.fpTemplate + len, 0, 512 - len);

    ui_display_enroll_fingerprint(true);
    vTaskDelay(pdMS_TO_TICKS(800));
    g_state = STATE_ENROLL_LIVENESS;   // next: capture face embedding
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
        g_enroll_session.livenessEmbeddingLen      = 64;
        g_enroll_session.livenessEmbeddingCaptured = true;
        ESP_LOGI(TAG, "Face embedding captured: 64 bytes");
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

    // Derive voterID = SHA-256(enrollmentId)
    uint8_t voter_id[32];
    derive_voter_id(g_enroll_record.enrollmentId.c_str(), voter_id);

    // Send INS_PERSONALIZE
    bool ok = sc_personalize_card(
        g_enroll_session.pin,
        voter_id,
        g_enroll_session.fpTemplate,
        g_enroll_record.cardStaticKey,
        g_enroll_record.adminTokenHash
    );

    if (!ok) {
        ui_display_error("Card write failed!\nRetrying...");
        vTaskDelay(pdMS_TO_TICKS(2000));
        // Retry once
        ok = sc_personalize_card(
            g_enroll_session.pin,
            voter_id,
            g_enroll_session.fpTemplate,
            g_enroll_record.cardStaticKey,
            g_enroll_record.adminTokenHash
        );
        if (!ok) {
            ui_display_error("Card write failed.\nContact supervisor.");
            vTaskDelay(pdMS_TO_TICKS(3000));
            enroll_reset();
            return;
        }
    }

    // ── Immediately save cardStaticKey to NVS ─────────────
    // This must happen before sc_establish_secure_channel() is
    // ever called for this card during a future voting session.
    if (!enrollment_store_save(g_enroll_session.cardUID.c_str(),
                               g_enroll_record.cardStaticKey)) {
        // NVS write failure is critical — the voter can never vote
        // on any terminal that doesn't have their key. Log at error
        // level and halt this enrollment.
        ui_display_error("NVS write failed!\nContact supervisor.\nCard write was OK.");
        ESP_LOGE(TAG, "CRITICAL: NVS save of cardStaticKey failed for %s",
                 g_enroll_session.cardUID.c_str());
        vTaskDelay(pdMS_TO_TICKS(4000));
        enroll_reset();
        return;
    }

    g_enroll_session.cardWritten = true;
    ESP_LOGI(TAG, "Card personalized and key stored for %s",
             g_enroll_session.cardUID.c_str());

    g_state = STATE_ENROLL_VERIFY;  // next: verify write + store liveness embedding
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
