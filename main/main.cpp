/*
 * ============================================================
 * MAL MFA E-Voting Terminal — ESP-IDF Port  v5.3
 *
 * v5.3 changes over v5.2:
 * - Enrollment flow fully integrated into home menu.
 * Selecting ENROLL VOTER on homepage now scans the JCOP4
 * card, fetches voter data from backend, captures PIN /
 * fingerprint / face embedding, and writes everything to
 * the card — without requiring a reboot into enrollment mode.
 * - New states: STATE_ENROLL_SCAN, STATE_ENROLL_FETCH,
 * STATE_ENROLL_PIN_SET, STATE_ENROLL_PIN_CONFIRM,
 * STATE_ENROLL_FINGERPRINT, STATE_ENROLL_LIVENESS,
 * STATE_ENROLL_WRITE, STATE_ENROLL_VERIFY, STATE_ENROLL_COMPLETE
 * - Fixed camera_uart embedding size: 64 bytes = 128 hex chars
 * (was incorrectly expecting 64 hex chars = 32 bytes).
 * - LEDC buzzer conflict fixed: CHANNEL_3 / TIMER_1 only.
 * - reset_session() → STATE_HOME (not STATE_IDLE).
 * ============================================================
 */

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "nvs_flash.h"
#include "esp_log.h"
#include "esp_random.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "driver/uart.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_wifi.h"
#include "mbedtls/sha256.h"
#include <cstring>
#include <time.h>
#include <Arduino.h>

#include "config.h"
#include "pin_defs.h"
#include "session.h"
#include "applet_defs.h"

#include "ili9341.h"
#include "tft_ui.h"
#include "pn5180.h"
#include "fingerprint.h"
#include "camera_uart.h"
#include "smart_card.h"
#include "network.h"
#include "terminal_key.h"
#include "crypto_utils.h"
#include "enrollment.h"
#include "enrollment_store.h"
#include "buzzer.h"
#include "officer_auth.h"




static const char *TAG = "MAIN";

// ── Global state ──────────────────────────────────────────
VoterSession      g_session         = {};
EnrollmentSession g_enroll_session  = {};
EnrollmentRecord  g_enroll_record   = {};
Candidate         g_candidates[10]  = {};
int               g_candidateCount  = 0;
std::string       g_pinBuffer       = "";
NavigationState   g_nav             = {0, 0};
SystemState       g_state           = STATE_HOME;
ElectionConfig    g_electionCfg     = {"", "", "", 0, false, "PASSIVE"};
std::string       g_lastTransactionId = "";
TerminalMode      g_terminal_mode   = MODE_VOTING;

// ── Homepage / settings selection ─────────────────────────
static int g_home_sel = HOME_VOTE;
static int g_sett_sel = SETTINGS_LIVENESS;

static int s_current_digit = 0;

// ── Timing state ──────────────────────────────────────────
static TickType_t s_session_start_tick  = 0;
static TickType_t s_last_idle_poll_tick = 0;
static TickType_t s_last_activity_tick  = 0;
static TickType_t s_last_removal_check  = 0;
static TickType_t s_last_status_tick    = 0;
static bool       s_is_sleeping         = false;
static const uint32_t INACTIVITY_TIMEOUT_MS = 30000;

// Enrollment state machine lives in enrollment.cpp (enroll_task)

// ── Inter-task event group ────────────────────────────────
static EventGroupHandle_t s_net_eg;
EventGroupHandle_t g_net_eg;

// ── Task handles ──────────────────────────────────────────
static TaskHandle_t s_task_ui        = NULL;
static TaskHandle_t s_task_network   = NULL;
static TaskHandle_t s_task_heartbeat = NULL;

// ── ADC ───────────────────────────────────────────────────
adc_oneshot_unit_handle_t adc1_handle;

// ─────────────────────────────────────────────────────────
//  LEDC — RGB LED on TIMER_0 / CHANNEL_0-2
//          Buzzer on TIMER_1 / CHANNEL_3  (see buzzer.cpp)
// ─────────────────────────────────────────────────────────
static void led_init(void) {
    ledc_timer_config_t timer = {};
    timer.speed_mode = LEDC_LOW_SPEED_MODE; timer.timer_num = LEDC_TIMER_0;
    timer.duty_resolution = LEDC_TIMER_8_BIT; timer.freq_hz = 5000;
    timer.clk_cfg = LEDC_AUTO_CLK;
    ledc_timer_config(&timer);
    ledc_channel_config_t ch = {};
    ch.speed_mode = LEDC_LOW_SPEED_MODE; ch.intr_type = LEDC_INTR_DISABLE;
    ch.timer_sel = LEDC_TIMER_0; ch.duty = 0; ch.hpoint = 0;
    ch.channel = LEDC_CHANNEL_0; ch.gpio_num = LED_R; ledc_channel_config(&ch);
    ch.channel = LEDC_CHANNEL_1; ch.gpio_num = LED_G; ledc_channel_config(&ch);
    ch.channel = LEDC_CHANNEL_2; ch.gpio_num = LED_B; ledc_channel_config(&ch);
}

static void set_led(uint8_t r, uint8_t g, uint8_t b) {
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, r);
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_1, g);
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_2, b);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_1);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_2);
}

static void ledc_buzzer_hw_init(void) {
    ledc_timer_config_t t = {};
    t.speed_mode = LEDC_LOW_SPEED_MODE; t.timer_num = LEDC_TIMER_1;
    t.duty_resolution = LEDC_TIMER_10_BIT; t.freq_hz = 2000;
    t.clk_cfg = LEDC_AUTO_CLK;
    ledc_timer_config(&t);
    ledc_channel_config_t ch = {};
    ch.gpio_num = BUZZER; ch.speed_mode = LEDC_LOW_SPEED_MODE;
    ch.channel = LEDC_CHANNEL_3; ch.intr_type = LEDC_INTR_DISABLE;
    ch.timer_sel = LEDC_TIMER_1; ch.duty = 0; ch.hpoint = 0;
    ledc_channel_config(&ch);
}

// ── Button helpers ────────────────────────────────────────
static bool read_button(uint8_t pin) {
    return gpio_get_level((gpio_num_t)pin) == 0;
}
static void wait_button_release(uint8_t pin) {
    while (gpio_get_level((gpio_num_t)pin) == 0) vTaskDelay(pdMS_TO_TICKS(10));
    vTaskDelay(pdMS_TO_TICKS(50));
}
static int get_button_press(void) {
    if (read_button(BTN_UP))     { wait_button_release(BTN_UP);     buzzer_click(); return BTN_UP;     }
    if (read_button(BTN_DOWN))   { wait_button_release(BTN_DOWN);   buzzer_click(); return BTN_DOWN;   }
    if (read_button(BTN_LEFT))   { wait_button_release(BTN_LEFT);   buzzer_click(); return BTN_LEFT;   }
    if (read_button(BTN_RIGHT))  { wait_button_release(BTN_RIGHT);  buzzer_click(); return BTN_RIGHT;  }
    if (read_button(BTN_CENTER)) { wait_button_release(BTN_CENTER); buzzer_click(); return BTN_CENTER; }
    if (read_button(BTN_BACK))   { wait_button_release(BTN_BACK);   buzzer_click(); return BTN_BACK;   }
    return -1;
}

// ── Battery ───────────────────────────────────────────────
static void battery_adc_init(void) {
    adc_oneshot_unit_init_cfg_t init_config = {}; init_config.unit_id = ADC_UNIT_1;
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init_config, &adc1_handle));
    adc_oneshot_chan_cfg_t config = {};
    config.atten = ADC_ATTEN_DB_12; config.bitwidth = ADC_BITWIDTH_DEFAULT;
    ESP_ERROR_CHECK(adc_oneshot_config_channel(adc1_handle, ADC_CHANNEL_0, &config));
}

int read_battery_percent(void) {
    int raw = 0;
    adc_oneshot_read(adc1_handle, ADC_CHANNEL_0, &raw);
    int pct = (int)(((float)(raw - 1860) / (2600.0f - 1860.0f)) * 100.0f);
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    return pct;
}

// ── Helpers ───────────────────────────────────────────────
static std::string generate_session_id(void) {
    uint8_t buf[16]; esp_fill_random(buf, 16);
    char hex[33];
    for (int i = 0; i < 16; i++) snprintf(&hex[i*2], 3, "%02x", buf[i]);
    hex[32] = '\0'; return std::string(hex);
}

#if 0
// SHA-256 of a string → 32 raw bytes (for voter_id derivation)
static void sha256_raw(const char *input, uint8_t out32[32]) {
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    mbedtls_sha256_starts(&ctx, 0);
    mbedtls_sha256_update(&ctx, (const uint8_t *)input, strlen(input));
    mbedtls_sha256_finish(&ctx, out32);
    mbedtls_sha256_free(&ctx);
}
#endif

static bool trigger_network_and_wait(uint32_t trigger_bit) {
    xEventGroupClearBits(s_net_eg, EVT_NET_SUCCESS | EVT_NET_ERROR);
    xEventGroupSetBits(s_net_eg, trigger_bit);
    EventBits_t result = xEventGroupWaitBits(s_net_eg,
        EVT_NET_SUCCESS | EVT_NET_ERROR, pdTRUE, pdFALSE,
        pdMS_TO_TICKS(NETWORK_TASK_TIMEOUT_MS));
    return (result & EVT_NET_SUCCESS) != 0;
}

// ── Session reset → HOME ──────────────────────────────────
static void reset_session(void) {
    g_session   = VoterSession{};
    g_pinBuffer = "";
    g_nav.currentSelection = 0;
    g_nav.maxSelections    = g_candidateCount;
    s_session_start_tick   = 0;
    wrapper_reset_rf();
    g_state = STATE_HOME;
    ui_display_home(g_home_sel);
    set_led(0, 255, 0);
    s_last_activity_tick = xTaskGetTickCount();
}

/* reset_enrollment() lives in enrollment.cpp as enroll_reset() */

// ═══════════════════════════════════════════════════════════
//  ELECTION CONFIG BACKGROUND TASK (Core 0)
//  Fetches election config + candidates after the homepage is
//  already visible.  Retries every 30 s until loaded.
//  On success, refreshes the election band on the homepage.
// ═══════════════════════════════════════════════════════════
static void task_election_config(void *pvParameters) {
    ESP_LOGI(TAG, "[ELECTION] Background fetch started");
    int attempt = 0;
    while (!g_electionCfg.loaded) {
        attempt++;
        net_wifi_reconnect_if_needed();
        ESP_LOGI(TAG, "[ELECTION] Fetch attempt %d", attempt);

        if (!officer_auth_is_set()) {
            	    ESP_LOGI(TAG, "Officer PIN hash absent — fetching from server...");
            	    if (net_fetch_officer_pin_hash()) {
            	        ESP_LOGI(TAG, "Officer PIN hash provisioned from server");
            	    } else {
            	        ESP_LOGW(TAG, "Officer PIN hash unavailable — "
            	                      "admin must provision via backend dashboard");
            	    }
            	}

        if (net_fetch_election_config()) {
            net_fetch_candidates();
            g_nav.maxSelections = g_candidateCount;
            ESP_LOGI(TAG, "[ELECTION] Loaded: %s  livenessMode=%s",
                     g_electionCfg.electionTitle.c_str(),
                     g_electionCfg.livenessMode.c_str());

            // Repaint election band on homepage if it is currently visible.
            // No mutex needed — tft_ui functions are called from Core 1
            // (task_ui) normally, but this is a one-shot static write to a
            // fixed screen region that task_ui never overlaps at this moment
            // (the band is only touched by ui_display_home and this call).
            if (g_state == STATE_HOME) {
                ui_home_update_election_band();
            }
            break;
        }

        // Retry every 30 s, back off after 10 failures (5 min max interval)
        uint32_t delay_ms = (attempt <= 10) ? 30000 : 300000;
        ESP_LOGW(TAG, "[ELECTION] Fetch failed — retry in %lu s", delay_ms / 1000);
        vTaskDelay(pdMS_TO_TICKS(delay_ms));
    }
    ESP_LOGI(TAG, "[ELECTION] Background task done");
    vTaskDelete(NULL);
}

// ═══════════════════════════════════════════════════════════
//  NETWORK TASK (Core 0)
// ═══════════════════════════════════════════════════════════
static void task_network(void *pvParameters) {
    for (;;) {
        EventBits_t bits = xEventGroupWaitBits(s_net_eg,
            EVT_NET_AUTH_TRIGGER | EVT_NET_VOTE_TRIGGER |
            EVT_NET_ENROLL_FETCH | EVT_NET_ENROLL_CONFIRM,
            pdTRUE, pdFALSE, portMAX_DELAY);
        if (bits & EVT_NET_AUTH_TRIGGER)   xEventGroupSetBits(s_net_eg, net_request_tap_session()     ? EVT_NET_SUCCESS : EVT_NET_ERROR);
        if (bits & EVT_NET_VOTE_TRIGGER)   xEventGroupSetBits(s_net_eg, net_submit_vote()              ? EVT_NET_SUCCESS : EVT_NET_ERROR);
        if (bits & EVT_NET_ENROLL_FETCH)   xEventGroupSetBits(s_net_eg, net_fetch_pending_enrollment() ? EVT_NET_SUCCESS : EVT_NET_ERROR);
        if (bits & EVT_NET_ENROLL_CONFIRM) xEventGroupSetBits(s_net_eg, net_complete_enrollment()      ? EVT_NET_SUCCESS : EVT_NET_ERROR);
    }
}

// ── Heartbeat task (Core 0) ───────────────────────────────
static void task_heartbeat(void *pvParameters) {
    for (;;) {
        net_wifi_reconnect_if_needed();
        net_send_heartbeat();
        vTaskDelay(pdMS_TO_TICKS(HEARTBEAT_INTERVAL_MS));
    }
}

// ═══════════════════════════════════════════════════════════
//  STATE HANDLERS
// ═══════════════════════════════════════════════════════════

// ── STATE_HOME ────────────────────────────────────────────
static void handle_home(void) {
    TickType_t now = xTaskGetTickCount();
    if ((now - s_last_status_tick) >= pdMS_TO_TICKS(1000)) {
        s_last_status_tick = now;
        wifi_ap_record_t ap;
        ui_home_update_status(esp_wifi_sta_get_ap_info(&ap) == ESP_OK,
                              read_battery_percent());
    }
    if ((now - s_last_activity_tick) > pdMS_TO_TICKS(INACTIVITY_TIMEOUT_MS)) {
        g_state = STATE_SLEEP; return;
    }
    int btn = get_button_press();
    if (btn == -1) return;
    s_last_activity_tick = xTaskGetTickCount();

    if (btn == BTN_UP) {
        int ns = (g_home_sel - 1 + HOME_ITEM_COUNT) % HOME_ITEM_COUNT;
        ui_home_move_selection(g_home_sel, ns); g_home_sel = ns;
    } else if (btn == BTN_DOWN) {
        int ns = (g_home_sel + 1) % HOME_ITEM_COUNT;
        ui_home_move_selection(g_home_sel, ns); g_home_sel = ns;
    } else if (btn == BTN_CENTER) {
           buzzer_beep_ok();
           switch (g_home_sel) {

               case HOME_VOTE:
                   // ── Officer PIN gate ──────────────────────────────────────
                   // The MFA chain (card + PIN + fingerprint + liveness) already
                   // secures each individual vote. The officer gate here prevents
                   // the terminal being put into Voting Mode by unauthorised
                   // personnel outside the official election window.
                   if (!officer_auth_prompt("VOTING")) {
                       ui_display_home(g_home_sel);
                       return;
                   }
                   if (!g_electionCfg.loaded) {
                       ui_display_status("Loading election\ndata from server...\nPlease wait.");
                       TickType_t deadline = xTaskGetTickCount() + pdMS_TO_TICKS(15000);
                       while (!g_electionCfg.loaded && xTaskGetTickCount() < deadline)
                           vTaskDelay(pdMS_TO_TICKS(500));
                       if (!g_electionCfg.loaded) {
                           ui_display_error("Election data\nnot available.\nCheck network.");
                           buzzer_beep_error();
                           vTaskDelay(pdMS_TO_TICKS(2500));
                           ui_display_home(g_home_sel);
                           return;
                       }
                   }
                   g_state = STATE_IDLE;
                   ui_display_idle();
                   set_led(0, 255, 0);
                   break;

               case HOME_ENROLL:
                   // ── Officer PIN gate ──────────────────────────────────────
                   // Enrollment creates voter credentials on JCOP4 cards.
                   // Strictly privileged — requires officer authentication
                   // before the enroll_task is spawned.
                   if (!officer_auth_prompt("ENROLLMENT")) {
                       ui_display_home(g_home_sel);
                       return;
                   }
                   set_led(255, 165, 0);
                   buzzer_beep_ok();
                   enroll_start_task();
                   vTaskDelay(pdMS_TO_TICKS(300));
                   while (g_state != STATE_HOME) {
                       vTaskDelay(pdMS_TO_TICKS(200));
                   }
                   set_led(0, 255, 0);
                   ui_display_home(g_home_sel);
                   break;

               case HOME_SETTINGS:
                   // ── Officer PIN gate ──────────────────────────────────────
                   // Settings include liveness mode switching — a security-
                   // relevant operation that must not be freely accessible.
                   if (!officer_auth_prompt("SETTINGS")) {
                       ui_display_home(g_home_sel);
                       return;
                   }
                   g_sett_sel = SETTINGS_LIVENESS;
                   g_state    = STATE_SETTINGS;
                   ui_display_settings(g_sett_sel);
                   break;

               case HOME_ABOUT:
                   // ABOUT is informational only — no gate needed.
                   g_state = STATE_ABOUT;
                   ui_display_about();
                   break;
           }
       } else if (btn == BTN_BACK) {
        g_state = STATE_SLEEP;
    }
}

// ── STATE_SETTINGS ────────────────────────────────────────
static void handle_settings(void) {
    int btn = get_button_press();
    if (btn == -1) return;
    s_last_activity_tick = xTaskGetTickCount();
    if (btn == BTN_UP)   { int ns=(g_sett_sel-1+SETTINGS_ITEM_COUNT)%SETTINGS_ITEM_COUNT; ui_settings_move(g_sett_sel,ns); g_sett_sel=ns; }
    if (btn == BTN_DOWN) { int ns=(g_sett_sel+1)%SETTINGS_ITEM_COUNT; ui_settings_move(g_sett_sel,ns); g_sett_sel=ns; }
    if (btn == BTN_CENTER && g_sett_sel == SETTINGS_LIVENESS) {
        g_electionCfg.livenessMode = (g_electionCfg.livenessMode == "ACTIVE") ? "PASSIVE" : "ACTIVE";
        ui_settings_update_liveness(g_electionCfg.livenessMode.c_str());
        buzzer_beep_ok();
    }
    if (btn == BTN_BACK) { g_state = STATE_HOME; ui_display_home(g_home_sel); }
    if (btn == BTN_CENTER && g_sett_sel == SETTINGS_ADMIN_RESET) {
            g_state = STATE_ADMIN_RESET_SCAN;
            ui_display_status("Admin Reset Mode\nTap Locked Card...");
            buzzer_beep_ok();
        }
}

// Add the Admin Reset Scanner State
// 1. Pass 'Button btn' as an argument
static void handle_admin_reset_scan(Button btn) {
    // 2. Fix the button check syntax
    if (btn == BTN_BACK) {
        reset_session();
        return;
    }

    TickType_t now = xTaskGetTickCount();
    if ((now - s_last_idle_poll_tick) < pdMS_TO_TICKS(1000)) return;
    s_last_idle_poll_tick = now;

    uint8_t uid[10];
    uint8_t uid_len = 0;

    if (!wrapper_pn5180_read_card(uid, &uid_len)) {
        if (btn_pressed(BTN_BACK)) reset_session();
        return;
    }

    // Format UID securely
    g_session.cardUID = "";
    for (int i = 0; i < uid_len; i++) {
        char hex[4]; if (i > 0) g_session.cardUID += ":";
        snprintf(hex, sizeof(hex), "%02x", uid[i]); g_session.cardUID += hex;
    }

    if (sc_select_applet() &&
        enrollment_store_load(g_session.cardUID.c_str(), g_session.applet_session.card_static_key)) {

        if (!sc_establish_secure_channel()) {
            ui_display_error("Secure Channel\nFailed.");
            vTaskDelay(pdMS_TO_TICKS(2000)); reset_session(); return;
        }

        g_state = STATE_ADMIN_RESET_PIN_ENTRY;
        g_pinBuffer = "";
        s_current_digit = 0;
        ui_display_status("Enter NEW PIN\nfor Voter");
        buzzer_beep_ok();
        vTaskDelay(pdMS_TO_TICKS(1000));
        ui_display_pin_entry();
    } else {
        ui_display_error("Card not recognized!");
        vTaskDelay(pdMS_TO_TICKS(2000)); reset_session();
    }
}

// Add the Admin Reset PIN Entry State
// 1. Pass 'Button btn' as an argument
static void handle_admin_reset_pin_entry(Button btn) {
    if (btn == BTN_BACK) {
        g_state = STATE_ADMIN_RESET_SCAN;
        ui_display_prompt("Tap Card to Reset");
        return;
    }

    if (g_pinBuffer.length() < 4) {
        if (btn == BTN_UP)    { s_current_digit = (s_current_digit + 1) % 10; ui_display_pin_digit_selector(s_current_digit); }
        if (btn == BTN_DOWN)  { s_current_digit = (s_current_digit + 9) % 10; ui_display_pin_digit_selector(s_current_digit); }
        if (btn == BTN_RIGHT) {
            g_pinBuffer += (char)s_current_digit; // RAW BYTE FIX APPLIED
            s_current_digit = 0;
            ui_update_pin_display();
        }
    }

    if (btn == BTN_CENTER && g_pinBuffer.length() == 4) {
        ui_display_status("Unlocking Card...");

        // 💥 TODO: Retrieve the 32-byte Admin Token you generated during Personalization
        uint8_t admin_token[32] = { /* Fetch from Spring Boot or offline NVS */ };

        uint8_t new_pin[4] = { (uint8_t)g_pinBuffer[0], (uint8_t)g_pinBuffer[1], (uint8_t)g_pinBuffer[2], (uint8_t)g_pinBuffer[3] };

        if (sc_admin_reset_pin(admin_token, new_pin)) {
            ui_display_status("Card Unlocked!\nPIN Reset Success.");
            set_led(0, 255, 0);
            buzzer_beep_ok();
            vTaskDelay(pdMS_TO_TICKS(3000));
        } else {
            ui_display_error("Admin Reset\nRejected.");
            buzzer_beep_error();
            vTaskDelay(pdMS_TO_TICKS(3000));
        }
        reset_session();
    }
}

// ── STATE_ABOUT ───────────────────────────────────────────
static void handle_about(void) {
    int btn = get_button_press();
    if (btn == -1) return;
    s_last_activity_tick = xTaskGetTickCount();
    if (btn == BTN_BACK || btn == BTN_CENTER) { g_state = STATE_HOME; ui_display_home(g_home_sel); }
}

// Enrollment state machine: see enrollment.cpp (enroll_task)


// ── STATE_IDLE ────────────────────────────────────────────

static void handle_check_for_card(void) {
    TickType_t now = xTaskGetTickCount();
    if ((now - s_last_idle_poll_tick) < pdMS_TO_TICKS(IDLE_POLL_INTERVAL_MS)) return;
    s_last_idle_poll_tick = now;

    // 1. Turn on the RF field right before polling
   // wrapper_reset_rf();

    uint8_t uid[10];
    uint8_t uid_len = 0;

    // 2. Use the hybrid wrapper to read the card
    if (!wrapper_pn5180_read_card(uid, &uid_len)) return;

    // 3. Transition to ISO-DEP Protocol (Layer 4)

    s_session_start_tick = s_last_activity_tick = xTaskGetTickCount();

    // 4. Format the UID securely into the global session
    g_session.cardUID = "";
    for (int i = 0; i < uid_len; i++) {
        char hex[4]; if (i > 0) g_session.cardUID += ":";
        snprintf(hex, sizeof(hex), "%02x", uid[i]); g_session.cardUID += hex;
    }

    set_led(255, 255, 0);
    buzzer_beep_card();
    ui_display_status("Card Detected\nSelecting applet...");

    // 5. Select the applet (Using the newly merged smart_card.cpp)
    if (sc_select_applet()) {
        if (!enrollment_store_load(g_session.cardUID.c_str(), g_session.applet_session.card_static_key)) {
            ui_display_error("Card not enrolled\nat this terminal!");
            buzzer_beep_error(); vTaskDelay(pdMS_TO_TICKS(3000));
            reset_session(); return;
        }
        g_session.cardAuthenticated = true;
        g_state = STATE_SECURE_CHANNEL;
    } else {
        ui_display_error("Invalid card!");
        buzzer_beep_error(); vTaskDelay(pdMS_TO_TICKS(2000));
        reset_session();
    }
}

// ── STATE_SECURE_CHANNEL ──────────────────────────────────
static void handle_secure_channel(void) {
	uint8_t pub_key[65];
	uint16_t pub_len = 0;

	if (!sc_establish_secure_channel() || !sc_get_public_key(pub_key, &pub_len)) {
	        ui_display_error("Secure channel failed!");
	        buzzer_beep_error();
	        vTaskDelay(pdMS_TO_TICKS(2000));
	        reset_session();
	        return;
	    }

    g_state = STATE_PIN_ENTRY;
    ui_display_pin_entry();
}

// ── STATE_PIN_ENTRY ───────────────────────────────────────
static int s_current_digit = 0;
static void handle_pin_entry(void) {
    int btn = get_button_press();
    if (btn == -1) return;
    s_last_activity_tick = xTaskGetTickCount();
    if (btn == BTN_BACK) {
        if (!g_pinBuffer.empty()) { g_pinBuffer.pop_back(); ui_update_pin_display(); }
        else reset_session();
        return;
    }
    if (btn == BTN_CENTER && g_pinBuffer.length() == 4) {
        if (sc_verify_pin(g_pinBuffer)) {
            g_session.pinVerified = true; g_pinBuffer = ""; s_current_digit = 0;
            if (sc_check_already_voted()) {
                ui_display_error("Already voted!"); buzzer_beep_error();
                vTaskDelay(pdMS_TO_TICKS(3000)); reset_session(); return;
            }
            g_state = STATE_FINGERPRINT_SCAN;
            buzzer_beep_ok(); set_led(0, 255, 0);
            ui_display_status("PIN Verified!\nPlace finger on sensor");
            vTaskDelay(pdMS_TO_TICKS(1000));
        } else {
            g_pinBuffer = ""; s_current_digit = 0;
            ui_update_pin_display(); buzzer_beep_error();
        }
        return;
    }
    if (g_pinBuffer.length() < 4) {
        if (btn == BTN_UP)    { s_current_digit = (s_current_digit + 1) % 10; ui_display_pin_digit_selector(s_current_digit); }
        if (btn == BTN_DOWN)  { s_current_digit = (s_current_digit + 9) % 10; ui_display_pin_digit_selector(s_current_digit); }
        // 💥 THE FIX: Remove the ('0' +) to send the raw integer byte, perfectly matching enrollment!
                if (btn == BTN_RIGHT) {
                    g_pinBuffer += (char)s_current_digit;
                    s_current_digit = 0;
                    ui_update_pin_display();
                }
    }
}

// ── STATE_FINGERPRINT_SCAN ────────────────────────────────
static void handle_fingerprint_scan(void) {
    ui_display_fingerprint(false);
    set_led(0, 0, 255);

    const int MAX_ATTEMPTS = 3;

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        if (!fp_capture_image() || !fp_generate_template()) {
            vTaskDelay(pdMS_TO_TICKS(500));
            continue;
        }

        uint8_t fp_tmpl[512];
        uint16_t fp_tmpl_len = fp_get_template(fp_tmpl);
        if (fp_tmpl_len == 0) continue;

        if (sc_verify_fingerprint(fp_tmpl, fp_tmpl_len)) {
            g_session.fingerprintVerified = true;
            g_state = STATE_LIVENESS_CHECK;
            ui_display_fingerprint(true);
            set_led(0, 255, 0);
            buzzer_beep_ok();
            vTaskDelay(pdMS_TO_TICKS(1000));
            return;
        } else if (attempt < MAX_ATTEMPTS) {
            ui_display_error("Mismatch.\nTry again...");
            buzzer_beep_error();
            vTaskDelay(pdMS_TO_TICKS(1500));
            ui_display_fingerprint(false);
        }
    }

    // Fallthrough: Failed all 3 attempts
    ui_display_error("Fingerprint\nverification failed!");
    buzzer_beep_error();
    vTaskDelay(pdMS_TO_TICKS(2000));
    reset_session();
}

// ── STATE_LIVENESS_CHECK ──────────────────────────────────
static void handle_liveness_check(void) {
    set_led(255, 255, 0);
    g_session.sessionId = generate_session_id();

    // BUG-FIX: buffer was 64 bytes; sc_get_liveness_embedding writes 256 bytes
    // (the card stores 256 bytes: 64 real embedding + 192 zero-padding).
    // Declaring this as 64 caused silent stack corruption on every liveness check.
    uint8_t embedding[256] = {}; uint16_t embedding_len = 0;
    bool has_embedding = sc_get_liveness_embedding(embedding, &embedding_len);
    if (has_embedding && embedding_len > 0) {
        // Send reference to camera — camera stores it for identity comparison
        // during the BURST/ACTIVE check. Camera returns STREAM_FAIL if the
        // live face doesn't match the stored reference embedding.
        cam_send_reference_embedding(embedding, embedding_len);
    } else {
        ESP_LOGW(TAG, "No face embedding on card — liveness-only mode for this voter");
    }

    /*if (!cam_perform_liveness(g_session.sessionId.c_str())) {
        ui_display_error("Liveness check\nfailed!"); buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(2000)); reset_session(); return;
    }*/
    g_session.livenessVerified = true;
    ui_display_status("Authenticating...");
    if (!trigger_network_and_wait(EVT_NET_AUTH_TRIGGER)) {
        ui_display_error("Server auth failed."); buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(2000)); reset_session(); return;
    }
    g_state = STATE_AUTHENTICATED;
    ui_display_status("Access Granted!"); set_led(0, 255, 0); buzzer_beep_ok();
    vTaskDelay(pdMS_TO_TICKS(1000));
}

// ── STATE_VOTING ──────────────────────────────────────────
static void handle_voting(void) {
    int btn = get_button_press();
    if (btn == -1) return;
    s_last_activity_tick = xTaskGetTickCount();
    if (btn == BTN_UP)     { g_nav.currentSelection = (g_nav.currentSelection - 1 + g_candidateCount) % g_candidateCount; ui_display_voting_interface(); }
    if (btn == BTN_DOWN)   { g_nav.currentSelection = (g_nav.currentSelection + 1) % g_candidateCount; ui_display_voting_interface(); }
    if (btn == BTN_CENTER) { g_state = STATE_VOTE_CONFIRMATION; ui_display_vote_confirmation(); }
    if (btn == BTN_BACK)   reset_session();
}

// ── STATE_VOTE_CONFIRMATION ───────────────────────────────
static void handle_vote_confirmation(void) {
    ui_display_status("Confirm: place\nfinger again");

    if (!fp_capture_image() || !fp_generate_template()) {
        vTaskDelay(pdMS_TO_TICKS(100));
        return;
    }

    uint8_t fp_tmpl[512];
    uint16_t fp_tmpl_len = fp_get_template(fp_tmpl);
    if (fp_tmpl_len == 0) return;

    if (!sc_verify_fingerprint(fp_tmpl, fp_tmpl_len)) {
        ui_display_error("Fingerprint failed.");
        buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(2000));
        g_state = STATE_VOTING;
        ui_display_voting_interface();
        return;
    }

    // 💥 1. PRE-BURN TAP INITIALIZATION
    ui_display_status("Securing Session...");

    // We repurpose EVT_NET_AUTH_TRIGGER to call task_network's net_request_tap_session
    if (!trigger_network_and_wait(EVT_NET_AUTH_TRIGGER)) {
        ui_display_error("Network Timeout.\nCard NOT Burned.\nPlease Try Again.");
        buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(3000));
        reset_session();
        return;
    }

    // 💥 2. THE HARDWARE BURN
    ui_display_status("Burning card...");
    if (!sc_set_voted_capture_burn_proof()) {
        ui_display_error("Card write failed.");
        buzzer_beep_error();
        g_state = STATE_ERROR;
        return;
    }

    // 💥 3. SUBMIT ENCRYPTED VOTE
    if (!trigger_network_and_wait(EVT_NET_VOTE_TRIGGER)) {
        // If HTTP 429 triggers here, the offline NVS Cache secures the payload.
        ui_display_error("Network error.\nVote saved locally.");
        buzzer_beep_error();
        g_state = STATE_ERROR;
        return;
    }

    g_state = STATE_VOTE_SUBMITTED;
    ui_display_vote_success(g_lastTransactionId.c_str());
    set_led(0, 255, 0);
    buzzer_beep_vote_success();
}

// ── STATE_SLEEP ───────────────────────────────────────────
static void handle_sleep_mode(void) {
    if (!s_is_sleeping) {
        gpio_set_level((gpio_num_t)TFT_LED, 0); set_led(0, 0, 0); s_is_sleeping = true;
    }
    bool wake = (read_button(BTN_UP)||read_button(BTN_DOWN)||read_button(BTN_LEFT)||
                 read_button(BTN_RIGHT)||read_button(BTN_CENTER)||read_button(BTN_BACK));
    TickType_t now = xTaskGetTickCount();
    if (!wake && (now - s_last_idle_poll_tick) >= pdMS_TO_TICKS(IDLE_POLL_INTERVAL_MS)) {
        s_last_idle_poll_tick = now;
        uint8_t uid[10]; uint8_t uid_len = 0;
        if (wrapper_pn5180_read_card(uid, &uid_len)) wake = true;
    }
    if (wake) {
        gpio_set_level((gpio_num_t)TFT_LED, 1); buzzer_click(); s_is_sleeping = false;
        s_last_activity_tick = xTaskGetTickCount(); reset_session();
    }
}

// ── Watchdogs ─────────────────────────────────────────────
static bool check_session_timeout(void) {
    if (s_session_start_tick == 0) return false;
    if ((xTaskGetTickCount()-s_session_start_tick) >= pdMS_TO_TICKS(SESSION_TIMEOUT_MS)) {
        ui_display_error("Session timed out."); buzzer_beep_alert();
        vTaskDelay(pdMS_TO_TICKS(2500)); reset_session(); return true;
    }
    return false;
}

static bool check_card_removed(void) {
    static const SystemState NO_CHECK[] = {
        STATE_IDLE, STATE_HOME, STATE_SETTINGS, STATE_ABOUT,
        STATE_VOTE_SUBMITTED, STATE_SLEEP,
        STATE_ENROLL_SCAN, STATE_ENROLL_FETCH,
        STATE_ENROLL_PIN_SET, STATE_ENROLL_PIN_CONFIRM,
        STATE_ENROLL_FINGERPRINT, STATE_ENROLL_LIVENESS,
        STATE_ENROLL_COMPLETE
    };
    for (auto s : NO_CHECK) if (g_state == s) return false;
    TickType_t now = xTaskGetTickCount();
    if ((now - s_last_removal_check) < pdMS_TO_TICKS(2000)) return false;
    s_last_removal_check = now;
    if (false) {
        ui_display_error("Card removed!"); buzzer_beep_error();
        vTaskDelay(pdMS_TO_TICKS(2000)); reset_session(); return true;
    }
    return false;
}

static void check_tamper(void) {
    if (gpio_get_level((gpio_num_t)TAMPER_PIN) == 1) {
        buzzer_beep_alert(); ui_display_tamper_alert(); set_led(255, 0, 0);
        vTaskDelay(pdMS_TO_TICKS(5000)); esp_restart();
    }
}

// ── UI task (Core 1) ──────────────────────────────────────
static void task_ui(void *pvParameters) {
    for (;;) {
        check_tamper();
        // Enrollment states are handled by enroll_task (enrollment.cpp),
        // not by task_ui — exclude them from the session watchdog.
        bool active_voting = (g_state != STATE_IDLE  && g_state != STATE_HOME &&
                              g_state != STATE_SETTINGS && g_state != STATE_ABOUT &&
                              g_state != STATE_VOTE_SUBMITTED && g_state != STATE_SLEEP &&
                              g_state <  STATE_ENROLL_IDLE);
        if (active_voting) {
            if (check_session_timeout()) { vTaskDelay(pdMS_TO_TICKS(50)); continue; }
            if (check_card_removed())    { vTaskDelay(pdMS_TO_TICKS(50)); continue; }
        }

        switch (g_state) {
            case STATE_HOME:              handle_home();                      break;
            case STATE_SETTINGS:          handle_settings();                  break;
            case STATE_ABOUT:             handle_about();                     break;
            case STATE_IDLE:
                handle_check_for_card();
                if ((xTaskGetTickCount()-s_last_activity_tick) > pdMS_TO_TICKS(INACTIVITY_TIMEOUT_MS))
                    g_state = STATE_SLEEP;
                break;
            case STATE_SLEEP:             handle_sleep_mode();                break;
            case STATE_SECURE_CHANNEL:    handle_secure_channel();            break;
            case STATE_PIN_ENTRY:         handle_pin_entry();                 break;
            case STATE_FINGERPRINT_SCAN:  handle_fingerprint_scan();          break;
            case STATE_LIVENESS_CHECK:    handle_liveness_check();            break;
            case STATE_AUTHENTICATED:
                ui_display_voting_interface(); g_state = STATE_VOTING;        break;
            case STATE_VOTING:            handle_voting();                    break;
            case STATE_VOTE_CONFIRMATION: handle_vote_confirmation();         break;
            case STATE_VOTE_SUBMITTED:
                vTaskDelay(pdMS_TO_TICKS(5000)); reset_session();             break;
            // Enrollment states driven by enroll_task (enrollment.cpp)
            case STATE_ERROR:
                vTaskDelay(pdMS_TO_TICKS(3000)); reset_session();             break;
            case STATE_ADMIN_RESET_SCAN:
                        handle_admin_reset_scan(btn);
                        break;

                    case STATE_ADMIN_RESET_PIN_ENTRY:
                        handle_admin_reset_pin_entry(btn);
                        break;
            default: break;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

// ── Button + tamper GPIO init ──────────────────────────────
static void buttons_init(void) {
    const uint8_t btns[] = {BTN_UP,BTN_DOWN,BTN_LEFT,BTN_RIGHT,BTN_CENTER,BTN_BACK,TAMPER_PIN};
    gpio_config_t cfg = {};
    cfg.mode=GPIO_MODE_INPUT; cfg.pull_up_en=GPIO_PULLUP_ENABLE; cfg.intr_type=GPIO_INTR_DISABLE;
    for (auto p : btns) cfg.pin_bit_mask |= (1ULL << p);
    gpio_config(&cfg);
}

// ── app_main ──────────────────────────────────────────────
extern "C" void app_main(void) {
    // 1. Arduino runtime (must be absolutely first)
    initArduino();

    ESP_LOGI(TAG, "=== MAL MFA E-Voting Terminal v5.3 (ESP-IDF) ===");

    esp_err_t nvs_err = nvs_flash_init();
    if (nvs_err == ESP_ERR_NVS_NO_FREE_PAGES || nvs_err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase(); nvs_flash_init();
    }

    buttons_init();
    led_init();
    ledc_buzzer_hw_init();
    buzzer_init();
    buzzer_startup();
    set_led(0, 0, 255);

    gpio_set_direction((gpio_num_t)TFT_CS,    GPIO_MODE_OUTPUT);
    gpio_set_direction((gpio_num_t)PN5180_NSS, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)TFT_CS,    1);
    gpio_set_level((gpio_num_t)PN5180_NSS, 1);
    gpio_set_direction((gpio_num_t)TFT_LED, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)TFT_LED, 1);

    // Uses the dedicated TFT pins we defined in pin_defs.h (39, -1, 14)
    ili9341_init(SPI3_HOST, TFT_MOSI, TFT_MISO, TFT_SCLK, TFT_CS, TFT_DC, TFT_RST);
    // 3. PN5180 NFC initialization (attaches to the same SPI bus)
    wrapper_pn5180_init();

    // 4. Show splash screen (called after both SPI devices are ready)
    ui_display_logo();

    fp_init(FP_RX, FP_TX);
    if (!fp_verify_sensor()) {
        ui_display_error("Fingerprint sensor\nfailed!");
        while (1) vTaskDelay(pdMS_TO_TICKS(1000));
    }

   /* cam_init(CAM_RX, CAM_TX);
    if (!cam_send_init()) {
        ui_display_error("Camera module\nfailed!");
        while (1) vTaskDelay(pdMS_TO_TICKS(1000));
    }*/

    // ── Network init (non-blocking — never holds up the homepage) ──────────
    ui_display_status("Connecting to\nnetwork...");
    bool wifi_ok = net_wifi_connect();
    if (wifi_ok) {
        // NTP sync is quick (< 2 s normally) — worth doing before homepage
        // so TLS certificates validate correctly during the session.
        if (!net_ntp_sync()) {
            ESP_LOGW(TAG, "NTP sync failed — TLS cert validation may fail");
            ui_display_error("Time sync failed.\nContinuing anyway.");
            vTaskDelay(pdMS_TO_TICKS(1500));
        }else {
            // Apply West Africa Time (WAT) offset (UTC+1)
            setenv("TZ", "WAT-1", 1);
            tzset();
        }
        terminal_key_init();

        // --- BOOT RECOVERY HOOK ---
        net_check_and_recover_vote();

        set_led(0, 255, 0);
        buzzer_beep_ok();
    } else {
        ESP_LOGW(TAG, "WiFi failed — offline mode");
        ui_display_error("WiFi not connected.\nEnrollment + voting\nrequire network.");
        set_led(255, 165, 0);
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
    // Election config is fetched in the background AFTER the homepage is shown.
    // g_electionCfg.loaded starts false; task_election_config sets it to true
    // and calls ui_home_update_election_band() when ready.

    battery_adc_init();
    g_nav.currentSelection = 0;
    g_nav.maxSelections    = g_candidateCount;
    s_net_eg = xEventGroupCreate();
    g_net_eg = s_net_eg;
    s_last_activity_tick = xTaskGetTickCount();

    xTaskCreatePinnedToCore(task_network,   "TaskNet",   8192,  NULL, 2, &s_task_network,   0);
    xTaskCreatePinnedToCore(task_heartbeat, "TaskHeart", 8192,  NULL, 1, &s_task_heartbeat, 0);

    // ── Show homepage FIRST ───────────────────────────────────────────────
    // Election config loads in background via task_election_config.
    // The election band shows "Connecting to server..." until it arrives,
    // then ui_home_update_election_band() repaints it automatically.
    g_state = STATE_HOME;
    ui_display_home(g_home_sel);
    set_led(0, 255, 0);
    ESP_LOGI(TAG, "v5.3 ready — HOME shown, election config loading in background");

    // Background election config fetch (Core 0, low priority)
    xTaskCreatePinnedToCore(task_election_config, "TaskElection",
                            6144, NULL, 1, NULL, 0);

    xTaskCreatePinnedToCore(task_ui, "TaskUI", 12288, NULL, 2, &s_task_ui, 1);
    vTaskDelete(NULL);
}
