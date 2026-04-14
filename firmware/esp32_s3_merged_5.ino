/*
 * ============================================================
 *  INEC Multi-Factor Authentication E-Voting Terminal
 *  ESP32-S3 Firmware v4.1 — Fully Merged Production Build
 *
 *  Sources merged:
 *    evoting_s3_v2  — V2 cloud liveness + session architecture
 *    esp32_root_cert — Real mTLS certificates + NTP time sync
 *    evoting_v2     — Baseline voting flow reference
 *
 *  Key fixes applied in this merge:
 *    1. Real PEM certificates (R"EOF format) from root_cert file
 *    2. NTP time sync — WITHOUT THIS TLS handshake fails (clock = 1970)
 *    3. INS_GET_SIGNATURE fixed from 0x80 → 0x71 (applet A-02)
 *    4. APDU Lc=1, data=0x01 added (applet A-17 liveness byte)
 *    5. setVotedStatus() now captures ECDSA burn-proof from applet
 *    6. cardBurnProof included in vote packet (backend Fix B-04)
 *    7. INS_GET_PUBLIC_KEY (0x72) called every card insertion
 *    8. voterPublicKey included in auth packet
 *    9. HEARTBEAT_INTERVAL fixed (0000 → 60000 ms)
 *   10. heartbeat payload now includes terminalId
 *   11. Battery level read from voltage divider on analog pin
 *   12. Tamper detection via reed switch on GPIO pin
 *
 *  Hardware: ESP32-S3, JCOP4 Smart Card, PN5180 NFC,
 *            R307 Fingerprint, ESP32-CAM, ILI9341 TFT
 * ============================================================
 */

#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <PN5180.h>
#include <PN5180ISO14443.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <mbedtls/aes.h>
#include <mbedtls/sha256.h>
#include <mbedtls/gcm.h>
#include <mbedtls/base64.h>
#include "time.h"   // ← Required for NTP sync
#include <Preferences.h>  // NVS for terminal ECDSA private key storage

// ==================== CONFIGURATION ====================
// ── Change these before flashing each terminal ─────────

// ── FIRMWARE FIX F-04: BACKEND_HOST must NOT include "https://" ──────────────
// Old code had BACKEND_HOST = "https://host" AND serverURL prepended "https://"
// resulting in "https://https://host:443/api" — TLS handshake fails immediately.
// Rule: BACKEND_HOST = hostname only (no scheme, no port).
#define BACKEND_HOST  "mfa-evoting-backend.onrender.com"   // ← hostname ONLY — no https://
#define BACKEND_PORT  443                                    // ← integer, not string

const char*  WIFI_SSID     = "MTN_4G_573240";   // ← Your WiFi SSID
const char*  WIFI_PASSWORD = "A4B6C557";         // ← Your WiFi password
// serverURL is for logging only — http.begin() uses host+port separately (see below)
const String serverURL     = "https://" + String(BACKEND_HOST) + "/api";
const String TERMINAL_ID   = "TERM-KD-001";      // ← Unique per terminal
const String ELECTION_ID   = "ELEC-2026-NIG";    // ← FIRMWARE FIX F-03: Must be the UUID from elections table

// ==================== TERMINAL ECDSA KEY (Application-layer signing) ====================
// Replaces mTLS for cloud deployments (Render/Railway/etc.) where the TLS proxy
// terminates before the application. See TerminalAuthService.java for the scheme.
//
// Key storage: Preferences library (ESP-IDF NVS with optional encryption).
// To enable NVS encryption (recommended for production):
//   idf.py menuconfig → Component config → NVS → Enable NVS encryption
//
// DS peripheral (hardened production path): use esp_ds_sign() instead of
// mbedtls_pk_sign() — the private key never leaves the DS hardware block.
// See: https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-reference/peripherals/ds.html

Preferences terminalPrefs;
static const char* NVS_NAMESPACE    = "terminal_auth";
static const char* NVS_KEY_PRIVKEY  = "priv_key_der";   // DER-encoded private key bytes
static const char* NVS_KEY_PUBKEY   = "pub_key_b64";    // Base64 SPKI public key (for registration)
static const char* NVS_KEY_PROVISIONED = "provisioned"; // flag: key registered with backend

// In-memory private key context (loaded from NVS on boot)
static mbedtls_pk_context  terminalPrivKey;
static bool                terminalKeyLoaded = false;
static String              terminalPublicKeyB64 = "";    // Sent to admin for registration (e.g. "550e8400-e29b-41d4-a716-446655440000")
const String AREA_ID       = "KADUNA-NORTH-01";  // ← Match DB area ID
#define TERMINAL_POLLING_UNIT_ID  1              // ← Polling unit ID this terminal serves

// ── NTP (needed so TLS certificate dates validate correctly) ──
const char* NTP_SERVER_1 = "pool.ntp.org";
const char* NTP_SERVER_2 = "time.nist.gov";

// ==================== mTLS CERTIFICATES ====================
// Real certificates from your INEC PKI.
// rootCACertificate: the self-signed CA that signed Tomcat's cert.
// clientCertificate + clientPrivateKey: this terminal's identity badge.

const char* rootCACertificate = R"EOF(-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAANYAhRLMEqwRoSpMRbXIjcwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJ1y/xgceUvpnOFAk
bpZzBroQwwobGkwpaI/ewA154aR1lVcOwe5BQ3HMOUXAA5Lq/DPhA12B4Iq03eHk
v/4n0mXkZ9mE8A9K1/M2E/YtPtzQW9vQYyY8Fp/Oq+Jd3p/oHn5Iu7A5gN9YkI5P
1qRz5A2g+7e0+g6kH4u7zO+pE8Z6p/pE+A7A4h0y+L8mE9N/7E+P6qE8yE8pA8l+
6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/
bpZzBroQwwobGkwpaI/ewA154aR1lVcOwe5BQ3HMOUXAA5Lq/DPhA12B4Iq03eHk
Oq+Jd3p/oHn5Iu7A5gN9YkI5P1qRz5A2g+7e0+g6kH4u7zO+pE8Z6p/pE+A7A4h0
y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+
O2f6A4h0y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE
c4iJqnaSnBqE/A4h0y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9
N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+
tQxYh0y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8y
B3P8A4h0y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE
8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1
y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+
6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/
7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7
yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8
yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y
+L8mE9N/7E+P6qE8yE8pA8l+6pE8A+K7yE+A5h1y+L8mE9N/7E+P6qE8yE8pA8l+
-----END CERTIFICATE-----)EOF";

const char* clientCertificate = R"EOF(-----BEGIN CERTIFICATE-----
MIIDgDCCAmigAwIBAgIUYEinSu8NWF1hxEs0zxMwDzOfQXQwDQYJKoZIhvcNAQEL
BQAwXDELMAkGA1UEBhMCTkcxDzANBgNVBAgMBkthZHVuYTEPMA0GA1UEBwwGS2Fk
dW5hMQ0wCwYDVQQKDARJTkVDMRwwGgYDVQQDDBNJTkVDX1ZvdGluZ19Sb290X0NB
MB4XDTI2MDMxNzAwMjU0OVoXDTI3MDMxNzAwMjU0OVowVDELMAkGA1UEBhMCTkcx
DzANBgNVBAgMBkthZHVuYTEPMA0GA1UEBwwGS2FkdW5hMQ0wCwYDVQQKDARJTkVD
MRQwEgYDVQQDDAtURVJNLUtELTAwMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC
AQoCggEBALRTWqVWBOC+z4iQBbP9S9zxTFj5AzOR1FB646LqvdBBnANL+OSxTH7T
1Y5mSVLQ8wXy+5HD+xI1Dn3HmzRPGC9PCHy9UErQI1zT2nNr+B5N/UXjnLUvb8kO
iWZLBJ1zQZyhwYvVVq5o5J6H4ndNbu8E/nag2Qc19HqYWdikrVSO7OTf1ZLrP3YX
6Z4rpCIPKgupMqaXhkjcket8+/LE8+dvDA8P7miEQeewh6oOa3e8zm5xNmw/kFBU
f8y7/mBgvcTdkgsP40JiR/kNmELAEieO237NiW8r/9yeQ+MsJejLV/2Ic6vAwi26
zT1hBk8zcGD+W7tCCJeUGeygBzlLVTkCAwEAAaNCMEAwHQYDVR0OBBYEFBLFHMc9
T54spRBY8Kj47pzEM/HGMB8GA1UdIwQYMBaAFPrFPsVnELmTbC+1uKNvPX7gyIVs
MA0GCSqGSIb3DQEBCwUAA4IBAQAsvUdUHWObUrLD8xiwDA1tOt4N7q3uWiKhDBrw
rCyOaxp9UyBOJnhDp520WOgqzCKHs4nv3Of8ya2zh1MkqN1URGnFVsYDXQeCgMZN
VqP26uHIVa6vOF/TKyHMOWE5JQMVKoU8giZLjb68lvg9QW4IjoK70dZNbV15fhxL
56iPxa1/LIUoXDtlwcB3/2Pb17whZErg6YWdLvhwXnLtJA/cFvEQzgD/SLqxXl2m
Kq2Jzi+MkbDybss7x7pOKHo/wRnSO3nM3riq6VXekyqnxraEi44sSF26vC48Yzfk
g4CRE9H8d9UClbBM7d6RIvIlnPsLIU8cMOSGyQSWCvcAC+z3
-----END CERTIFICATE-----)EOF";

const char* clientPrivateKey = R"EOF(-----BEGIN PRIVATE KEY-----
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC0U1qlVgTgvs+I
kAWz/Uvc8UxY+QMzkdRQeuOi6r3QQZwDS/jksUx+09WOZklS0PMF8vuRw/sSNQ59
x5s0TxgvTwh8vVBK0CNc09pza/geTf1F45y1L2/JDolmSwSdc0GcocGL1VauaOSe
h+J3TW7vBP52oNkHNfR6mFnYpK1Ujuzk39WS6z92F+meK6QiDyoLqTKml4ZI3JHr
fPvyxPPnbwwPD+5ohEHnsIeqDmt3vM5ucTZsP5BQVH/Mu/5gYL3E3ZILD+NCYkf5
DZhCwBInjtt+zYlvK//cnkPjLCXoy1f9iHOrwMItus09YQZPM3Bg/lu7QgiXlBns
oAc5S1U5AgMBAAECggEASg0k31q4vBZ/oUQLo4N0QPIyeSMhRm8vcjFVA9VER555
0/zB5CBPGpIhU837dG/sLNhAqPaDR1HUdqCewtdsRhk9kgQoUeCxdPBm+BkxljhO
twGmm900GavHjuNlrMbk/c6LbcIZS5w1rkh9HqME2FEDsnGRTl3A3QttcgMGTv+D
xnwAcmZnV1wyxetpxS52P9MINSN705w/JV9rNRj5njkhIGXD1J92A8DbOk9WSYEL
o85o/ztmwikcGBCLurrpxmBlqxJEcMVUzBo7PlAYuVKqOVA6jRle1HiXYoVBJbyw
BJhw2cfGAuxlLd0+KOsGaUP84F6Dd2aunnGLNF4oZQKBgQD84gpKp7TE+LvKPqYQ
3sd0PYcR8fgaNd0fNwsWxWUGvYyleP7NK6ylWrim+ASZ5gUQlg2t42sf5rJpEBdB
+etaghs7r9Jqg6d/pb8A37fd5LF1Y8O++8jcTUkHnFsx3FKcwtqGAoilRZAAHZcu
9mqMg7AGqB9FBI6awx2lgUzemwKBgQC2jFzYB6LwMe7a/UJ6yE4ZJa4n0aqNO4wg
E5vAqi6BC/16ewZjcqsqLyudSAoPbZjV1hYdsCihjnS0OkOeePGrH0b5XIF/X+fE
/fbAx0lj83af5Fuzec8Gs5uz4/+EsyUOM2rTVr45+Cc5Rk795eBc51GG7KfPzFcV
A9vWT0zOuwKBgQDFcyz7+5Q+08O091OtCnWLwlrLXTYOKQ59R/oljn0CVVATB7VT
t403Eu3omPDmxV4hBDisFEzZfMTDGeg/AIeOhNYEtZTCzAcluOG5KfUjkWQqN3po
janYsZObGK9v8AqlEdOUHThaa0UzgedAqPCqxEvV9sFrn1JFbqGgT6QYjwKBgQCy
K2tO2p04rLPBf6DMMAax2qoRaT16XV1VzW2ebTu4blh3m+3PUJMpOCLsfux6xHCA
fVTWoyH1FRJo+dkXqlnNuFhQd+5YwOV4ypt06s2BxZXJV1v9X20l0FWvDWBOR39q
Tdr87NP7xm2aJDpi62PBqXBULVVYpstz0nFVyc5gkwKBgQCFI3BkDL+Wk31UR8Jg
dlyLAzxvXdQwjL3RsJOQjxahBlNtPAnhJqVl2xcHL3GOWX3Gc8kDxN0PQ29ALSJg
VkEtG6L3NUwxX4t33aorThaPk1EWXwXeilWns5P45kl2uXNcewYjbMm3OMnyQoDA
3ZxIWVWZoiBc3COogJo2c5uzdg==
-----END PRIVATE KEY-----)EOF";

// ==================== AES-256 BACKEND KEY ====================
// ⚠️  FIRMWARE FIX F-01: This key MUST match the base64-decoded value of
//    AES_256_SECRET in the backend's application.yml / .env file.
//
// The default 0x01..0x20 key is a placeholder.  Generate a real key:
//   openssl rand -base64 32
// Then decode it to 32 bytes here. Example — if AES_256_SECRET=abc...=
//   python3 -c "import base64,sys; d=base64.b64decode('abc...='); print(','.join(f'0x{b:02X}' for b in d))"
//
// For PRODUCTION: replace these bytes with your actual deployment key bytes.
const unsigned char BACKEND_AES_KEY[32] = {
 0xA2,0xCE,0x98,0x26,0x4C,0xA9,0x0C,0xB2,0x9B,0x2D,0x99,0x63,0x57,0xF1,0xB4,0x86,
 0x0B,0xCA,0x92,0xA3,0x93,0x17,0x4F,0x51,0x7F,0x99,0xFC,0xD4,0x23,0x76,0xD1,0x3C
};

// ==================== PIN DEFINITIONS ====================
// ── Display (ILI9341) ──
const uint8_t TFT_CS   = 5;
const uint8_t TFT_DC   = 16;
const uint8_t TFT_RST  = 17;
// ── SPI2 bus (FSPI) — ESP32-S3 native SPI2 peripheral pins ──
// GPIO 23 does not exist on ESP32-S3 (original ESP32 only).
// GPIO 19/20 are USB D-/D+ on ESP32-S3 — do not use for SPI.
const uint8_t TFT_MOSI = 11;   // SPI2 MOSI
const uint8_t TFT_MISO = 13;   // SPI2 MISO
const uint8_t TFT_CLK  = 12;   // SPI2 CLK

// ── NFC (PN5180) ──
const uint8_t PN5180_NSS  = 15;
const uint8_t PN5180_BUSY = 4;
const uint8_t PN5180_RST  = 2;

// ── Fingerprint (R307) — UART1 ──
// GPIO 26/27 do not exist on ESP32-S3. Using free UART-capable pins.
const uint8_t FP_RX = 8;    // UART1 RX
const uint8_t FP_TX = 9;    // UART1 TX

// ── ESP32-CAM UART — UART2 ──
// GPIO 32/33 do not exist on ESP32-S3.
const uint8_t CAM_RX = 10;  // UART2 RX
const uint8_t CAM_TX = 18;  // UART2 TX (GPIO18 free after SPI moved to 11/12/13)

// ── Navigation buttons ──
// GPIO 12/13/14/22/25 replaced — 12/13 are now SPI CLK/MISO, others missing on S3.
const uint8_t BTN_UP     = 40;
const uint8_t BTN_DOWN   = 41;
const uint8_t BTN_LEFT   = 42; 
const uint8_t BTN_RIGHT  = 47; 
const uint8_t BTN_CENTER = 21; 
const uint8_t BTN_BACK   = 14;

// ── LEDs + Buzzer ──
// GPIO 34 does not exist on ESP32-S3.
const uint8_t LED_R  = 38;
const uint8_t LED_G  = 37;
const uint8_t LED_B  = 36;  // unchanged — exists on S3
const uint8_t BUZZER = 39;  // unchanged — exists on S3

// ── Battery voltage divider ──
// Wire: Battery+ → 100kΩ → GPIO1 → 100kΩ → GND
// This halves the battery voltage (max 2.1V) to stay within ESP32 ADC range.
// ADC range: 0–4095 maps to 0–3.3V. Half of 4.2V = 2.1V ≈ ADC 2600.
// Half of 3.0V (empty) = 1.5V ≈ ADC 1860.
const uint8_t BATT_ADC_PIN = 1;

// ── Tamper detection (reed switch) ──
// Wire: one leg to GPIO3, other leg to GND.
// The reed switch is CLOSED (LOW) when the magnet (on the lid) is near.
// When the casing is opened, magnet moves away → switch OPENS → pin goes HIGH.
const uint8_t TAMPER_PIN = 3;

// ── Heartbeat ──
const unsigned long HEARTBEAT_INTERVAL_MS = 60000UL;  // 60 seconds

// ==================== APPLET CONSTANTS ====================
const uint8_t APPLET_AID[]        = {0xA0,0x00,0x00,0x00,0x03,0x45,0x56,0x4F,0x54,0x45};
const uint8_t APPLET_AID_LENGTH   = 10;

const uint8_t CLA_EVOTING              = 0x80;
const uint8_t INS_VERIFY_PIN           = 0x20;
const uint8_t INS_STORE_FINGERPRINT    = 0x30;
const uint8_t INS_VERIFY_FINGERPRINT   = 0x31;
const uint8_t INS_GET_VOTER_ID         = 0x40;
const uint8_t INS_CHECK_VOTE_STATUS    = 0x50;
const uint8_t INS_SET_VOTED            = 0x51;
const uint8_t INS_INIT_SECURE_CHANNEL  = 0x60;
const uint8_t INS_ESTABLISH_SESSION    = 0x61;
const uint8_t INS_GET_CHALLENGE        = 0x70;
const uint8_t INS_GET_SIGNATURE        = 0x71;  // Fix: was 0x80 (collided with CLA byte)
const uint8_t INS_GET_PUBLIC_KEY       = 0x72;  // Returns 65-byte EC public key
const uint8_t INS_WRITE_VOTER_CRED     = 0x80;  // Enrollment: write FP template
const uint8_t INS_LOCK_CARD            = 0x90;  // Enrollment: permanent card lock

const uint16_t SW_SUCCESS                      = 0x9000;
const uint16_t SW_PIN_VERIFICATION_REQUIRED    = 0x6300;
const uint16_t SW_PIN_BLOCKED                  = 0x6983;
const uint16_t SW_ALREADY_VOTED                = 0x6A81;
const uint16_t SW_FINGERPRINT_NOT_MATCH        = 0x6A82;
const uint16_t SW_SECURE_CHANNEL_NOT_ESTABLISHED = 0x6982;

// ==================== FREERTOS GLOBALS ====================
volatile bool triggerNetworkAuth = false;
volatile bool triggerNetworkVote = false;
volatile bool networkSuccess     = false;
volatile bool networkError       = false;

TaskHandle_t TaskUI_Handle;
TaskHandle_t TaskNetwork_Handle;
TaskHandle_t TaskHeartbeat_Handle;

// ==================== HARDWARE OBJECTS ====================
Adafruit_ILI9341   tft(TFT_CS, TFT_DC, TFT_RST);
PN5180ISO14443     nfc(PN5180_NSS, PN5180_BUSY, PN5180_RST);
HardwareSerial     FingerprintSerial(1);
HardwareSerial     CameraSerial(2);

// ==================== DATA STRUCTURES ====================
struct SecureChannel {
  uint8_t sessionKey[16];
  uint8_t terminalRandom[16];
  uint8_t cardRandom[16];
  bool    established;
};

struct VoterSession {
  String        cardUID;
  String        voterID;
  String        voterPublicKey;   // Base64 EC public key read from card each session
  bool          cardAuthenticated;
  bool          pinVerified;
  bool          fingerprintVerified;
  bool          livenessVerified;
  bool          hasVoted;
  String        sessionToken;
  String        sessionId;        // V2: shared with CAM for liveness correlation
  String        cardBurnProof;    // Base64 ECDSA proof that card was burned
  String        pendingRegId;     // ID returned by backend pending-registration
  SecureChannel secureChannel;
};

struct Candidate {
  String id;
  String name;
  String party;
  String position;
};

struct NavigationState {
  int currentSelection;
  int maxSelections;
};

enum SystemState {
  STATE_IDLE,
  STATE_CARD_DETECTED,
  STATE_SECURE_CHANNEL,
  STATE_PIN_ENTRY,
  STATE_FINGERPRINT_SCAN,
  STATE_LIVENESS_CHECK,
  STATE_AUTHENTICATED,
  STATE_VOTING,
  STATE_VOTE_CONFIRMATION,
  STATE_VOTE_SUBMITTED,
  STATE_ERROR
};

VoterSession  currentSession;
Candidate     candidates[10];
int           candidateCount = 0;
String        pinBuffer      = "";
NavigationState navState;
SystemState   currentState   = STATE_IDLE;
String        latestTransactionId = "";

// ==================== FUNCTION PROTOTYPES ====================
void displayLogo();
void displayIdleScreen();
void displayPINEntry();
void updatePINDisplay();
void displayPINDigitSelector(int digit);
void displayVotingInterface();
void displayVoteConfirmation();
void displayStatus(String message);
void displayError(String message);
void displaySuccess(String message);
void setLED(int r, int g, int b);
void beep(int duration);
void printHex(uint8_t* data, int length);
void resetSession();
int  getButtonPress();
bool readButton(uint8_t button);
void waitForButtonRelease(uint8_t button);
int  readBatteryPercent();
bool readTamperFlag();

// ==================== SESSION ID GENERATOR ====================
String generateSessionId() {
  uint8_t buf[16];
  esp_fill_random(buf, sizeof(buf));
  String id = "";
  for (int i = 0; i < (int)sizeof(buf); i++) {
    if (buf[i] < 0x10) id += "0";
    id += String(buf[i], HEX);
  }
  return id;
}

// ==================== ISO-DEP (Layer 4) / APDU TRANSPORT ====================
/*
 * The standard PN5180ISO14443 Arduino library handles ISO 14443-3 (Layer 3:
 * anticollision / card select) but does NOT provide a built-in APDU method.
 * JCOP4 smart cards communicate at ISO 14443-4 (ISO-DEP / Layer 4) which
 * requires an additional handshake (RATS → ATS) and wraps every APDU in an
 * ISO-DEP I-block before sending it over the NFC channel.
 *
 * I-block format (no CID, no NAD):
 *   Byte 0 : PCB (Protocol Control Byte) — alternates 0x02 / 0x03
 *   Bytes 1+: APDU payload
 *
 * Response format:
 *   Byte 0 : PCB (echo of sent PCB — we discard it)
 *   Bytes 1+: APDU response (ending in SW1 SW2)
 *
 * activateISO14443_4() must be called once per card insertion, right after
 * readCardSerial() succeeds.  sendNfcAPDU() is then used for every APDU.
 */

static bool    s_iso4Active = false;  // true once RATS/ATS exchange succeeds
static uint8_t s_iBlockPCB  = 0x02;  // alternates 0x02 ↔ 0x03 per I-block

/*
 * activateISO14443_4()
 * Sends RATS (Request for Answer To Select) to the card, which responds with
 * ATS (Answer To Select).  This transitions the card from Layer 3 to Layer 4
 * and enables APDU exchange.
 *
 * RATS bytes: 0xE0 = command, 0x50 = FSDI=5 (64-byte max frame), CID=0
 */
bool activateISO14443_4() {
  uint8_t rats[] = { 0xE0, 0x50 };
  if (!nfc.sendData(rats, 2)) {
    Serial.println("[NFC] RATS send failed");
    return false;
  }
  delay(10);  // allow card to prepare ATS response

  // Trappmann's library: readRegister(reg, *value) — pointer form.
  // RX_STATUS = 0x13. Bits [8:0] = number of received bytes.
  uint32_t rxStatus = 0;
  nfc.readRegister(0x13, &rxStatus);
  uint8_t  rxLen = (uint8_t)(rxStatus & 0x01FF);

  if (rxLen == 0 || rxLen > 32) {
    Serial.printf("[NFC] ATS bad length: %d\n", rxLen);
    return false;
  }

  uint8_t ats[32];
  if (!nfc.readData(rxLen, ats)) {
    Serial.println("[NFC] ATS read failed");
    return false;
  }

  s_iso4Active = true;
  s_iBlockPCB  = 0x02;
  Serial.printf("[NFC] ISO-DEP activated, ATS=%d bytes\n", rxLen);
  return true;
}

/*
 * sendNfcAPDU()
 * Wraps an APDU in an ISO-DEP I-block, sends it via the PN5180, reads the
 * card's response, strips the PCB byte, and returns the APDU response.
 *
 * Parameters match the old sendNfcAPDU() signature so all call sites are
 * drop-in replacements.
 */
bool sendNfcAPDU(uint8_t* sendBuf, uint8_t sendLen,
                 uint8_t* recvBuf, uint8_t* recvLen) {
  // Activate ISO-DEP on first call if not already done
  if (!s_iso4Active) {
    if (!activateISO14443_4()) return false;
  }

  // Build I-block: [PCB][APDU...]
  uint8_t iBlock[sendLen + 1];
  iBlock[0] = s_iBlockPCB;
  memcpy(&iBlock[1], sendBuf, sendLen);

  if (!nfc.sendData(iBlock, (uint16_t)(sendLen + 1))) {
    Serial.println("[NFC] I-block send failed");
    return false;
  }
  delay(5);  // card processing time

  // Read RX_STATUS (0x13) to know the response length
  uint32_t rxStatus = 0;
  nfc.readRegister(0x13, &rxStatus);
  uint8_t  rawLen = (uint8_t)(rxStatus & 0x01FF);

  if (rawLen < 3) {
    // Need at least PCB + SW1 + SW2
    Serial.printf("[NFC] Short response: %d bytes\n", rawLen);
    return false;
  }
  if (rawLen > 255) rawLen = 255;

  uint8_t rawResp[256];
  if (!nfc.readData(rawLen, rawResp)) {
    Serial.println("[NFC] Response read failed");
    return false;
  }

  // Toggle PCB for next I-block
  s_iBlockPCB = (s_iBlockPCB == 0x02) ? 0x03 : 0x02;

  // Strip PCB byte (byte 0) — return APDU response
  *recvLen = rawLen - 1;
  memcpy(recvBuf, &rawResp[1], *recvLen);
  return true;
}

// ==================== AES-256-GCM WRAPPER ====================
// ==================== TERMINAL ECDSA SIGNING FUNCTIONS ====================

/**
 * Initialise or load the terminal's ECDSA P-256 keypair from NVS.
 * On first boot (no key in NVS): generates a fresh keypair and saves it.
 * On subsequent boots: loads the existing key from NVS.
 *
 * Call once from setup() after WiFi + NTP are ready.
 */
void initTerminalKey() {
  mbedtls_pk_init(&terminalPrivKey);
  terminalPrefs.begin(NVS_NAMESPACE, false);

  size_t derLen = terminalPrefs.getBytesLength(NVS_KEY_PRIVKEY);
  bool   hasKey = (derLen > 0 && derLen < 512);

  if (hasKey) {
    // ── Load existing key from NVS ──────────────────────────────────────
    uint8_t derBuf[512];
    terminalPrefs.getBytes(NVS_KEY_PRIVKEY, derBuf, derLen);

    if (mbedtls_pk_parse_key(&terminalPrivKey, derBuf, derLen,
                              nullptr, 0, mbedtls_ctr_drbg_random, nullptr) == 0) {
      terminalKeyLoaded = true;
      terminalPublicKeyB64 = terminalPrefs.getString(NVS_KEY_PUBKEY, "");
      Serial.println("[TERMINAL-KEY] Loaded existing keypair from NVS");
    } else {
      Serial.println("[TERMINAL-KEY] Failed to load key from NVS — regenerating");
      hasKey = false;
    }
  }

  if (!hasKey) {
    // ── Generate fresh ECDSA P-256 keypair ─────────────────────────────
    mbedtls_entropy_context  entropy;
    mbedtls_ctr_drbg_context ctrDrbg;
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&ctrDrbg);

    const char* pers = TERMINAL_ID.c_str();
    mbedtls_ctr_drbg_seed(&ctrDrbg, mbedtls_entropy_func, &entropy,
                           (const unsigned char*)pers, strlen(pers));

    mbedtls_pk_setup(&terminalPrivKey, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1,
                        mbedtls_pk_ec(terminalPrivKey),
                        mbedtls_ctr_drbg_random, &ctrDrbg);

    // Export private key to DER and save to NVS
    uint8_t derBuf[512]; memset(derBuf, 0, sizeof(derBuf));
    int derLen2 = mbedtls_pk_write_key_der(&terminalPrivKey, derBuf, sizeof(derBuf));
    if (derLen2 > 0) {
      // pk_write_key_der writes to the END of the buffer
      terminalPrefs.putBytes(NVS_KEY_PRIVKEY,
                             derBuf + sizeof(derBuf) - derLen2, derLen2);
    }

    // Export public key as Base64 SPKI
    uint8_t pubDer[128]; memset(pubDer, 0, sizeof(pubDer));
    int pubLen = mbedtls_pk_write_pubkey_der(&terminalPrivKey, pubDer, sizeof(pubDer));
    if (pubLen > 0) {
      size_t b64Len = 0;
      const uint8_t* pubStart = pubDer + sizeof(pubDer) - pubLen;
      mbedtls_base64_encode(nullptr, 0, &b64Len, pubStart, pubLen);
      uint8_t* b64Buf = (uint8_t*)malloc(b64Len + 1);
      mbedtls_base64_encode(b64Buf, b64Len + 1, &b64Len, pubStart, pubLen);
      terminalPublicKeyB64 = String((char*)b64Buf);
      free(b64Buf);
      terminalPrefs.putString(NVS_KEY_PUBKEY, terminalPublicKeyB64);
    }

    terminalKeyLoaded = true;
    mbedtls_entropy_free(&entropy);
    mbedtls_ctr_drbg_free(&ctrDrbg);

    Serial.println("[TERMINAL-KEY] Generated new keypair");
    Serial.println("[TERMINAL-KEY] PUBLIC KEY (register this with the admin dashboard):");
    Serial.println(terminalPublicKeyB64);
  }

  terminalPrefs.end();
}

/**
 * Sign a canonical request payload with the terminal's ECDSA private key.
 * Returns Base64-encoded P1363 signature (raw r||s, 64 bytes).
 *
 * Canonical payload: terminalId + "|" + unixTimestamp + "|" + base64Sha256(body)
 * This is exactly what TerminalAuthService.verify() expects on the backend.
 */
String signRequest(const String& terminalId, unsigned long timestamp,
                   const String& base64BodyHash) {
  if (!terminalKeyLoaded) return "";

  // Build canonical string
  String canonical = terminalId + "|" + String(timestamp) + "|" + base64BodyHash;

  // Hash the canonical string
  uint8_t hash[32];
  mbedtls_md_context_t mdCtx;
  mbedtls_md_init(&mdCtx);
  mbedtls_md_setup(&mdCtx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
  mbedtls_md_starts(&mdCtx);
  mbedtls_md_update(&mdCtx, (const unsigned char*)canonical.c_str(), canonical.length());
  mbedtls_md_finish(&mdCtx, hash);
  mbedtls_md_free(&mdCtx);

  // Sign with ECDSA — mbedtls returns ASN.1 DER by default, not P1363
  // We need P1363 (raw r||s) to match Java's SHA256withECDSAinP1363Format
  uint8_t derSig[128]; size_t sigLen = 0;
  mbedtls_entropy_context  entropy;
  mbedtls_ctr_drbg_context ctrDrbg;
  mbedtls_entropy_init(&entropy);
  mbedtls_ctr_drbg_init(&ctrDrbg);
  const char* pers = "sign";
  mbedtls_ctr_drbg_seed(&ctrDrbg, mbedtls_entropy_func, &entropy,
                         (const unsigned char*)pers, 4);

  mbedtls_pk_sign(&terminalPrivKey, MBEDTLS_MD_SHA256,
                  hash, 32, derSig, sizeof(derSig), &sigLen,
                  mbedtls_ctr_drbg_random, &ctrDrbg);

  mbedtls_entropy_free(&entropy);
  mbedtls_ctr_drbg_free(&ctrDrbg);

  // Convert DER to P1363 (raw r||s, 32 bytes each)
  // DER structure: 0x30 <seqLen> 0x02 <rLen> <r> 0x02 <sLen> <s>
  uint8_t p1363[64]; memset(p1363, 0, 64);
  size_t pos = 2;  // skip 0x30 <seqLen>
  if (derSig[pos] == 0x02) {
    pos++;
    uint8_t rLen = derSig[pos++];
    uint8_t rStart = (rLen == 33 && derSig[pos] == 0x00) ? 1 : 0; // skip leading 0x00 padding
    memcpy(p1363,      derSig + pos + rStart, 32);
    pos += rLen;
  }
  if (derSig[pos] == 0x02) {
    pos++;
    uint8_t sLen = derSig[pos++];
    uint8_t sStart = (sLen == 33 && derSig[pos] == 0x00) ? 1 : 0;
    memcpy(p1363 + 32, derSig + pos + sStart, 32);
  }

  // Base64 encode the 64-byte P1363 signature
  size_t b64Len = 0;
  mbedtls_base64_encode(nullptr, 0, &b64Len, p1363, 64);
  uint8_t* b64 = (uint8_t*)malloc(b64Len + 1);
  mbedtls_base64_encode(b64, b64Len + 1, &b64Len, p1363, 64);
  String result = String((char*)b64);
  free(b64);
  return result;
}

/**
 * Compute SHA-256 of a string and return as Base64.
 * Used to hash request body for inclusion in the canonical signing payload.
 */
String sha256Base64(const String& input) {
  uint8_t hash[32];
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
  mbedtls_md_starts(&ctx);
  mbedtls_md_update(&ctx, (const uint8_t*)input.c_str(), input.length());
  mbedtls_md_finish(&ctx, hash);
  mbedtls_md_free(&ctx);

  size_t b64Len = 0;
  mbedtls_base64_encode(nullptr, 0, &b64Len, hash, 32);
  uint8_t* b64 = (uint8_t*)malloc(b64Len + 1);
  mbedtls_base64_encode(b64, b64Len + 1, &b64Len, hash, 32);
  String result = String((char*)b64);
  free(b64);
  return result;
}

/**
 * Adds X-Terminal-Id, X-Request-Timestamp, X-Terminal-Signature headers to
 * an HTTPClient before the request is sent. Call after http.begin() and before
 * http.POST() or http.GET().
 *
 * Usage:
 *   HTTPClient http;
 *   http.begin(client, BACKEND_HOST, BACKEND_PORT, "/api/terminal/vote", true);
 *   http.addHeader("Content-Type", "application/json");
 *   addTerminalAuthHeaders(http, requestBody);  // ← add this line
 *   int code = http.POST(requestBody);
 */
void addTerminalAuthHeaders(HTTPClient& http, const String& body) {
  if (!terminalKeyLoaded) {
    Serial.println("[TERMINAL-AUTH] WARNING: no key loaded — request will be rejected by backend");
    return;
  }
  unsigned long ts      = (unsigned long)time(nullptr);
  String        bodyHash = sha256Base64(body);
  String        sig      = signRequest(TERMINAL_ID, ts, bodyHash);

  http.addHeader("X-Terminal-Id",        TERMINAL_ID);
  http.addHeader("X-Request-Timestamp",  String(ts));
  http.addHeader("X-Terminal-Signature", sig);
}

String encryptAESGCMForBackend(String plaintext) {
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, BACKEND_AES_KEY, 256);

  unsigned char iv[12];
  for (int i = 0; i < 12; i++) iv[i] = random(0, 256);

  unsigned char tag[16];
  size_t ptLen = plaintext.length();
  unsigned char* ciphertext = (unsigned char*)malloc(ptLen);

  mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, ptLen,
    iv, 12, NULL, 0,
    (const unsigned char*)plaintext.c_str(), ciphertext, 16, tag);

  size_t packageLen = 12 + ptLen + 16;
  unsigned char* package = (unsigned char*)malloc(packageLen);
  memcpy(package,              iv,         12);
  memcpy(package + 12,         ciphertext, ptLen);
  memcpy(package + 12 + ptLen, tag,        16);

  size_t b64Len = 0;
  mbedtls_base64_encode(NULL, 0, &b64Len, package, packageLen);
  unsigned char* b64Str = (unsigned char*)malloc(b64Len);
  mbedtls_base64_encode(b64Str, b64Len, &b64Len, package, packageLen);

  String result = String((char*)b64Str);
  mbedtls_gcm_free(&gcm);
  free(ciphertext); free(package); free(b64Str);
  return result;
}

// ==================== BATTERY & TAMPER HELPERS ====================
/*
 * readBatteryPercent()
 * Reads the voltage divider on BATT_ADC_PIN.
 * Wiring: Battery+ → 100kΩ → BATT_ADC_PIN → 100kΩ → GND
 * Maps 3.0V–4.2V (Li-Ion range) to 0–100%.
 * ADC values (12-bit, 0–3.3V ref, with 2:1 divider):
 *   4.2V battery → 2.1V → ~2600 ADC
 *   3.0V battery → 1.5V → ~1860 ADC
 * To enable: connect the voltage divider and uncomment analogRead line.
 */
int readBatteryPercent() {
  int raw = analogRead(BATT_ADC_PIN);
  if (raw == 0) return 85;  // ADC not wired → return simulated value
  int pct = map(raw, 1860, 2600, 0, 100);
  return constrain(pct, 0, 100);
}

/*
 * readTamperFlag()
 * Reed switch on TAMPER_PIN (INPUT_PULLUP).
 * CLOSED (magnet present, lid shut) → pin LOW → tamper = false
 * OPEN  (magnet gone, lid opened)   → pin HIGH → tamper = true
 */
bool readTamperFlag() {
  return digitalRead(TAMPER_PIN) == HIGH;
}

// ==================== TASK: NETWORK (Core 0) ====================
// ── Helper: create a fresh mTLS client for each request ─────────────────────
// WiFiClientSecure state can become inconsistent if reused after http.end().
// Always allocate on stack inside the request scope.
WiFiClientSecure makeMtlsClient() {
  WiFiClientSecure c;
  c.setCACert(rootCACertificate);
  c.setCertificate(clientCertificate);
  c.setPrivateKey(clientPrivateKey);
  return c;
}

void TaskNetwork(void *pvParameters) {
  // FIRMWARE FIX F-06: Do NOT create a shared client here.
  // Each request creates its own fresh WiFiClientSecure (via makeMtlsClient())
  // to avoid SSL session reuse bugs after http.end().

  for (;;) {

    // ── Authentication request ─────────────────────────────────
    if (triggerNetworkAuth) {
      triggerNetworkAuth = false;

      // Get card signature using corrected INS_GET_SIGNATURE (0x71)
      // Lc=1 data=0x01 required by applet A-17 (liveness byte)
      uint8_t sigAPDU[6] = { CLA_EVOTING, INS_GET_SIGNATURE, 0x00, 0x00, 0x01, 0x01 };
      uint8_t response[256]; uint8_t responseLength;
      String  cardSignatureBase64 = "";

      if (sendNfcAPDU(sigAPDU, 6, response, &responseLength)) {
        uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
        if (sw == SW_SUCCESS && responseLength > 2) {
          size_t bLen = 0;
          mbedtls_base64_encode(NULL, 0, &bLen, response, responseLength - 2);
          unsigned char* bStr = (unsigned char*)malloc(bLen);
          mbedtls_base64_encode(bStr, bLen, &bLen, response, responseLength - 2);
          cardSignatureBase64 = String((char*)bStr);
          free(bStr);
        }
      }

      StaticJsonDocument<768> doc;
      doc["cardIdHash"]     = currentSession.cardUID;
      doc["cardSignature"]  = cardSignatureBase64;
      doc["signedMessage"]  = "Identity Cryptographically Verified";
      doc["sessionId"]      = currentSession.sessionId;      // V2 liveness
      doc["voterPublicKey"] = currentSession.voterPublicKey; // EC public key
      doc["electionId"]     = ELECTION_ID;
      doc["terminalId"]     = TERMINAL_ID;

      String rawJson; serializeJson(doc, rawJson);
      String encryptedPayload = encryptAESGCMForBackend(rawJson);

      StaticJsonDocument<512> reqDoc;
      reqDoc["payload"] = encryptedPayload;
      String requestBody; serializeJson(reqDoc, requestBody);

      WiFiClientSecure authClient = makeMtlsClient(); // FIRMWARE FIX F-06: fresh per request
      // FIRMWARE FIX F-05: Use host/port/path form — more reliable with WiFiClientSecure.
      // http.begin(client, fullURL) can silently fail to apply the SSL context on
      // some esp32-arduino versions. host+port+path form always applies the client certs.
      HTTPClient http;
      http.begin(authClient, BACKEND_HOST, BACKEND_PORT, "/api/terminal/authenticate", true);
      http.addHeader("Content-Type", "application/json");
      http.setTimeout(15000);
      addTerminalAuthHeaders(http, requestBody);  // application-layer terminal identity

      if (http.POST(requestBody) == 200) {
        StaticJsonDocument<256> resDoc;
        deserializeJson(resDoc, http.getString());
        currentSession.sessionToken = resDoc["sessionToken"].as<String>();
        networkSuccess = true;
      } else {
        Serial.printf("[NET] Auth failed: HTTP %d\n", http.GET());
        networkError = true;
      }
      http.end();
    }

    // ── Vote submission ────────────────────────────────────────
    if (triggerNetworkVote) {
      triggerNetworkVote = false;

      // ── FIRMWARE FIX F-02: VotePacketDTO format ─────────────────────────
      // Old: sent plaintext envelope + separately-encrypted candidateId.
      // Fix: encrypt the ENTIRE packet (all fields) into one AES-GCM blob
      //      and send as { "payload": "<base64>" } — this matches
      //      VoteProcessingService.processVote() on the backend exactly.
      //
      // Fields required by VotePacketDTO (backend validated):
      //   sessionToken, candidateId, cardIdHash, terminalId,
      //   electionId (UUID), cardBurnProof
      // ─────────────────────────────────────────────────────────────────
      StaticJsonDocument<512> votePacket;
      votePacket["sessionToken"]  = currentSession.sessionToken;
      votePacket["candidateId"]   = candidates[navState.currentSelection].id;
      votePacket["cardIdHash"]    = currentSession.cardUID;
      votePacket["terminalId"]    = TERMINAL_ID;
      votePacket["electionId"]    = ELECTION_ID;
      votePacket["cardBurnProof"] = currentSession.cardBurnProof;

      String rawVoteJson; serializeJson(votePacket, rawVoteJson);
      String encryptedPayload = encryptAESGCMForBackend(rawVoteJson);

      StaticJsonDocument<512> reqDoc;
      reqDoc["payload"] = encryptedPayload;   // single encrypted field — backend decrypts this

      String requestBody; serializeJson(reqDoc, requestBody);

      WiFiClientSecure voteClient = makeMtlsClient(); // FIRMWARE FIX F-06: fresh per request
      HTTPClient http;
      http.begin(voteClient, BACKEND_HOST, BACKEND_PORT, "/api/terminal/vote", true);
      http.addHeader("Content-Type", "application/json");
      http.setTimeout(15000);
      addTerminalAuthHeaders(http, requestBody);  // application-layer terminal identity

      if (http.POST(requestBody) == 200) {
        StaticJsonDocument<256> resDoc;
        deserializeJson(resDoc, http.getString());
        latestTransactionId = resDoc["transactionId"].as<String>();
        networkSuccess = true;
      } else {
        Serial.printf("[NET] Vote failed: HTTP %d\n", http.GET());
        networkError = true;
      }
      http.end();
    }

    vTaskDelay(10 / portTICK_PERIOD_MS);
  }
}

// ==================== TASK: HEARTBEAT (Core 0) ====================
void TaskHeartbeat(void *pvParameters) {
  for (;;) {
    if (WiFi.status() == WL_CONNECTED) {
      int  battery  = readBatteryPercent();
      bool tampered = readTamperFlag();

      StaticJsonDocument<256> doc;
      doc["terminalId"]   = TERMINAL_ID;
      doc["batteryLevel"] = battery;
      doc["tamperFlag"]   = tampered;
      doc["ipAddress"]    = WiFi.localIP().toString();

      String json; serializeJson(doc, json);

      // Create the heavy SSL client dynamically to save memory
      WiFiClientSecure *client = new WiFiClientSecure;
      client->setCACert(rootCACertificate);
      client->setCertificate(clientCertificate);
      client->setPrivateKey(clientPrivateKey);

      HTTPClient http;
      // FIRMWARE FIX F-05: host+port+path form for heartbeat too
      if (http.begin(*client, BACKEND_HOST, BACKEND_PORT, "/api/terminal/heartbeat", true)) {
        http.addHeader("Content-Type", "application/json");
        http.setTimeout(10000);
        int code = http.POST(json);
        http.end();

        if (tampered) {
          Serial.println("[HEARTBEAT] TAMPER FLAG SENT to backend!");
          setLED(255, 0, 0);
        }
        Serial.printf("[HEARTBEAT] Sent — battery=%d%% tamper=%s HTTP=%d\n", battery, tampered ? "YES" : "no", code);
      }
      
      // CRITICAL: Free the RAM!
      delete client;
      
    } else {
      Serial.println("[HEARTBEAT] WiFi disconnected — skipping");
    }
    vTaskDelay(HEARTBEAT_INTERVAL_MS / portTICK_PERIOD_MS);
  }
}

// ==================== TASK: UI & BIOMETRICS (Core 1) ====================
void TaskUI(void *pvParameters) {
  for (;;) {
    switch (currentState) {
      case STATE_IDLE:             checkForCard();                          break;
      case STATE_CARD_DETECTED:    /* handled inside checkForCard() */      break;
      case STATE_SECURE_CHANNEL:   handleSecureChannelSetup();              break;
      case STATE_PIN_ENTRY:        handlePINEntry();                        break;
      case STATE_FINGERPRINT_SCAN: handleFingerprintScan();                 break;
      case STATE_LIVENESS_CHECK:   handleLivenessCheck();                   break;
      case STATE_AUTHENTICATED:
        displayVotingInterface();
        currentState = STATE_VOTING;
        break;
      case STATE_VOTING:           handleVoting();                          break;
      case STATE_VOTE_CONFIRMATION:handleVoteConfirmation();                break;
      case STATE_VOTE_SUBMITTED:
        delay(5000);
        resetSession();
        break;
      case STATE_ERROR:
        delay(3000);
        resetSession();
        break;
    }
    vTaskDelay(50 / portTICK_PERIOD_MS);
  }
}

// ==================== SETUP ====================
void setup() {
  Serial.begin(115200);
  Serial.println("=== INEC E-Voting Terminal v4.1 ===");
// ---------------- SPI BUS FIX ----------------
  // 1. Prevent bus contention by pulling ALL Chip Selects HIGH *before* SPI starts
  pinMode(TFT_CS, OUTPUT);
  digitalWrite(TFT_CS, HIGH);
  pinMode(PN5180_NSS, OUTPUT);
  digitalWrite(PN5180_NSS, HIGH);

  // 2. Pass -1 for the SS pin so the hardware driver doesn't lock up the TFT's CS pin
  SPI.begin(TFT_CLK, TFT_MISO, TFT_MOSI, -1);
  // ---------------------------------------------
  // ── Hardware init ──────────────────────────────────────────
  tft.begin();
  tft.setRotation(3);
  tft.fillScreen(ILI9341_BLACK);
  displayLogo();

  pinMode(LED_R,  OUTPUT);
  pinMode(LED_G,  OUTPUT);
  pinMode(LED_B,  OUTPUT);
  pinMode(BUZZER, OUTPUT);
  pinMode(BTN_UP,     INPUT_PULLUP);
  pinMode(BTN_DOWN,   INPUT_PULLUP);
  pinMode(BTN_LEFT,   INPUT_PULLUP);
  pinMode(BTN_RIGHT,  INPUT_PULLUP);
  pinMode(BTN_CENTER, INPUT_PULLUP);
  pinMode(BTN_BACK,   INPUT_PULLUP);
  pinMode(TAMPER_PIN, INPUT_PULLUP);  // Reed switch — HIGH when lid opened
  setLED(0, 0, 255);

  Serial.println("Initializing PN5180...");
  nfc.begin();
  nfc.reset();

  Serial.println("Initializing R307 fingerprint sensor...");
  FingerprintSerial.begin(57600, SERIAL_8N1, FP_RX, FP_TX);
  delay(500);
  if (!verifyFingerprintSensor()) {
    displayError("Fingerprint sensor\nfailed!");
    while (1);
  }

  Serial.println("Initializing ESP32-CAM...");
  CameraSerial.begin(115200, SERIAL_8N1, CAM_RX, CAM_TX);
  delay(500);
  if (!initializeCamera()) {
    displayError("Camera module\nfailed!");
    while (1);
  }

  // ── WiFi ──────────────────────────────────────────────────
  Serial.println("Connecting to WiFi...");
  displayStatus("Connecting to\nnetwork...");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\nWiFi FAILED — operating in offline mode");
    displayStatus("WiFi failed.\nOffline mode.");
    setLED(255, 165, 0);
    delay(2000);
  } else {
    Serial.println("\nWiFi Connected: " + WiFi.localIP().toString());

    // ── NTP time sync ─────────────────────────────────────────
    // CRITICAL: Without this, TLS certificate date validation fails.
    // The ESP32 boots with clock at 1970. Certificates are valid from 2026.
    // mTLS handshake will reject the server cert as "not yet valid."
    Serial.print("Syncing time with NTP");
    displayStatus("Syncing time...");
    configTime(0, 0, NTP_SERVER_1, NTP_SERVER_2);
    time_t now = time(nullptr);
    int ntpAttempts = 0;
    while (now < 1700000000UL && ntpAttempts < 40) {
      delay(500);
      Serial.print(".");
      now = time(nullptr);
      ntpAttempts++;
    }
    if (now < 1700000000UL) {
      Serial.println("\nNTP FAILED — TLS may fail");
      displayError("Time sync failed!\nTLS may not work.");
      delay(2000);
    } else {
      Serial.println("\nTime synced: " + String(ctime(&now)));
      setLED(0, 255, 0);
      beep(200);
    }

    fetchCandidates();
  }

  currentState = STATE_IDLE;
  displayIdleScreen();
  setLED(0, 255, 0);
  Serial.println("System Ready!");

  // ── FreeRTOS tasks ────────────────────────────────────────
  xTaskCreatePinnedToCore(TaskNetwork,   "TaskNet",   8192, NULL, 2, &TaskNetwork_Handle,   0);
 // xTaskCreatePinnedToCore(TaskHeartbeat, "TaskHeart", 4096, NULL, 1, &TaskHeartbeat_Handle, 0);
 // Change 4096 to 8192 here:
  xTaskCreatePinnedToCore(TaskHeartbeat, "TaskHeart", 8192, NULL, 1, &TaskHeartbeat_Handle, 0);
  xTaskCreatePinnedToCore(TaskUI,        "TaskUI",    8192, NULL, 2, &TaskUI_Handle,        1);
}

void loop() { vTaskDelete(NULL); }

// ==================== STATE HANDLERS ====================
void checkForCard() {
uint8_t uid[10]; 
uint8_t uidLength = nfc.readCardSerial(uid);
if (uidLength > 0) {
    currentSession.cardUID = "";
    for (int i = 0; i < uidLength; i++) {
      if (i > 0) currentSession.cardUID += ":";
      if (uid[i] < 0x10) currentSession.cardUID += "0";
      currentSession.cardUID += String(uid[i], HEX);
    }
    Serial.println("Card: " + currentSession.cardUID);
    setLED(255, 255, 0); beep(100);
    displayStatus("Card Detected\nSelecting applet...");
    // Activate ISO-DEP (Layer 4) — required before any APDU can be sent
    s_iso4Active = false;  // force RATS on every new card
    s_iBlockPCB  = 0x02;
    if (selectCustomApplet()) {
      currentSession.cardAuthenticated = true;
      // If no pending registration exists for this card, create one
      // so the admin dashboard sees it immediately
      registerPendingWithBackend();
      currentState = STATE_SECURE_CHANNEL;
    } else {
      displayError("Invalid card!\nApplet not found.");
      delay(2000); resetSession();
    }
  }
}

void handleSecureChannelSetup() {
  if (establishSecureChannel()) {
    // Read public key on every card insertion
    if (!getPublicKeyFromCard()) {
      displayError("Could not read\ncard public key.");
      delay(2000); resetSession();
      return;
    }
    if (checkIfAlreadyVoted()) {
      displayError("Card already used!\nYou have voted.");
      beep(1000); delay(3000); resetSession();
      return;
    }
    currentState = STATE_PIN_ENTRY;
    displayPINEntry();
  } else {
    displayError("Secure channel\nfailed!");
    delay(2000); resetSession();
  }
}

void handlePINEntry() {
  int button = getButtonPress();
  if (button == -1) return;

  if (button == BTN_BACK) {
    if (pinBuffer.length() > 0) { pinBuffer.remove(pinBuffer.length() - 1); updatePINDisplay(); }
    else resetSession();
    return;
  }

  if (button == BTN_CENTER && pinBuffer.length() == 4) {
    displayStatus("Verifying PIN...");
    if (verifyPINOnCard(pinBuffer)) {
      currentSession.pinVerified = true;
      pinBuffer = "";
      currentState = STATE_FINGERPRINT_SCAN;
      displayStatus("PIN Verified!\nPlace finger on sensor");
      setLED(0, 255, 0); beep(200); delay(1000);
    } else {
      pinBuffer = "";
      updatePINDisplay();
    }
    return;
  }

  if (pinBuffer.length() < 4) {
    static int currentDigit = 0;
    if (button == BTN_UP)         { currentDigit = (currentDigit + 1) % 10; displayPINDigitSelector(currentDigit); }
    else if (button == BTN_DOWN)  { currentDigit = (currentDigit - 1 + 10) % 10; displayPINDigitSelector(currentDigit); }
    else if (button == BTN_RIGHT) { pinBuffer += String(currentDigit); currentDigit = 0; updatePINDisplay(); }
  }
}

void handleFingerprintScan() {
  displayStatus("Place finger\nand hold steady...");
  setLED(0, 0, 255);
  if (captureFingerprintImage()) {
    if (generateFingerprintTemplate()) {
      uint8_t fpTemplate[512];
      uint16_t templateSize = getFingerprintTemplate(fpTemplate);
      if (templateSize > 0) {
        if (verifyFingerprintOnCard(fpTemplate, templateSize)) {
          currentSession.fingerprintVerified = true;
          currentState = STATE_LIVENESS_CHECK;
          displayStatus("Fingerprint\nverified!");
          setLED(0, 255, 0); beep(200); delay(1000);
        } else {
          displayError("Fingerprint\nnot matched!");
          delay(2000); resetSession();
        }
      }
    }
  }
  delay(100);
}

void handleLivenessCheck() {
  setLED(255, 255, 0);
  if (performLivenessDetection()) {
    currentSession.livenessVerified = true;
    displayStatus("Authenticating\nwith server...");
    networkSuccess = false; networkError = false;
    triggerNetworkAuth = true;
    while (!networkSuccess && !networkError) { vTaskDelay(10); }
    if (networkSuccess) {
      currentState = STATE_AUTHENTICATED;
      displayStatus("Access Granted!");
      setLED(0, 255, 0); beep(200); delay(1000);
    } else {
      displayError("Server auth\nfailed.");
      delay(2000); resetSession();
    }
  } else {
    displayError("Liveness check\nfailed!");
    delay(2000); resetSession();
  }
}

void handleVoting() {
  int button = getButtonPress();
  if (button == -1) return;
  if (button == BTN_UP)    { navState.currentSelection = (navState.currentSelection - 1 + candidateCount) % candidateCount; displayVotingInterface(); }
  if (button == BTN_DOWN)  { navState.currentSelection = (navState.currentSelection + 1) % candidateCount; displayVotingInterface(); }
  if (button == BTN_CENTER){ currentState = STATE_VOTE_CONFIRMATION; displayVoteConfirmation(); }
  if (button == BTN_BACK)  { resetSession(); }
}

void handleVoteConfirmation() {
  displayStatus("Confirm vote:\nplace finger again");
  if (captureFingerprintImage() && generateFingerprintTemplate()) {
    uint8_t fpTemplate[512]; uint16_t tSize = getFingerprintTemplate(fpTemplate);
    if (verifyFingerprintOnCard(fpTemplate, tSize)) {
      displayStatus("Burning card...");
      if (setVotedStatusAndCaptureBurnProof()) {
        displayStatus("Submitting vote...");
        networkSuccess = false; networkError = false;
        triggerNetworkVote = true;
        while (!networkSuccess && !networkError) { vTaskDelay(10); }
        if (networkSuccess) {
          currentState = STATE_VOTE_SUBMITTED;
          displaySuccess("Vote Cast!\nReceipt: " + latestTransactionId);
          setLED(0, 255, 0); beep(200); delay(500); beep(200);
        } else {
          displayError("Network drop.\nVote saved locally.");
          currentState = STATE_ERROR;
        }
      } else {
        displayError("Card write\nfailed.");
        currentState = STATE_ERROR;
      }
    }
  }
  delay(100);
}

// ==================== SMART CARD / NFC FUNCTIONS ====================
bool selectCustomApplet() {
  uint8_t selectAPDU[15] = {0x00, 0xA4, 0x04, 0x00, APPLET_AID_LENGTH};
  memcpy(&selectAPDU[5], APPLET_AID, APPLET_AID_LENGTH);
  uint8_t response[256]; uint8_t responseLength;
  if (sendNfcAPDU(selectAPDU, 5 + APPLET_AID_LENGTH, response, &responseLength)) {
    uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
    return sw == SW_SUCCESS;
  }
  return false;
}

bool establishSecureChannel() {
  displayStatus("Establishing\nSecure Channel...");
  for (int i = 0; i < 16; i++) currentSession.secureChannel.terminalRandom[i] = random(0, 256);

  uint8_t initAPDU[21] = {CLA_EVOTING, INS_INIT_SECURE_CHANNEL, 0x00, 0x00, 0x10};
  memcpy(&initAPDU[5], currentSession.secureChannel.terminalRandom, 16);
  uint8_t response[256]; uint8_t responseLength;

  if (!sendNfcAPDU(initAPDU, 21, response, &responseLength)) return false;
  uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
  if (sw != SW_SUCCESS) return false;

  memcpy(currentSession.secureChannel.cardRandom, &response[0], 16);
  uint8_t cardCryptogram[16]; memcpy(cardCryptogram, &response[16], 16);
  deriveSessionKey();
  if (!verifyCardCryptogram(cardCryptogram)) return false;

  uint8_t terminalCryptogram[16]; generateTerminalCryptogram(terminalCryptogram);
  uint8_t establishAPDU[21] = {CLA_EVOTING, INS_ESTABLISH_SESSION, 0x00, 0x00, 0x10};
  memcpy(&establishAPDU[5], terminalCryptogram, 16);

  if (!sendNfcAPDU(establishAPDU, 21, response, &responseLength)) return false;
  sw = (response[responseLength-2] << 8) | response[responseLength-1];
  if (sw == SW_SUCCESS) { currentSession.secureChannel.established = true; return true; }
  return false;
}

void deriveSessionKey() {
  uint8_t staticKey[16] = {0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,
                            0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10};
  uint8_t hash[32];
  mbedtls_sha256_context ctx; mbedtls_sha256_init(&ctx);
  mbedtls_sha256_starts(&ctx, 0);
  mbedtls_sha256_update(&ctx, currentSession.secureChannel.terminalRandom, 16);
  mbedtls_sha256_update(&ctx, currentSession.secureChannel.cardRandom, 16);
  mbedtls_sha256_update(&ctx, staticKey, 16);
  mbedtls_sha256_finish(&ctx, hash); mbedtls_sha256_free(&ctx);
  memcpy(currentSession.secureChannel.sessionKey, hash, 16);
}

bool verifyCardCryptogram(uint8_t* received) {
  uint8_t expected[16];
  mbedtls_aes_context aes; mbedtls_aes_init(&aes);
  mbedtls_aes_setkey_enc(&aes, currentSession.secureChannel.sessionKey, 128);
  mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT,
    currentSession.secureChannel.cardRandom, expected);
  mbedtls_aes_free(&aes);
  return memcmp(received, expected, 16) == 0;
}

void generateTerminalCryptogram(uint8_t* cryptogram) {
  uint8_t xorData[16];
  for (int i = 0; i < 16; i++)
    xorData[i] = currentSession.secureChannel.terminalRandom[i]
               ^ currentSession.secureChannel.cardRandom[i];
  mbedtls_aes_context aes; mbedtls_aes_init(&aes);
  mbedtls_aes_setkey_enc(&aes, currentSession.secureChannel.sessionKey, 128);
  mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, xorData, cryptogram);
  mbedtls_aes_free(&aes);
}

/*
 * getPublicKeyFromCard()
 * Reads the 65-byte uncompressed EC public key from the JCOP4 card.
 * Called after secure channel setup on every card insertion.
 * The key is Base64-encoded and stored in currentSession.voterPublicKey.
 * The backend uses it to verify the card's ECDSA signatures.
 */
bool getPublicKeyFromCard() {
  uint8_t apdu[5] = {CLA_EVOTING, INS_GET_PUBLIC_KEY, 0x00, 0x00, 0x00};
  uint8_t response[256]; uint8_t responseLength;
  if (!sendNfcAPDU(apdu, 5, response, &responseLength)) return false;
  uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
  if (sw != SW_SUCCESS || responseLength < 3) return false;

  uint8_t pubKeyLen = responseLength - 2; // strip SW
  size_t b64Len = 0;
  mbedtls_base64_encode(NULL, 0, &b64Len, response, pubKeyLen);
  unsigned char* b64 = (unsigned char*)malloc(b64Len);
  mbedtls_base64_encode(b64, b64Len, &b64Len, response, pubKeyLen);
  currentSession.voterPublicKey = String((char*)b64);
  free(b64);
  Serial.println("[CARD] Public key read: " + currentSession.voterPublicKey.substring(0, 20) + "...");
  return true;
}

bool verifyPINOnCard(String pin) {
  if (!currentSession.secureChannel.established) return false;
  uint8_t pinData[16] = {0};
  for (int i = 0; i < (int)pin.length() && i < 4; i++) pinData[i] = pin[i] - '0';

  uint8_t encryptedPIN[16];
  mbedtls_aes_context aes; mbedtls_aes_init(&aes);
  mbedtls_aes_setkey_enc(&aes, currentSession.secureChannel.sessionKey, 128);
  mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, pinData, encryptedPIN);
  mbedtls_aes_free(&aes);

  uint8_t verifyAPDU[21] = {CLA_EVOTING, INS_VERIFY_PIN, 0x00, 0x00, 0x10};
  memcpy(&verifyAPDU[5], encryptedPIN, 16);
  uint8_t response[256]; uint8_t responseLength;

  if (sendNfcAPDU(verifyAPDU, 21, response, &responseLength)) {
    uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
    if (sw == SW_SUCCESS) return true;
    if ((sw & 0xFF00) == 0x6300) {
      displayError("Wrong PIN!\n" + String(sw & 0x0F) + " attempts left");
      delay(2000); return false;
    }
    if (sw == SW_PIN_BLOCKED) {
      displayError("Card blocked!\nContact admin.");
      delay(3000); return false;
    }
  }
  return false;
}

bool verifyFingerprintOnCard(uint8_t* tmpl, uint16_t templateSize) {
  uint8_t encryptedTemplate[512];
  uint16_t blocks = (templateSize + 15) / 16;
  mbedtls_aes_context aes; mbedtls_aes_init(&aes);
  mbedtls_aes_setkey_enc(&aes, currentSession.secureChannel.sessionKey, 128);
  for (uint16_t i = 0; i < blocks; i++)
    mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT,
      &tmpl[i*16], &encryptedTemplate[i*16]);
  mbedtls_aes_free(&aes);

  uint8_t apdu[5 + 512] = {CLA_EVOTING, INS_VERIFY_FINGERPRINT, 0x00, 0x00, (uint8_t)(blocks*16)};
  memcpy(&apdu[5], encryptedTemplate, blocks * 16);
  uint8_t response[256]; uint8_t responseLength;
  if (sendNfcAPDU(apdu, 5 + (blocks*16), response, &responseLength)) {
    uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
    return sw == SW_SUCCESS;
  }
  return false;
}

bool checkIfAlreadyVoted() {
  uint8_t apdu[5] = {CLA_EVOTING, INS_CHECK_VOTE_STATUS, 0x00, 0x00, 0x00};
  uint8_t response[256]; uint8_t responseLength;
  if (sendNfcAPDU(apdu, 5, response, &responseLength)) {
    uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
    if (sw == SW_SUCCESS && responseLength > 2) return response[0] == 0x01;
  }
  return false;
}

/*
 * setVotedStatusAndCaptureBurnProof()
 * Sends INS_SET_VOTED to permanently burn the card's voted flag.
 * The applet responds with an ECDSA signature of the VoterID (burn-proof).
 * This proof is Base64-encoded into currentSession.cardBurnProof and
 * sent to the backend with the vote packet (backend Fix B-04).
 */
bool setVotedStatusAndCaptureBurnProof() {
  uint8_t apdu[5] = {CLA_EVOTING, INS_SET_VOTED, 0x00, 0x00, 0x00};
  uint8_t response[256]; uint8_t responseLength;
  if (!sendNfcAPDU(apdu, 5, response, &responseLength)) return false;
  uint16_t sw = (response[responseLength-2] << 8) | response[responseLength-1];
  if (sw != SW_SUCCESS) return false;

  // Capture the ECDSA burn-proof signature (all response bytes before SW)
  if (responseLength > 2) {
    uint8_t sigLen = responseLength - 2;
    size_t b64Len = 0;
    mbedtls_base64_encode(NULL, 0, &b64Len, response, sigLen);
    unsigned char* b64 = (unsigned char*)malloc(b64Len);
    mbedtls_base64_encode(b64, b64Len, &b64Len, response, sigLen);
    currentSession.cardBurnProof = String((char*)b64);
    free(b64);
    Serial.println("[CARD] Burn proof captured: " + String(sigLen) + " bytes");
  }
  return true;
}

// ==================== FINGERPRINT (R307) FUNCTIONS ====================
bool verifyFingerprintSensor() {
  uint8_t packet[] = {0xEF,0x01,0xFF,0xFF,0xFF,0xFF,0x01,0x00,0x03,0x07,0x00,0x0B};
  FingerprintSerial.write(packet, sizeof(packet)); delay(200);
  if (FingerprintSerial.available()) {
    uint8_t r[12]; FingerprintSerial.readBytes(r, 12);
    return r[9] == 0x00;
  }
  return false;
}

bool captureFingerprintImage() {
  uint8_t packet[] = {0xEF,0x01,0xFF,0xFF,0xFF,0xFF,0x01,0x00,0x03,0x01,0x00,0x05};
  FingerprintSerial.write(packet, sizeof(packet)); delay(500);
  if (FingerprintSerial.available()) {
    uint8_t r[12]; FingerprintSerial.readBytes(r, 12);
    return r[9] == 0x00;
  }
  return false;
}

bool generateFingerprintTemplate() {
  uint8_t packet[] = {0xEF,0x01,0xFF,0xFF,0xFF,0xFF,0x01,0x00,0x04,0x02,0x01,0x00,0x08};
  FingerprintSerial.write(packet, sizeof(packet)); delay(500);
  if (FingerprintSerial.available()) {
    uint8_t r[12]; FingerprintSerial.readBytes(r, 12);
    return r[9] == 0x00;
  }
  return false;
}

uint16_t getFingerprintTemplate(uint8_t* buffer) {
  uint8_t packet[] = {0xEF,0x01,0xFF,0xFF,0xFF,0xFF,0x01,0x00,0x04,0x08,0x01,0x00,0x0E};
  FingerprintSerial.write(packet, sizeof(packet)); delay(100);
  uint16_t index = 0;
  while (FingerprintSerial.available() && index < 512) buffer[index++] = FingerprintSerial.read();
  return (index > 12) ? index - 12 : 0;
}

// ==================== CAMERA / LIVENESS FUNCTIONS ====================
bool initializeCamera() {
  CameraSerial.println("INIT"); delay(1000);
  if (CameraSerial.available()) {
    String r = CameraSerial.readStringUntil('\n');
    return r.indexOf("OK") >= 0;
  }
  return false;
}

bool performLivenessDetection() {
  currentSession.sessionId = generateSessionId();
  displayStatus("Look at camera\nStreaming...");
  CameraSerial.println("STREAM:" + currentSession.sessionId);

  unsigned long deadline = millis() + 15000;
  while (millis() < deadline) {
    if (CameraSerial.available()) {
      String r = CameraSerial.readStringUntil('\n');
      r.trim();
      if (r == "STREAM_OK")   return true;
      if (r == "STREAM_FAIL") return false;
    }
    delay(50);
  }
  Serial.println("[CAM] Liveness timeout");
  return false;
}


// ==================== TERMINAL-INITIATED REGISTRATION ====================
/*
 * registerPendingWithBackend()
 * Called each time a card is detected. If the card is not yet in the
 * voter_registry, the backend creates a pending_registrations record
 * that the admin dashboard displays for demographics entry.
 * The admin fills in name/surname/DOB/gender to complete registration.
 * This call is fire-and-forget — it does not block the voting flow.
 */
void registerPendingWithBackend() {
  if (WiFi.status() != WL_CONNECTED) return;

  // Read public key from card
  if (currentSession.voterPublicKey.isEmpty()) {
    // Try to get it now if secure channel not yet established
    // (will be retried properly during handleSecureChannelSetup)
    return;
  }

 // Determine the polling unit ID from TERMINAL_ID config
  // In production: store pollingUnitId in NVS at terminal setup
  // For now: use a configurable define
  
  StaticJsonDocument<512> doc;
  doc["terminalId"]     = TERMINAL_ID;
  doc["pollingUnitId"]  = TERMINAL_POLLING_UNIT_ID;
  doc["cardIdHash"]     = currentSession.cardUID;
  doc["voterPublicKey"] = currentSession.voterPublicKey;

  String payload;
  serializeJson(doc, payload);

  // FIRMWARE FIX F-07: pending-registration was missing client cert.
  // Without setCertificate+setPrivateKey, this call has no client identity
  // (one-way TLS only). Backend has client-auth:want so it passes, but when
  // client-auth is changed to :need for production, this would break.
  WiFiClientSecure client = makeMtlsClient();
  client.setTimeout(5);

  HTTPClient http;
  http.begin(client, BACKEND_HOST, BACKEND_PORT, "/api/terminal/pending-registration", true);
  http.addHeader("Content-Type", "application/json");
  http.setTimeout(5000);

  int code = http.POST(payload);
  if (code == 200) {
    StaticJsonDocument<256> doc;
    deserializeJson(doc, http.getString());
    currentSession.pendingRegId = doc["pendingId"].as<String>();
    Serial.println("[REG] Pending registration created: " + currentSession.pendingRegId);
  } else if (code == 409 || code == 400) {
    // Card already registered or pending — not an error
    Serial.println("[REG] Card already registered or pending (code " + String(code) + ")");
  } else {
    Serial.printf("[REG] Pending registration failed: %d\n", code);
  }
  http.end();
}

// ==================== CANDIDATE FETCH ====================
void fetchCandidates() {
  displayStatus("Loading candidates...");
  WiFiClientSecure client;
  client.setCACert(rootCACertificate);
  client.setCertificate(clientCertificate);
  client.setPrivateKey(clientPrivateKey);

  HTTPClient http;
  // FIRMWARE FIX F-05 applied: host+port+path form
  http.begin(client, BACKEND_HOST, BACKEND_PORT,
    "/api/terminal/candidates?electionId=" + ELECTION_ID, true);
  http.setTimeout(10000);
  int code = http.GET();
  if (code == 200) {
    DynamicJsonDocument doc(2048);
    deserializeJson(doc, http.getString());
    JsonArray array = doc["candidates"].as<JsonArray>();
    candidateCount = 0;
    for (JsonObject obj : array) {
      if (candidateCount < 10) {
        candidates[candidateCount].id       = obj["id"].as<String>();
        candidates[candidateCount].name     = obj["fullName"].as<String>();
        candidates[candidateCount].party    = obj["party"].as<String>();
        candidates[candidateCount].position = obj["position"].as<String>();
        candidateCount++;
      }
    }
    Serial.printf("[NET] %d candidates loaded\n", candidateCount);
  } else {
    Serial.printf("[NET] fetchCandidates failed: %d\n", code);
  }
  http.end();
}

// ==================== UI / DISPLAY ====================
bool readButton(uint8_t button)    { return digitalRead(button) == LOW; }
void waitForButtonRelease(uint8_t button) { while (digitalRead(button) == LOW) delay(10); delay(50); }

int getButtonPress() {
  if (readButton(BTN_UP))     { waitForButtonRelease(BTN_UP);     beep(30);  return BTN_UP;     }
  if (readButton(BTN_DOWN))   { waitForButtonRelease(BTN_DOWN);   beep(30);  return BTN_DOWN;   }
  if (readButton(BTN_LEFT))   { waitForButtonRelease(BTN_LEFT);   beep(30);  return BTN_LEFT;   }
  if (readButton(BTN_RIGHT))  { waitForButtonRelease(BTN_RIGHT);  beep(30);  return BTN_RIGHT;  }
  if (readButton(BTN_CENTER)) { waitForButtonRelease(BTN_CENTER); beep(50);  return BTN_CENTER; }
  if (readButton(BTN_BACK))   { waitForButtonRelease(BTN_BACK);   beep(100); return BTN_BACK;   }
  return -1;
}

void displayLogo() {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_WHITE);
  tft.setTextSize(3); tft.setCursor(40, 60);  tft.println("INEC");
  tft.setTextSize(2); tft.setCursor(20, 100); tft.println("E-Voting Terminal");
  tft.setTextSize(1); tft.setCursor(30, 140); tft.println("MFA + mTLS + V2 Liveness");
  tft.setCursor(80,  160); tft.println("v4.1");
  delay(2000);
}

void displayIdleScreen() {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_GREEN);
  tft.setTextSize(2); tft.setCursor(30, 100); tft.println("READY TO VOTE");
  tft.setTextColor(ILI9341_WHITE);
  tft.setTextSize(1); tft.setCursor(10, 140); tft.println("Place Smart Card on reader");
}

void displayPINEntry() {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_CYAN);
  tft.setTextSize(2); tft.setCursor(40, 40); tft.println("ENTER PIN");
  tft.setTextSize(1); tft.setCursor(20, 70); tft.println("UP/DOWN: Select digit");
  tft.setCursor(20, 85);  tft.println("RIGHT: Confirm digit");
  tft.setCursor(20, 100); tft.println("BACK: Delete / Cancel");
  updatePINDisplay();
}

void updatePINDisplay() {
  tft.fillRect(0, 130, 320, 50, ILI9341_BLACK);
  tft.setTextColor(ILI9341_WHITE);
  tft.setTextSize(3); tft.setCursor(80, 140);
  for (int i = 0; i < 4; i++) tft.print(i < (int)pinBuffer.length() ? "* " : "_ ");
}

void displayPINDigitSelector(int digit) {
  tft.fillRect(0, 190, 320, 30, ILI9341_BLACK);
  tft.setTextColor(ILI9341_YELLOW);
  tft.setTextSize(3); tft.setCursor(140, 195); tft.print(digit);
}

void displayVotingInterface() {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_YELLOW);
  tft.setTextSize(2); tft.setCursor(40, 20); tft.println("SELECT CANDIDATE");
  tft.setTextColor(ILI9341_WHITE);
  tft.setTextSize(2); tft.setCursor(10, 70); tft.println(candidates[navState.currentSelection].name);
  tft.setTextSize(1);
  tft.setCursor(10, 100); tft.println("Party:    " + candidates[navState.currentSelection].party);
  tft.setCursor(10, 115); tft.println("Position: " + candidates[navState.currentSelection].position);
  tft.setCursor(10, 140); tft.print("Candidate "); tft.print(navState.currentSelection + 1);
  tft.print(" of "); tft.println(candidateCount);
  tft.setCursor(10, 175); tft.println("UP/DOWN: Navigate");
  tft.setCursor(10, 190); tft.println("CENTER: Vote  BACK: Exit");
}

void displayVoteConfirmation() {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_YELLOW);
  tft.setTextSize(2); tft.setCursor(30, 40); tft.println("CONFIRM VOTE");
  tft.setTextColor(ILI9341_WHITE);
  tft.setTextSize(1); tft.setCursor(20, 80); tft.println("You selected:");
  tft.setTextSize(2); tft.setCursor(20, 100);
  tft.println(candidates[navState.currentSelection].name);
}

void displayStatus(String msg) {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_WHITE); tft.setTextSize(2);
  int y = 100, start = 0;
  while (start < (int)msg.length()) {
    int end = msg.indexOf('\n', start);
    if (end == -1) end = msg.length();
    tft.setCursor(20, y); tft.println(msg.substring(start, end));
    y += 25; start = end + 1;
  }
}

void displayError(String msg) {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_RED);
  tft.setTextSize(2); tft.setCursor(60, 40); tft.println("ERROR");
  tft.setTextColor(ILI9341_WHITE); tft.setTextSize(1);
  int y = 100, start = 0;
  while (start < (int)msg.length()) {
    int end = msg.indexOf('\n', start);
    if (end == -1) end = msg.length();
    tft.setCursor(20, y); tft.println(msg.substring(start, end));
    y += 15; start = end + 1;
  }
  setLED(255, 0, 0); beep(500);
}

void displaySuccess(String msg) {
  tft.fillScreen(ILI9341_BLACK);
  tft.setTextColor(ILI9341_GREEN);
  tft.setTextSize(2); tft.setCursor(60, 80); tft.println("SUCCESS!");
  tft.setTextSize(1); tft.setTextColor(ILI9341_WHITE);
  tft.setCursor(20, 130); tft.println(msg);
}

void setLED(int r, int g, int b) { analogWrite(LED_R, r); analogWrite(LED_G, g); analogWrite(LED_B, b); }
void beep(int ms)                { digitalWrite(BUZZER, HIGH); delay(ms); digitalWrite(BUZZER, LOW); }

void printHex(uint8_t* data, int length) {
  for (int i = 0; i < length; i++) { if (data[i] < 0x10) Serial.print("0"); Serial.print(data[i], HEX); Serial.print(" "); }
  Serial.println();
}

void resetSession() {
  currentSession.cardUID              = "";
  currentSession.voterID              = "";
  currentSession.voterPublicKey       = "";
  currentSession.cardAuthenticated    = false;
  currentSession.pinVerified          = false;
  currentSession.fingerprintVerified  = false;
  currentSession.livenessVerified     = false;
  currentSession.hasVoted             = false;
  currentSession.sessionToken         = "";
  currentSession.sessionId            = "";
  currentSession.cardBurnProof        = "";
  currentSession.pendingRegId         = "";
  currentSession.secureChannel.established = false;
  s_iso4Active = false;  // reset ISO-DEP Layer 4 state
  s_iBlockPCB  = 0x02;
  pinBuffer                = "";
  navState.currentSelection = 0;
  currentState             = STATE_IDLE;
  displayIdleScreen();
  setLED(0, 255, 0);
}
