#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * camera_uart — UART bridge to the ESP32-CAM liveness module.
 *
 * Protocol (ESP32-S3 master → ESP32-CAM slave):
 *   TX "PING\n"           → RX "PONG\n"
 *   TX "INIT\n"           → RX "OK\n" | "ERROR\n"
 *   TX "ENROLL\n"         → RX "EMBED:<64-hex>\n" | "ERROR_NO_FACE\n"
 *   TX "STREAM:<sid>\n"   → RX "STREAM_OK\n" | "STREAM_FAIL\n"  (V2 single-frame)
 *   TX "BURST:<sid>\n"    → RX "STREAM_OK\n" | "STREAM_FAIL\n"  (V3 5-frame burst)
 *   TX "ACTIVE:<sid>\n"   → RX "STREAM_OK\n" | "STREAM_FAIL\n"  (MediaPipe WSS challenge)
 *
 * cam_perform_liveness() routes automatically based on g_electionCfg.livenessMode:
 *   "ACTIVE"  → sends ACTIVE:<sid>  (MediaPipe challenge-response via wss://)
 *   "PASSIVE" → sends BURST:<sid>   (MiniFASNet 5-frame REST burst)  [default]
 */

void cam_init(int rx_pin, int tx_pin);
bool cam_send_ping(void);
bool cam_send_init(void);

/**
 * cam_perform_liveness — run liveness check in the mode configured by the backend.
 *
 * Reads g_electionCfg.livenessMode at call time:
 *   "ACTIVE"  → ACTIVE:<session_id>  (35 s timeout — challenge round-trip)
 *   otherwise → BURST:<session_id>   (20 s timeout — 5-frame MiniFASNet)
 *
 * Returns true if CAM responds STREAM_OK within the mode's timeout.
 */
bool cam_perform_liveness(const char *session_id);

/**
 * cam_get_enrollment_embedding — capture face, return hex embedding.
 * Sends ENROLL, parses "EMBED:<hex>" response.
 * out_hex must be at least 65 bytes (64 hex chars + null).
 * Returns true on success.
 */
bool cam_get_enrollment_embedding(char *out_hex, size_t out_hex_len);
bool cam_send_reference_embedding(const uint8_t *embedding, uint16_t len);

#ifdef __cplusplus
}
#endif
