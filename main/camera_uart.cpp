/**
 * camera_uart.cpp — ESP32-S3 ↔ ESP32-CAM UART bridge
 * =====================================================
 * Routes liveness commands based on g_electionCfg.livenessMode:
 *   "ACTIVE"  → "ACTIVE:<sid>"  MediaPipe challenge-response (wss://)
 *   "PASSIVE" → "BURST:<sid>"   MiniFASNet 5-frame REST burst (default)
 *
 * This implements BUG-S3 fix: the S3 firmware previously always sent
 * "STREAM:<sid>" (V2 single-frame) regardless of configured liveness mode.
 * Now reads g_electionCfg.livenessMode (parsed from /api/terminal/config
 * by BUG-8 fix in network.cpp) and routes to the correct CAM command.
 */

#include "camera_uart.h"
#include "session.h"          // for g_electionCfg
#include "driver/uart.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

static const char *TAG      = "CAM-UART";
#define CAM_UART_NUM         UART_NUM_2
#define CAM_UART_BUF         1024
#define CMD_TIMEOUT_PASSIVE  20000   /* ms — 5-frame burst + MiniFASNet inference */
#define CMD_TIMEOUT_ACTIVE   35000   /* ms — challenge round-trip + MediaPipe    */
#define CMD_TIMEOUT_GENERIC  5000    /* ms — PING / INIT                         */

/* ── Init ────────────────────────────────────────────────────────────── */

void cam_init(int rx_pin, int tx_pin) {
    uart_config_t cfg = {};
    cfg.baud_rate  = 115200;
    cfg.data_bits  = UART_DATA_8_BITS;
    cfg.parity     = UART_PARITY_DISABLE;
    cfg.stop_bits  = UART_STOP_BITS_1;
    cfg.flow_ctrl  = UART_HW_FLOWCTRL_DISABLE;
    cfg.source_clk = UART_SCLK_DEFAULT;

    uart_driver_install(CAM_UART_NUM, CAM_UART_BUF, CAM_UART_BUF, 0, NULL, 0);
    uart_param_config(CAM_UART_NUM, &cfg);
    uart_set_pin(CAM_UART_NUM, tx_pin, rx_pin,
                 UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    ESP_LOGI(TAG, "CAM UART2 init: rx=%d tx=%d", rx_pin, tx_pin);
}

extern void ui_display_active_challenge(const char *action);

/* ── Helpers ─────────────────────────────────────────────────────────── */

static void cam_flush(void) {
    uart_flush(CAM_UART_NUM);
}

static void cam_send_line(const char *line) {
    uart_write_bytes(CAM_UART_NUM, line, strlen(line));
    uart_write_bytes(CAM_UART_NUM, "\n", 1);
}

/**
 * cam_read_line — block until a '\n'-terminated line arrives or timeout.
 * Strips trailing '\r' and '\n'.  Returns true if any data received.
 */
static bool cam_read_line(char *buf, size_t buf_len, uint32_t timeout_ms) {
    size_t pos = 0;
    uint32_t elapsed = 0;
    const uint32_t POLL_MS = 20;

    while (elapsed < timeout_ms && pos < buf_len - 1) {
        uint8_t ch;
        int n = uart_read_bytes(CAM_UART_NUM, &ch, 1, pdMS_TO_TICKS(POLL_MS));
        if (n > 0) {
            if (ch == '\n') break;
            if (ch != '\r') buf[pos++] = (char)ch;
        }
        elapsed += POLL_MS;
    }
    buf[pos] = '\0';
    return pos > 0;
}

/* ── Public API ──────────────────────────────────────────────────────── */

bool cam_send_ping(void) {
    cam_flush();
    cam_send_line("PING");
    char resp[16] = {};
    bool ok = cam_read_line(resp, sizeof(resp), CMD_TIMEOUT_GENERIC);
    bool pass = ok && strcmp(resp, "PONG") == 0;
    if (!pass) ESP_LOGW(TAG, "PING failed (got: '%s')", resp);
    return pass;
}

bool cam_send_init(void) {
    cam_flush();
    cam_send_line("INIT");
    char resp[16] = {};
    bool ok = cam_read_line(resp, sizeof(resp), CMD_TIMEOUT_GENERIC);
    bool pass = ok && strcmp(resp, "OK") == 0;
    if (!pass) ESP_LOGW(TAG, "INIT failed (got: '%s')", resp);
    return pass;
}

/**
 * cam_perform_liveness — route to BURST or ACTIVE based on livenessMode.
 *
 * S3 liveness mode switch (BUG-S3 fix):
 *   The S3 reads g_electionCfg.livenessMode, which was parsed from
 *   /api/terminal/config in net_fetch_election_config() (BUG-8 fix).
 *   The admin sets it via PUT /api/camera/liveness-mode in SettingsView.
 *
 * CAM firmware (active_liveness.c) handles both commands:
 *   BURST:<sid>  → 5-frame MiniFASNet burst over HTTPS REST
 *   ACTIVE:<sid> → MediaPipe challenge-response over WSS WebSocket
 */
bool cam_perform_liveness(const char *session_id) {
    if (!session_id || strlen(session_id) == 0) {
        ESP_LOGE(TAG, "cam_perform_liveness: null/empty session_id");
        return false;
    }

    bool active_mode = (g_electionCfg.livenessMode == "ACTIVE");

    char cmd[96];
    uint32_t timeout_ms;

    if (active_mode) {
        snprintf(cmd, sizeof(cmd), "ACTIVE:%s", session_id);
        timeout_ms = CMD_TIMEOUT_ACTIVE;
        ESP_LOGI(TAG, "Liveness → ACTIVE (MediaPipe challenge, timeout=%lus)",
                 timeout_ms / 1000);
    } else {
        snprintf(cmd, sizeof(cmd), "BURST:%s", session_id);
        timeout_ms = CMD_TIMEOUT_PASSIVE;
        ESP_LOGI(TAG, "Liveness → PASSIVE/BURST (MiniFASNet, timeout=%lus)",
                 timeout_ms / 1000);
    }

    cam_flush();
    cam_send_line(cmd);

    /* ── OPTION B: Two-phase read loop ──────────────────────────────────
     *
     * PASSIVE mode: one-shot — wait for STREAM_OK or STREAM_FAIL.
     *
     * ACTIVE mode: two-phase —
     *   Phase 1: CAM sends "CHALLENGE:<action>" after receiving the
     *            challenge from the backend WebSocket. Extract the action
     *            and display it on the TFT so the voter knows what to do.
     *            CAM then waits 3s before starting to stream.
     *   Phase 2: CAM sends "STREAM_OK" or "STREAM_FAIL" after the
     *            MediaPipe challenge evaluation completes or times out.
     *
     * IMPORTANT: resp buffer must be large enough for the longest possible
     * CHALLENGE line: "CHALLENGE:TURN_HEAD_RIGHT" = 25 chars + null = 26.
     * Use 64 bytes to be safe.
     */
    char resp[64] = {};
    TickType_t deadline = xTaskGetTickCount() + pdMS_TO_TICKS(timeout_ms);
    bool challenge_displayed = false;
    bool result = false;

    while (xTaskGetTickCount() < deadline) {
        uint32_t remaining_ms = (deadline - xTaskGetTickCount()) * portTICK_PERIOD_MS;
        if (remaining_ms == 0) break;

        /* Poll with 500ms slices so we don't spin-burn the CPU */
        bool got = cam_read_line(resp, sizeof(resp),
                                 remaining_ms < 500 ? remaining_ms : 500);
        if (!got) continue;

        ESP_LOGI(TAG, "CAM → S3: '%s'", resp);

        /* ── Phase 1: Challenge relay ─────────────────────────── */
        if (active_mode && !challenge_displayed &&
            strncmp(resp, "CHALLENGE:", 10) == 0)
        {
            const char *action = resp + 10;   /* e.g. "BLINK", "SMILE" */
            ESP_LOGI(TAG, "Challenge received: %s — displaying on TFT", action);
            ui_display_active_challenge(action);
            challenge_displayed = true;
            /* Clear buffer and wait for Phase 2 */
            memset(resp, 0, sizeof(resp));
            continue;
        }

        /* ── Phase 2: Final result ────────────────────────────── */
        if (strcmp(resp, "STREAM_OK") == 0) {
            result = true;
            break;
        }
        if (strcmp(resp, "STREAM_FAIL") == 0) {
            result = false;
            break;
        }

        /* Unexpected line (debug print from CAM, etc.) — ignore and keep waiting */
        memset(resp, 0, sizeof(resp));
    }

    if (xTaskGetTickCount() >= deadline) {
        ESP_LOGW(TAG, "Liveness: timed out after %lu ms (mode=%s)",
                 timeout_ms, active_mode ? "ACTIVE" : "PASSIVE");
        result = false;
    }

    ESP_LOGI(TAG, "Liveness result: %s (mode=%s, challenge_displayed=%d)",
             result ? "PASS" : "FAIL",
             active_mode ? "ACTIVE" : "PASSIVE",
             (int)challenge_displayed);
    return result;
}

/**
 * cam_get_enrollment_embedding — capture face, decode hex → 64 binary bytes.
 *
 * Sends "ENROLL", receives "EMBED:<128-hex>" (EMBED_SIZE = 8×8 = 64 bytes).
 * Decodes the 128 hex chars into 64 raw binary bytes before writing to
 * out_bytes.  enrollment.cpp casts the output to uint8_t* and passes it
 * directly to sc_store_liveness_embedding — it MUST receive binary bytes,
 * not ASCII hex chars.
 *
 * out_bytes : caller buffer, must be >= 64 bytes.
 *             enrollment.cpp passes 256 — that is fine, only 64 are written.
 *
 * Fix: old version wrote raw hex string to the buffer instead of decoding
 * it.  The card would have received ASCII digits instead of the embedding.
 */
bool cam_get_enrollment_embedding(char *out_bytes, size_t out_len) {
    if (!out_bytes || out_len < 64) {
        ESP_LOGE(TAG, "cam_get_enrollment_embedding: buffer must be >= 64 bytes");
        return false;
    }

    cam_flush();
    cam_send_line("ENROLL");

    char resp[140] = {};   /* "EMBED:" (6) + 128 hex chars + null */
    bool ok = cam_read_line(resp, sizeof(resp), 8000);

    if (!ok) {
        ESP_LOGW(TAG, "ENROLL: no response from CAM");
        return false;
    }
    if (strncmp(resp, "EMBED:", 6) != 0) {
        ESP_LOGW(TAG, "ENROLL failed — CAM replied: '%s'", resp);
        return false;
    }

    const char *hex = resp + 6;
    size_t hex_len  = strlen(hex);
    if (hex_len != 128) {
        ESP_LOGE(TAG, "ENROLL: expected 128 hex chars (64 bytes), got %d", (int)hex_len);
        return false;
    }

    /* Decode 128 hex chars → 64 raw binary bytes */
    for (int i = 0; i < 64; i++) {
        unsigned int bval = 0;
        sscanf(&hex[i * 2], "%02x", &bval);
        out_bytes[i] = (char)(uint8_t)bval;
    }

    ESP_LOGI(TAG, "Enrollment embedding: 64 bytes decoded from CAM");
    return true;
}

/**
 * cam_send_reference_embedding — sends the smart card face embedding to the camera
 * Converts the 256-byte binary embedding into a hex string and sends it via UART.
 */
bool cam_send_reference_embedding(const uint8_t *embedding, uint16_t len) {
    if (!embedding || len == 0) {
        ESP_LOGE(TAG, "Invalid reference embedding");
        return false;
    }

    ESP_LOGI(TAG, "Sending reference embedding to Camera (%d bytes)...", len);

    // Allocate memory for "REF:" + 512 hex chars (for a 256-byte embedding) + null terminator
    char *cmd = (char *)malloc(len * 2 + 10);
    if (!cmd) {
        ESP_LOGE(TAG, "Failed to allocate memory for hex conversion");
        return false;
    }

    strcpy(cmd, "REF:");
    int pos = 4;
    for (uint16_t i = 0; i < len; i++) {
        sprintf(&cmd[pos], "%02X", embedding[i]);
        pos += 2;
    }

    cam_flush();
    cam_send_line(cmd);
    free(cmd); // Free the memory so we don't cause a leak!

    // Wait for the camera to acknowledge
    char resp[32] = {};
    if (cam_read_line(resp, sizeof(resp), 2000)) {
        ESP_LOGI(TAG, "CAM replied to REF: %s", resp);
        return (strcmp(resp, "OK") == 0 || strcmp(resp, "REF_OK") == 0);
    }

    // If the camera firmware hasn't been updated to reply to "REF:" yet,
    // we don't want to crash the whole voting flow. Warn, but proceed.
    ESP_LOGW(TAG, "No response to REF embedding, proceeding anyway...");
    return true;
}
