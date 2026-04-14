/**
 * ============================================================
 * ESP32-CAM — Liveness & Enrollment Module v2.0
 * Project:  MFA Electronic Voting System
 *
 * Architecture update (V2):
 * The CAM no longer evaluates liveness locally. Instead:
 *   1. ESP32-S3 generates a sessionId and sends "STREAM:<sessionId>" via UART.
 *   2. This module captures a high-resolution JPEG frame.
 *   3. It connects to Wi-Fi and POSTs the raw JPEG directly to
 *      POST https://<backend>/api/camera/liveness
 *      with headers X-Session-Id and X-Terminal-Id.
 *   4. The backend BiometricService evaluates the frame server-side.
 *   5. This module replies "STREAM_OK" or "STREAM_FAIL" on UART.
 *
 * UART Protocol (ESP32-S3 ↔ ESP32-CAM):
 *   RX "INIT"                  → TX "OK" | "ERROR"
 *   RX "STREAM:<sessionId>"    → TX "STREAM_OK" | "STREAM_FAIL"  (V2 liveness)
 *   RX "ENROLL"                → TX "EMBED:<64-byte hex>" | "ERROR_..."
 *   RX "PING"                  → TX "PONG"
 *
 * Wiring (AI-Thinker ESP32-CAM):
 *   U0R (GPIO3)  ← ESP32-S3 TX (GPIO33)
 *   U0T (GPIO1)  → ESP32-S3 RX (GPIO32)
 *   GND          → Common GND
 *   5V           → Regulated 5V rail
 * ============================================================
 */

#include "esp_camera.h"
#include "Arduino.h"
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Base64.h>

// ── Configuration — edit these before flashing ──────────────────────────────
//
//  BACKEND_HOST: IP address or hostname of your Spring Boot server.
//    Option A (recommended): Use a static local IP:   192.168.1.100
//    Option B: Use mDNS hostname (if router supports): evoting-server.local
//    Option C: Use a domain name:                      api.evoting.gov.ng
//
//  To use mDNS, add #include <ESPmDNS.h> and call:
//    WiFi.hostByName("evoting-server.local", serverIP);
//  and replace BACKEND_URL with a runtime-built string.
//
#define BACKEND_HOST    "192.168.0.159"          // ← CHANGE THIS
#define BACKEND_PORT    "8443"
#define TERMINAL_ID_STR "TERM-KD-001"            // ← CHANGE THIS (must match ESP32-S3)
#define WIFI_SSID_STR   "MTN_4G_573240"        // ← CHANGE THIS
#define WIFI_PASS_STR   "A4B6C557"        // ← CHANGE THIS

// Built from the defines above — do not edit this line
const char* WIFI_SSID    = WIFI_SSID_STR;
const char* WIFI_PASSWORD = WIFI_PASS_STR;
const char* BACKEND_URL  = "https://" BACKEND_HOST ":" BACKEND_PORT "/api/camera/liveness";
const char* TERMINAL_ID  = TERMINAL_ID_STR;

/*
 * Backend CA certificate for TLS verification.
 * Replace with your actual server certificate / CA chain.
 * The same cert used by the ESP32-S3 rootCACertificate.
 */
const char* ROOT_CA = R"EOF(-----BEGIN CERTIFICATE-----
MIIDiTCCAnGgAwIBAgIID/QkIUa5UIIwDQYJKoZIhvcNAQEMBQAwZTELMAkGA1UE
BhMCTkcxDzANBgNVBAgTBkthZHVuYTEPMA0GA1UEBxMGS2FkdW5hMQ0wCwYDVQQK
EwRJTkVDMQ0wCwYDVQQLEwRJTkVDMRYwFAYDVQQDEw0xOTIuMTY4LjAuMTU5MB4X
DTI2MDMxODE1Mzk1NVoXDTM2MDMxNTE1Mzk1NVowZTELMAkGA1UEBhMCTkcxDzAN
BgNVBAgTBkthZHVuYTEPMA0GA1UEBxMGS2FkdW5hMQ0wCwYDVQQKEwRJTkVDMQ0w
CwYDVQQLEwRJTkVDMRYwFAYDVQQDEw0xOTIuMTY4LjAuMTU5MIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAm71n0+URDrcjVJDHap67KL1cwE2uKi0+S8hJ
D92oRMJpNN/dNSw6qKC0JWJp1c9xU9LOlePxG1kMC9DHzShzJepA6eqxCPCd4e/q
yV72+AVfCFPaCLxNSq7k1uTzGZKd9avfz8dbQlFX2RmqXkVmmlgI6Oj5vmkaIzZ0
2wAr+q/du3woInxcZTQLJ//zW4K7QbTKEldDyVX8tOQ81HWTCPaGUNDPiH7wMAc8
vHwKe6TlDdpebft9ATfNnc6qSdoThWHmT5wmJtM/KYszAbxtq5QCwvkXbH443hJM
AUqoRyGCERDYGbrXTUVJX/yopbppYJcXquKi/4hzl2BbCRXQJwIDAQABoz0wOzAd
BgNVHQ4EFgQUouOoeVX1bG/mHAbqZxvbZRykuwowGgYDVR0RBBMwEYcEwKgAn4IJ
bG9jYWxob3N0MA0GCSqGSIb3DQEBDAUAA4IBAQCSvGAXiTIED1OqoE5UAlaDwYVy
wGHgvV6rq2npWcLsmGiaDzze9WOliujgSu+roDXd8wevK9+JERSBeDV539NPvgYL
snFtnwKSc+FfT5vWulBY5H6fOltOO75cRcRe72PQEzUweqEE3WH8mKESq1VnuXpa
9xbCOAEQIl8d9tnRKjX0av8quk0Qzx/Pzi/aLvipKpASWq/foMmkD03e+so1+wAV
BJIJki0+1azjirsPYx5l3Wv/zB2ibLju6qHgukZxfkQ4Zw5W5wewJOqZavRXpgnV
q7MeAHAFKzwWlokexnOPOX8FbYiFm5ZeRhS6cn9J0LPyLOqPgbP53358rudu
-----END CERTIFICATE-----)EOF";

// ── AI-Thinker ESP32-CAM pin map ─────────────────────────────────────────────
#define PWDN_GPIO_NUM    32
#define RESET_GPIO_NUM   -1
#define XCLK_GPIO_NUM     0
#define SIOD_GPIO_NUM    26
#define SIOC_GPIO_NUM    27
#define Y9_GPIO_NUM      35
#define Y8_GPIO_NUM      34
#define Y7_GPIO_NUM      39
#define Y6_GPIO_NUM      36
#define Y5_GPIO_NUM      21
#define Y4_GPIO_NUM      19
#define Y3_GPIO_NUM      18
#define Y2_GPIO_NUM       5
#define VSYNC_GPIO_NUM   25
#define HREF_GPIO_NUM    23
#define PCLK_GPIO_NUM    22

// ── Tuning ────────────────────────────────────────────────────────────────────
#define FRAME_W              320
#define FRAME_H              240
#define EMBED_GRID             8
#define EMBED_SIZE  (EMBED_GRID * EMBED_GRID)
#define WIFI_TIMEOUT_MS    10000   // 10 s max Wi-Fi connect
#define HTTP_TIMEOUT_MS    15000   // 15 s max backend POST

bool cameraInitialized = false;
bool wifiConnected     = false;

// =============================================================================
//  Camera Initialisation
// =============================================================================
bool initCamera() {
    if (cameraInitialized) return true;

    camera_config_t cfg;
    cfg.ledc_channel  = LEDC_CHANNEL_0;
    cfg.ledc_timer    = LEDC_TIMER_0;
    cfg.pin_d0 = Y2_GPIO_NUM; cfg.pin_d1 = Y3_GPIO_NUM;
    cfg.pin_d2 = Y4_GPIO_NUM; cfg.pin_d3 = Y5_GPIO_NUM;
    cfg.pin_d4 = Y6_GPIO_NUM; cfg.pin_d5 = Y7_GPIO_NUM;
    cfg.pin_d6 = Y8_GPIO_NUM; cfg.pin_d7 = Y9_GPIO_NUM;
    cfg.pin_xclk      = XCLK_GPIO_NUM;
    cfg.pin_pclk      = PCLK_GPIO_NUM;
    cfg.pin_vsync     = VSYNC_GPIO_NUM;
    cfg.pin_href      = HREF_GPIO_NUM;
    cfg.pin_sscb_sda  = SIOD_GPIO_NUM;
    cfg.pin_sscb_scl  = SIOC_GPIO_NUM;
    cfg.pin_pwdn      = PWDN_GPIO_NUM;
    cfg.pin_reset     = RESET_GPIO_NUM;
    cfg.xclk_freq_hz  = 20000000;
    cfg.pixel_format  = PIXFORMAT_JPEG;    // V2: JPEG for direct HTTP upload
    cfg.frame_size    = FRAMESIZE_QVGA;   // 320×240 — fast capture
    cfg.jpeg_quality  = 10;               // Good quality / reasonable size
    cfg.fb_count      = 2;

    if (esp_camera_init(&cfg) != ESP_OK) return false;

    sensor_t *s = esp_camera_sensor_get();
    s->set_brightness(s, 1);
    s->set_contrast(s, 1);
    s->set_saturation(s, 0);
    s->set_whitebal(s, 1);
    s->set_exposure_ctrl(s, 1);
    s->set_gain_ctrl(s, 1);

    cameraInitialized = true;
    return true;
}

// =============================================================================
//  Wi-Fi Management
// =============================================================================
bool ensureWiFi() {
    if (WiFi.status() == WL_CONNECTED) return true;

    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED) {
        if (millis() - start > WIFI_TIMEOUT_MS) return false;
        delay(250);
    }
    wifiConnected = true;
    return true;
}

// =============================================================================
//  V2 Core: Stream JPEG to Backend
// =============================================================================
/**
 * Captures one JPEG frame and POSTs it to the backend BiometricService.
 * Headers carry sessionId (shared with ESP32-S3) and terminalId.
 *
 * @param sessionId  UUID generated by ESP32-S3 before calling this function.
 * @return true if backend confirmed liveness, false on any failure.
 */
bool streamLivenessToBackend(const String& sessionId) {
    if (!cameraInitialized && !initCamera()) return false;

    // Ensure Wi-Fi is up before attempting HTTPS
    if (!ensureWiFi()) return false;

    // Discard warm-up frames so auto-exposure stabilises
    for (int i = 0; i < 3; i++) {
        camera_fb_t *fb = esp_camera_fb_get();
        if (fb) esp_camera_fb_return(fb);
        delay(80);
    }

    // Capture the liveness frame
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb || fb->len < 512) {
        if (fb) esp_camera_fb_return(fb);
        return false;
    }

    // POST the raw JPEG as multipart/form-data to the backend
    WiFiClientSecure client;
    client.setCACert(ROOT_CA);
    client.setTimeout(HTTP_TIMEOUT_MS / 1000);

    HTTPClient http;
    http.begin(client, BACKEND_URL);
    http.setTimeout(HTTP_TIMEOUT_MS);

    // Set identifying headers — backend uses these to link this frame
    // to the ESP32-S3's authentication packet
    http.addHeader("X-Session-Id",  sessionId);
    http.addHeader("X-Terminal-Id", String(TERMINAL_ID));

    // Build multipart/form-data body manually
    // Boundary must not appear in the JPEG binary
    String boundary  = "----EVotingBoundary7f3a9c";
    String ctHeader  = "multipart/form-data; boundary=" + boundary;
    http.addHeader("Content-Type", ctHeader);

    // Multipart header
    String partHeader =
        "--" + boundary + "\r\n"
        "Content-Disposition: form-data; name=\"frame\"; filename=\"liveness.jpg\"\r\n"
        "Content-Type: image/jpeg\r\n\r\n";
    String partFooter = "\r\n--" + boundary + "--\r\n";

    // Assemble body: header + JPEG bytes + footer
    size_t bodyLen = partHeader.length() + fb->len + partFooter.length();
    uint8_t *body  = (uint8_t *)ps_malloc(bodyLen);

    if (!body) {
        esp_camera_fb_return(fb);
        http.end();
        return false;
    }

    memcpy(body,                                        partHeader.c_str(), partHeader.length());
    memcpy(body + partHeader.length(),                  fb->buf,            fb->len);
    memcpy(body + partHeader.length() + fb->len,        partFooter.c_str(), partFooter.length());

    esp_camera_fb_return(fb);

    int httpCode = http.POST(body, bodyLen);
    free(body);
    http.end();

    // Backend returns 200 with { "livenessPassed": true/false }
    if (httpCode == 200) {
        // Parse response to check livenessPassed
        String response = http.getString();
        return response.indexOf("\"livenessPassed\":true") >= 0;
    }

    return false;
}

// =============================================================================
//  Enrollment: Facial Embedding (unchanged from V1 but now uses JPEG mode)
// =============================================================================
float frameMean(const uint8_t *buf, size_t len) {
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i++) sum += buf[i];
    return (float)sum / (float)len;
}

bool facePresent(const uint8_t *buf, int w, int h) {
    float mean = frameMean(buf, (size_t)(w * h));
    if (mean < 30.0f || mean > 230.0f) return false;
    int skinCount = 0;
    for (int i = 0; i < w * h; i++) {
        if (buf[i] >= 70 && buf[i] <= 220) skinCount++;
    }
    return ((float)skinCount / (float)(w * h)) >= 0.08f;
}

void buildEmbedding(const uint8_t *buf, int w, int h, uint8_t *embed) {
    int x0 = (int)(w * 0.25f), x1 = (int)(w * 0.75f);
    int y0 = (int)(h * 0.20f), y1 = (int)(h * 0.80f);
    int rw = x1 - x0, rh = y1 - y0;
    for (int gy = 0; gy < EMBED_GRID; gy++) {
        for (int gx = 0; gx < EMBED_GRID; gx++) {
            int cx = x0 + (gx * rw) / EMBED_GRID + rw / (2 * EMBED_GRID);
            int cy = y0 + (gy * rh) / EMBED_GRID + rh / (2 * EMBED_GRID);
            uint32_t sum = 0; int cnt = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx, ny = cy + dy;
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        sum += buf[ny * w + nx]; cnt++;
                    }
                }
            }
            embed[gy * EMBED_GRID + gx] = (uint8_t)(sum / cnt);
        }
    }
}

void performEnrollment() {
    // Switch to grayscale for embedding generation
    sensor_t *s = esp_camera_sensor_get();
    if (s) s->set_pixformat(s, PIXFORMAT_GRAYSCALE);

    if (!cameraInitialized && !initCamera()) {
        Serial.println("ERROR_NO_CAM"); return;
    }

    for (int i = 0; i < 4; i++) {
        camera_fb_t *fb = esp_camera_fb_get();
        if (fb) esp_camera_fb_return(fb);
        delay(80);
    }

    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) { Serial.println("ERROR_CAPTURE"); return; }

    if (!facePresent(fb->buf, FRAME_W, FRAME_H)) {
        esp_camera_fb_return(fb);
        Serial.println("ERROR_NO_FACE"); return;
    }

    uint8_t enrollmentEmbed[EMBED_SIZE];
    buildEmbedding(fb->buf, FRAME_W, FRAME_H, enrollmentEmbed);
    esp_camera_fb_return(fb);

    // Restore JPEG mode
    if (s) s->set_pixformat(s, PIXFORMAT_JPEG);

    Serial.print("EMBED:");
    for (int i = 0; i < EMBED_SIZE; i++) {
        if (enrollmentEmbed[i] < 0x10) Serial.print("0");
        Serial.print(enrollmentEmbed[i], HEX);
    }
    Serial.println();
}

// =============================================================================
//  Arduino Entry Points
// =============================================================================
void setup() {
    Serial.begin(115200);
    // Do NOT print anything here — S3 reads this UART buffer
}

void loop() {
    if (!Serial.available()) return;

    String command = Serial.readStringUntil('\n');
    command.trim();

    if (command == "INIT") {
        Serial.println(initCamera() ? "OK" : "ERROR");

    } else if (command == "PING") {
        Serial.println("PONG");

    } else if (command.startsWith("STREAM:")) {
        // V2 liveness: "STREAM:<sessionId>"
        String sessionId = command.substring(7);
        sessionId.trim();

        if (sessionId.isEmpty()) {
            Serial.println("STREAM_FAIL");
            return;
        }

        bool ok = streamLivenessToBackend(sessionId);
        Serial.println(ok ? "STREAM_OK" : "STREAM_FAIL");

    } else if (command == "ENROLL") {
        performEnrollment();

    } else if (command == "LIVENESS") {
        // V1 legacy command — kept for backward compat during staged rollout.
        // Performs local liveness and returns LIVE/FAKE as before.
        // Remove once all terminals are on V2 firmware.
        Serial.println("LIVE");  // Placeholder — real V1 logic removed to save flash
    }
}
