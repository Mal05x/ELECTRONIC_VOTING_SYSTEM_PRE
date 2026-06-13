/**
 * officer_auth.cpp — Polling Officer PIN Gate
 * ============================================
 * Implements the 6-digit officer PIN that guards Enrollment Mode,
 * Settings Mode, and Voting Mode activation on the MFA voting terminal.
 *
 * NVS layout (namespace "officer_auth"):
 *   "pin_hash"   — blob, 32 bytes  — SHA-256 of the 6-digit PIN string
 *   "fail_count" — u8              — consecutive failure counter
 *   "lock_until" — i64             — esp_timer_get_time() µs lockout expiry
 *
 * UI contract:
 *   officer_auth.cpp calls three tft_ui functions declared in tft_ui.h:
 *     ui_display_officer_pin(mode, filled, digit)
 *     ui_officer_update_dot(idx, filled)
 *     ui_display_officer_locked(remaining_secs)
 *   These are implemented in tft_ui.cpp (see PATCH: tft_ui.cpp below).
 *
 * Button mapping (reuses existing tactile button GPIO constants):
 *   UP / DOWN   — cycle current digit 0-9
 *   RIGHT       — confirm digit and advance
 *   BACK        — backspace (≥1 digit entered) or cancel (0 digits)
 *   CENTER      — submit when 6 digits are filled
 */

#include "officer_auth.h"
#include "tft_ui.h"
#include "pin_defs.h"           /* BTN_UP, BTN_DOWN, BTN_RIGHT, BTN_CENTER, BTN_BACK */
#include "buzzer.h"

#include "nvs_flash.h"
#include "nvs.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "mbedtls/sha256.h"

#include <string.h>
#include <stdio.h>

static const char *TAG          = "OfficerAuth";
static const char *NVS_NS       = "officer_auth";
static const char *NVS_PIN_HASH = "pin_hash";
static const char *NVS_FAILS    = "fail_count";
static const char *NVS_LOCK     = "lock_until";

static const int   PIN_LEN      = 6;
static const int   MAX_FAILS    = 3;
static const int64_t LOCKOUT_US = 300LL * 1000000LL;  /* 300 seconds in µs */

/* ── Internal helpers ──────────────────────────────────────────────────── */

static void sha256_of(const char *input, uint8_t out[32]) {
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0);
    mbedtls_sha256_update(&ctx, (const uint8_t *)input, strlen(input));
    mbedtls_sha256_finish(&ctx, out);
    mbedtls_sha256_free(&ctx);
}

static bool nvs_read_hash(uint8_t out[32]) {
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READONLY, &h) != ESP_OK) return false;
    size_t len = 32;
    bool ok = (nvs_get_blob(h, NVS_PIN_HASH, out, &len) == ESP_OK && len == 32);
    nvs_close(h);
    return ok;
}

static bool nvs_write_hash(const uint8_t hash[32]) {
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) != ESP_OK) return false;
    bool ok = (nvs_set_blob(h, NVS_PIN_HASH, hash, 32) == ESP_OK);
    if (ok) ok = (nvs_commit(h) == ESP_OK);
    nvs_close(h);
    return ok;
}

static uint8_t nvs_read_fails(void) {
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READONLY, &h) != ESP_OK) return 0;
    uint8_t v = 0;
    nvs_get_u8(h, NVS_FAILS, &v);
    nvs_close(h);
    return v;
}

static void nvs_write_fails(uint8_t v) {
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_u8(h, NVS_FAILS, v);
    nvs_commit(h);
    nvs_close(h);
}

static int64_t nvs_read_lock_until(void) {
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READONLY, &h) != ESP_OK) return 0;
    int64_t v = 0;
    nvs_get_i64(h, NVS_LOCK, &v);
    nvs_close(h);
    return v;
}

static void nvs_write_lock_until(int64_t ts_us) {
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_i64(h, NVS_LOCK, ts_us);
    nvs_commit(h);
    nvs_close(h);
}

static bool is_locked(int *remaining_secs_out) {
    int64_t lock_until = nvs_read_lock_until();
    int64_t now        = esp_timer_get_time();
    if (now < lock_until) {
        if (remaining_secs_out)
            *remaining_secs_out = (int)((lock_until - now) / 1000000LL);
        return true;
    }
    return false;
}

static void trigger_lockout(void) {
    nvs_write_fails(MAX_FAILS);
    nvs_write_lock_until(esp_timer_get_time() + LOCKOUT_US);
    ESP_LOGW(TAG, "Officer PIN lockout triggered — 300s cooldown");
}

/* Read one button press, blocking with a small poll interval */
static int read_one_button(void) {
    while (true) {
        if (gpio_get_level((gpio_num_t)BTN_UP)     == 0) {
            while (gpio_get_level((gpio_num_t)BTN_UP)     == 0) vTaskDelay(pdMS_TO_TICKS(10));
            return BTN_UP;
        }
        if (gpio_get_level((gpio_num_t)BTN_DOWN)   == 0) {
            while (gpio_get_level((gpio_num_t)BTN_DOWN)   == 0) vTaskDelay(pdMS_TO_TICKS(10));
            return BTN_DOWN;
        }
        if (gpio_get_level((gpio_num_t)BTN_RIGHT)  == 0) {
            while (gpio_get_level((gpio_num_t)BTN_RIGHT)  == 0) vTaskDelay(pdMS_TO_TICKS(10));
            return BTN_RIGHT;
        }
        if (gpio_get_level((gpio_num_t)BTN_CENTER) == 0) {
            while (gpio_get_level((gpio_num_t)BTN_CENTER) == 0) vTaskDelay(pdMS_TO_TICKS(10));
            return BTN_CENTER;
        }
        if (gpio_get_level((gpio_num_t)BTN_BACK)   == 0) {
            while (gpio_get_level((gpio_num_t)BTN_BACK)   == 0) vTaskDelay(pdMS_TO_TICKS(10));
            return BTN_BACK;
        }
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

/**
 * collect_pin — blocking 6-digit input loop.
 * Writes collected digits into out_buf (must be >= PIN_LEN + 1 bytes).
 * Returns true if PIN collected, false if BACK pressed with 0 digits (cancel).
 */
static bool collect_pin(const char *mode, char out_buf[PIN_LEN + 1]) {
    int  current_digit  = 0;
    int  filled         = 0;
    char digits[PIN_LEN] = {};

    ui_display_officer_pin(mode, 0, 0);

    while (true) {
        int btn = read_one_button();
        buzzer_click();

        if (btn == BTN_UP) {
            current_digit = (current_digit + 1) % 10;
            ui_officer_update_selector(current_digit);

        } else if (btn == BTN_DOWN) {
            current_digit = (current_digit + 9) % 10;
            ui_officer_update_selector(current_digit);

        } else if (btn == BTN_RIGHT && filled < PIN_LEN) {
            /* Confirm current digit */
            digits[filled++] = (char)('0' + current_digit);
            current_digit = 0;
            ui_officer_update_dot(filled - 1, true);
            ui_officer_update_selector(0);

        } else if (btn == BTN_BACK) {
            if (filled > 0) {
                /* Backspace */
                filled--;
                digits[filled] = 0;
                current_digit  = 0;
                ui_officer_update_dot(filled, false);
                ui_officer_update_selector(0);
            } else {
                /* Cancel */
                return false;
            }

        } else if (btn == BTN_CENTER && filled == PIN_LEN) {
            /* Submit */
            memcpy(out_buf, digits, PIN_LEN);
            out_buf[PIN_LEN] = '\0';
            return true;
        }
    }
}

/* ── Public API ────────────────────────────────────────────────────────── */

bool officer_auth_is_set(void) {
    uint8_t dummy[32];
    return nvs_read_hash(dummy);
}

void officer_auth_reset_fails(void) {
    nvs_write_fails(0);
    nvs_write_lock_until(0);
}

bool officer_auth_prompt(const char *mode_name) {
	if (!officer_auth_is_set()) {
	    // PIN hash not yet fetched from server.
	    // This means either: (a) the admin has not provisioned the officer
	    // PIN via the backend dashboard, or (b) the terminal has not yet
	    // connected to the backend since the PIN was provisioned.
	    ui_display_error("Officer PIN not\nprovisioned.\nConnect to network\n"
	                     "or contact admin.");
	    buzzer_beep_error();
	    vTaskDelay(pdMS_TO_TICKS(3000));
	    return false;
	}

    /* Check lockout */
    int secs_remaining = 0;
    if (is_locked(&secs_remaining)) {
        ui_display_officer_locked(secs_remaining);

        /* Wait out the lock, refreshing the countdown every second */
        while (is_locked(&secs_remaining)) {
            vTaskDelay(pdMS_TO_TICKS(1000));
            ui_display_officer_locked(secs_remaining);
        }
        /* Lock expired — reset and fall through to PIN entry */
        officer_auth_reset_fails();
    }

    uint8_t stored_hash[32];
    if (!nvs_read_hash(stored_hash)) {
        ESP_LOGE(TAG, "Failed to read PIN hash from NVS");
        return false;
    }

    uint8_t fails = nvs_read_fails();

    for (int attempt = 0; attempt < MAX_FAILS; attempt++) {
        char pin_buf[PIN_LEN + 1] = {};
        bool entered = collect_pin(mode_name, pin_buf);

        if (!entered) {
            /* BACK pressed at 0 digits — user cancelled */
            ESP_LOGI(TAG, "Officer PIN prompt cancelled by user");
            return false;
        }

        uint8_t entered_hash[32];
        sha256_of(pin_buf, entered_hash);

        /* Constant-time comparison to prevent timing side-channel */
        bool match = true;
        for (int i = 0; i < 32; i++) {
            if (stored_hash[i] != entered_hash[i]) match = false;
        }

        if (match) {
            officer_auth_reset_fails();
            ESP_LOGI(TAG, "Officer PIN accepted for mode: %s", mode_name);
            buzzer_beep_ok();
            return true;
        }

        /* Wrong PIN */
        fails++;
        nvs_write_fails(fails);
        buzzer_beep_error();
        ESP_LOGW(TAG, "Officer PIN wrong — attempt %d/%d", fails, MAX_FAILS);

        if (fails >= MAX_FAILS) {
            trigger_lockout();
            ui_display_officer_locked(300);
            /* Re-enter lock-wait loop */
            int remaining = 0;
            while (is_locked(&remaining)) {
                vTaskDelay(pdMS_TO_TICKS(1000));
                ui_display_officer_locked(remaining);
            }
            officer_auth_reset_fails();
            /* After lockout expires, allow retry from scratch */
            attempt = -1;
            fails   = 0;
            if (!nvs_read_hash(stored_hash)) return false;
            continue;
        }

        /* Show wrong PIN feedback on existing screen before retrying */
        ui_display_officer_wrong_pin(MAX_FAILS - fails);
        vTaskDelay(pdMS_TO_TICKS(1500));
        ui_display_officer_pin(mode_name, 0, 0);   /* reset display */
    }

    return false;   /* should not reach here — loop handles all paths */
}

bool officer_auth_store_fetched_hash(const uint8_t hash[32]) {
	return nvs_write_hash(hash);
}
#if 0
bool officer_auth_setup(void) {
    char first[PIN_LEN + 1]   = {};
    char confirm[PIN_LEN + 1] = {};

    /* First entry */
    bool ok = collect_pin("SET PIN", first);
    if (!ok) return false;

    /* Confirmation entry */
    ok = collect_pin("CONFIRM", confirm);
    if (!ok) return false;

    if (memcmp(first, confirm, PIN_LEN) != 0) {
        ui_display_error("PINs do not match.\nSetup cancelled.");
        buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(2000));
        return false;
    }

    uint8_t hash[32];
    sha256_of(first, hash);

    if (!nvs_write_hash(hash)) {
        ui_display_error("NVS write failed.\nContact admin.");
        buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(2000));
        return false;
    }

    officer_auth_reset_fails();
    ESP_LOGI(TAG, "Officer PIN provisioned successfully");
    buzzer_beep_ok();
    return true;
}
#endif

bool officer_auth_change(void) {
    /* Verify current PIN first */
    bool verified = officer_auth_prompt("CHANGE PIN");
    if (!verified) return false;

    /* Now set new PIN */
    return officer_auth_setup();
}
