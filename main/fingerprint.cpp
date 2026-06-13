#include "fingerprint.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>

static const char *TAG = "FP";
#define FP_UART    UART_NUM_1
#define FP_BAUD    57600
#define FP_BUF_SZ  1024

// Fixed packet header (R307 default address 0xFFFFFFFF)
static const uint8_t FP_HEADER[] = {0xEF, 0x01, 0xFF, 0xFF, 0xFF, 0xFF};

static void fp_send(const uint8_t *pkt, size_t len) {
    uart_write_bytes(FP_UART, (const char *)pkt, len);
}

static int fp_recv(uint8_t *buf, int max_len, int timeout_ms) {
    return uart_read_bytes(FP_UART, buf, max_len, pdMS_TO_TICKS(timeout_ms));
}

void fp_init(int rx_pin, int tx_pin) {
    uart_config_t cfg = {};
    cfg.baud_rate  = FP_BAUD;
    cfg.data_bits  = UART_DATA_8_BITS;
    cfg.parity     = UART_PARITY_DISABLE;
    cfg.stop_bits  = UART_STOP_BITS_1;
    cfg.flow_ctrl  = UART_HW_FLOWCTRL_DISABLE;
    cfg.source_clk = UART_SCLK_DEFAULT;

    uart_driver_install(FP_UART, FP_BUF_SZ, FP_BUF_SZ, 0, NULL, 0);
    uart_param_config(FP_UART, &cfg);
    uart_set_pin(FP_UART, tx_pin, rx_pin, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE);
    ESP_LOGI(TAG, "R307 UART1 init done");
}

// VerifyPassword — packet 0xEF01...01|0003|13|00|00|00|00|checksum
bool fp_verify_sensor(void) {
    static const uint8_t pkt[] = {
        0xEF,0x01,0xFF,0xFF,0xFF,0xFF,
        0x01, 0x00, 0x07, 0x13, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x1B
    };
    uart_flush(FP_UART);
    fp_send(pkt, sizeof(pkt));
    uint8_t resp[16];
    int n = fp_recv(resp, sizeof(resp), 500);
    if (n >= 10 && resp[0] == 0xEF && resp[9] == 0x00) return true;
    ESP_LOGW(TAG, "VerifyPassword failed, n=%d", n);
    return false;
}

// GenImg (capture) — INS 0x01
bool fp_capture_image(void) {
    static const uint8_t pkt[] = {
        0xEF,0x01,0xFF,0xFF,0xFF,0xFF,
        0x01, 0x00, 0x03, 0x01, 0x00, 0x05
    };
    uart_flush(FP_UART);
    fp_send(pkt, sizeof(pkt));
    uint8_t resp[16];
    int n = fp_recv(resp, sizeof(resp), 1000);
    if (n >= 10 && resp[9] == 0x00) return true;
    ESP_LOGW(TAG, "GenImg fail conf=%02X", (n >= 10) ? resp[9] : 0xFF);
    return false;
}

// Img2Tz — slot 1 — INS 0x02
bool fp_generate_template(void) {
    static const uint8_t pkt[] = {
        0xEF,0x01,0xFF,0xFF,0xFF,0xFF,
        0x01, 0x00, 0x04, 0x02, 0x01, 0x00, 0x08
    };
    uart_flush(FP_UART);
    fp_send(pkt, sizeof(pkt));
    uint8_t resp[16];
    int n = fp_recv(resp, sizeof(resp), 1000);
    if (n >= 10 && resp[9] == 0x00) return true;
    ESP_LOGW(TAG, "Img2Tz fail conf=%02X", (n >= 10) ? resp[9] : 0xFF);
    return false;
}

// UpChar — slot 1 — INS 0x08 — upload template to host
uint16_t fp_get_template(uint8_t *buf_out) {
    static const uint8_t pkt[] = {
        0xEF,0x01,0xFF,0xFF,0xFF,0xFF,
        0x01, 0x00, 0x04, 0x08, 0x01, 0x00, 0x0E
    };
    uart_flush(FP_UART);
    fp_send(pkt, sizeof(pkt));
    vTaskDelay(pdMS_TO_TICKS(200));

    uint8_t raw[700];
    int total = 0, chunk;
    while ((chunk = fp_recv(raw + total, sizeof(raw) - total, 100)) > 0)
        total += chunk;

    /*
     * BUG-FIX: R307 UpChar response structure:
     *   Bytes  0-11 : ACK packet (12 bytes) — confirms UpChar was accepted
     *   Bytes 12-end: Data packets, each structured as:
     *     [EF][01][FF FF FF FF] = 6-byte start code + address
     *     [pkt_type]            = 1 byte  (0x02=data, 0x07=end-of-data)
     *     [len_hi][len_lo]      = 2 bytes (length of data + 2 checksum bytes)
     *     [data...]             = len-2 bytes of actual template data
     *     [chk_hi][chk_lo]      = 2 bytes checksum (ignored here)
     *     — total header per packet: 9 bytes (6 start + 1 type + 2 len)
     *
     * The old code did: memcpy(buf_out, raw + 12, total - 12)
     * This copied packet headers and checksums alongside template data,
     * producing a corrupted "template" that never matched on verification.
     *
     * Fix: walk each packet, extract only the data bytes.
     */
    uint16_t out_pos = 0;
    int pos = 12;   // skip the 12-byte ACK

    while (pos < total && out_pos < 512) {
        // Each data packet starts with 6-byte header (EF 01 FF FF FF FF)
        if (pos + 9 > total) break;
        if (raw[pos] != 0xEF || raw[pos + 1] != 0x01) {
            ESP_LOGW(TAG, "UpChar: bad packet start at pos=%d (%02X %02X)", pos, raw[pos], raw[pos+1]);
            break;
        }

        uint8_t  pkt_type  = raw[pos + 6];
        uint16_t len_field = ((uint16_t)raw[pos + 7] << 8) | raw[pos + 8];
        uint16_t data_bytes = (len_field > 2) ? len_field - 2 : 0;   // subtract 2 checksum bytes

        pos += 9;   // skip the 9-byte per-packet header

        if (pkt_type == 0x07) break;   // end-of-data packet — we're done

        if (pkt_type == 0x02) {
            uint16_t copy = data_bytes;
            if (out_pos + copy > 512) copy = 512 - out_pos;
            if (pos + (int)copy <= total) {
                memcpy(buf_out + out_pos, raw + pos, copy);
                out_pos += copy;
            }
        }

        pos += len_field;   // skip data + checksum (len_field includes checksum)
    }

    if (out_pos < 512) {
        ESP_LOGW(TAG, "UpChar: partial template — got %u/512 bytes (total raw=%d)", out_pos, total);
    } else {
        ESP_LOGI(TAG, "UpChar: template uploaded OK (512 bytes)");
    }
    return out_pos;
}
