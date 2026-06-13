#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * buzzer — Passive buzzer driver (LEDC PWM on BUZZER pin)
 * =========================================================
 * The three-legged passive buzzer wired to the terminal:
 *
 *   Leg 1  "+"  → 3.3 V  (through 100 Ω series resistor)
 *   Leg 2  "S"  → GPIO 46  (BUZZER pin, LEDC PWM output)
 *   Leg 3  "-"  → GND
 *
 * A passive buzzer produces sound ONLY when driven by a PWM signal at
 * the desired frequency.  DC voltage makes no sound.  This is why the
 * firmware uses ESP-IDF's LEDC peripheral — it generates the square
 * wave that makes the diaphragm vibrate at the correct pitch.
 *
 * Uses LEDC_TIMER_2 / LEDC_CHANNEL_2 to avoid conflicts with the TFT
 * backlight (LEDC_CHANNEL_0) and camera driver (LEDC_CHANNEL_0/1).
 *
 * Note: buzzer_tone() BLOCKS for duration_ms.  Call from a dedicated
 * buzzer task or the main state-machine task between state transitions.
 * Do NOT call from an ISR.
 */

void buzzer_init(void);
void buzzer_off(void);
void buzzer_tone(uint32_t freq_hz, uint32_t duration_ms);

// ── Composed sound effects ────────────────────────────────────────────────
void buzzer_click(void);          // 30 ms  — button / navigation tick
void buzzer_beep_card(void);      // 50 ms  — NFC card detected
void buzzer_beep_ok(void);        // 2-tone — step succeeded (PIN OK, FP OK…)
void buzzer_beep_error(void);     // long ↓ — step failed / card rejected
void buzzer_beep_alert(void);     // double — warning (tamper, session expired)
void buzzer_beep_vote_success(void); // rising 3-tone — vote submitted
void buzzer_startup(void);        // ascending melody — system boot

#ifdef __cplusplus
}
#endif
