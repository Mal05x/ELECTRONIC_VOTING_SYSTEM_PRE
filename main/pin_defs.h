#pragma once
#include <stdint.h>

// ── SPI2 (FSPI) BUS: EXCLUSIVELY FOR PN5180 NFC ─────────
const uint8_t SPI_MOSI = 11;
const uint8_t SPI_MISO = 13;
const uint8_t SPI_CLK  = 12;

static const uint8_t PN5180_NSS  = 15;
static const uint8_t PN5180_BUSY = 4;
static const uint8_t PN5180_RST  = 2;

// ── SPI3 (HSPI) BUS: EXCLUSIVELY FOR ILI9341 TFT ────────
// We avoid Native USB (19/20) and RC-capacitors (0).
const uint8_t TFT_MOSI = 39;
const int8_t  TFT_MISO = -1;  // Set to -1 (Unused) to save a pin!
const uint8_t TFT_SCLK = 14;  // Clean pin freed from the button swap

const uint8_t TFT_CS   = 5;
const uint8_t TFT_DC   = 16;
const uint8_t TFT_RST  = 17;
const uint8_t TFT_LED  = 45;

// ── R307 Fingerprint sensor (UART1) ──────────────────────
static const uint8_t FP_RX = 8;
static const uint8_t FP_TX = 9;

// ── ESP32-CAM (UART2) ─────────────────────────────────────
static const uint8_t CAM_RX = 10;
static const uint8_t CAM_TX = 18;

// ── Navigation buttons (INPUT_PULLUP → LOW when pressed) ─
static const uint8_t BTN_UP     = 40;
static const uint8_t BTN_DOWN   = 41;
static const uint8_t BTN_LEFT   = 42;
static const uint8_t BTN_RIGHT  = 47;
static const uint8_t BTN_CENTER = 21;
static const uint8_t BTN_BACK   = 38;   // <── Swapped to BOOT pin to free up GPIO 14!

// ── RGB LED (Moved away from PSRAM pins!) ───────────────
static const uint8_t LED_R  = 6;
static const uint8_t LED_G  = 7;
static const uint8_t LED_B  = 48;

// ── Buzzer (Moved away from PSRAM pins!) ────────────────
static const uint8_t BUZZER = 46;

// ── Battery ADC ──────────────────────────────────────────
// Wiring: Batt+ → 100kΩ → GPIO1 → 100kΩ → GND (2:1 divider)
static const uint8_t BATT_ADC_PIN = 1;  // ADC1_CH0

// ── Tamper reed switch ───────────────────────────────────
// CLOSED (lid shut) → LOW, OPEN (lid removed) → HIGH
static const uint8_t TAMPER_PIN = 3;
