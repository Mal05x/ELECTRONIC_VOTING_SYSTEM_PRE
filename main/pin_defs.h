#pragma once
#include <stdint.h>

// ── SHARED SPI BUS (SPI2 / FSPI) ──────────────────────────
const uint8_t SPI_MOSI = 11;
const uint8_t SPI_MISO = 13;
const uint8_t SPI_CLK  = 12;

// ── ILI9341 TFT ───────────────────────────────────────────
const uint8_t TFT_CS   = 5;
const uint8_t TFT_DC   = 16;
const uint8_t TFT_RST  = 17;
const uint8_t TFT_LED  = 45;

// ── PN5180 NFC (shares SPI2 bus) ─────────────────────────
static const uint8_t PN5180_NSS  = 15;
static const uint8_t PN5180_BUSY = 4;
static const uint8_t PN5180_RST  = 2;

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
static const uint8_t BTN_BACK   = 14;

// ── RGB LED ───────────────────────────────────────────────
// ── RGB LED (Moved away from PSRAM pins!) ───────────────
static const uint8_t LED_R  = 6;
static const uint8_t LED_G  = 7;
static const uint8_t LED_B  = 48;

// ── Buzzer (Moved away from PSRAM pins!) ────────────────
static const uint8_t BUZZER = 46; // Or 20, if you aren't using the native USB port

// ── Battery ADC ──────────────────────────────────────────
// Wiring: Batt+ → 100kΩ → GPIO1 → 100kΩ → GND (2:1 divider)
static const uint8_t BATT_ADC_PIN = 1;  // ADC1_CH0

// ── Tamper reed switch ───────────────────────────────────
// CLOSED (lid shut) → LOW, OPEN (lid removed) → HIGH
static const uint8_t TAMPER_PIN = 3;
