/**
 * buzzer.cpp — Passive buzzer driver (ESP-IDF LEDC)
 * ==================================================
 * Uses LEDC_TIMER_1 / LEDC_CHANNEL_3 — matching main.cpp's static
 * buzzer setup, which uses TIMER_0/CHANNEL_0-2 for the RGB LED and
 * TIMER_1/CHANNEL_3 for the buzzer on GPIO BUZZER (pin 46).
 *
 * CONFLICT FIX vs previous delivery:
 *   The previous buzzer.cpp incorrectly used LEDC_CHANNEL_2/TIMER_2.
 *   LEDC_CHANNEL_2 is already assigned to the BLUE LED in main.cpp's
 *   led_init(). Using it here would silently break the blue LED.
 *   Fixed to CHANNEL_3/TIMER_1, which main.cpp already owns for buzzer.
 *
 * Wiring (3-legged passive buzzer):
 *   Leg "+"  → 3.3 V  (through 100 Ω series resistor)
 *   Leg "S"  → GPIO 46  (BUZZER pin, LEDC PWM output)
 *   Leg "-"  → GND
 */

#include "buzzer.h"
#include "pin_defs.h"
#include "driver/ledc.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define BUZ_SPEED    LEDC_LOW_SPEED_MODE
#define BUZ_TIMER    LEDC_TIMER_1      // matches main.cpp buzzer_init()
#define BUZ_CHANNEL  LEDC_CHANNEL_3    // matches main.cpp buzzer_init()
#define BUZ_BITS     LEDC_TIMER_10_BIT
#define BUZ_DUTY_50  512               // 50% duty = maximum volume

static bool s_init = false;

void buzzer_init(void) {
    // Timer already configured by main.cpp's static buzzer_init().
    // This function only stores the init flag and sets duty to 0.
    // Do NOT re-init the timer — it would race with main.cpp's setup.
    ledc_set_duty(BUZ_SPEED, BUZ_CHANNEL, 0);
    ledc_update_duty(BUZ_SPEED, BUZ_CHANNEL);
    s_init = true;
}

void buzzer_off(void) {
    if (!s_init) return;
    ledc_set_duty(BUZ_SPEED, BUZ_CHANNEL, 0);
    ledc_update_duty(BUZ_SPEED, BUZ_CHANNEL);
}

void buzzer_tone(uint32_t freq_hz, uint32_t duration_ms) {
    if (!s_init) return;
    if (freq_hz == 0) {
        buzzer_off();
        vTaskDelay(pdMS_TO_TICKS(duration_ms));
        return;
    }
    ledc_set_freq(BUZ_SPEED, BUZ_TIMER, freq_hz);
    ledc_set_duty(BUZ_SPEED, BUZ_CHANNEL, BUZ_DUTY_50);
    ledc_update_duty(BUZ_SPEED, BUZ_CHANNEL);
    vTaskDelay(pdMS_TO_TICKS(duration_ms));
    buzzer_off();
}

// ── Sound effects ─────────────────────────────────────────────────────────
void buzzer_click(void)          { buzzer_tone(1000,  28); }
void buzzer_beep_card(void)      { buzzer_tone(1200,  50); }
void buzzer_beep_ok(void)        { buzzer_tone(1400, 90);  vTaskDelay(pdMS_TO_TICKS(30)); buzzer_tone(1900, 110); }
void buzzer_beep_error(void)     { buzzer_tone(600,  120); vTaskDelay(pdMS_TO_TICKS(40)); buzzer_tone(380,  300); }
void buzzer_beep_alert(void)     { buzzer_tone(900,  160); vTaskDelay(pdMS_TO_TICKS(80)); buzzer_tone(900,  160); }
void buzzer_beep_vote_success(void) {
    buzzer_tone(1000, 100); vTaskDelay(pdMS_TO_TICKS(40));
    buzzer_tone(1400, 100); vTaskDelay(pdMS_TO_TICKS(40));
    buzzer_tone(2000, 220);
}
void buzzer_startup(void) {
    buzzer_tone(800,  80); vTaskDelay(pdMS_TO_TICKS(20));
    buzzer_tone(1000, 80); vTaskDelay(pdMS_TO_TICKS(20));
    buzzer_tone(1300, 80); vTaskDelay(pdMS_TO_TICKS(20));
    buzzer_tone(1700, 140);
}
