#include "pn5180.h"
#include <Arduino.h>
#include <SPI.h>
#include <PN5180.h>
#include <PN5180ISO14443.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "pin_defs.h"

PN5180ISO14443 nfc(PN5180_NSS, PN5180_BUSY, PN5180_RST);
#define PN5180_RX_IRQ_STAT (1<<0)
static const char* TAG = "PN5180_WRAPPER";
static uint8_t iso_dep_block_num = 0;

extern "C" {
    void wrapper_pn5180_init(void) {
        initArduino();

        // Safely link the Arduino environment to the running ESP-IDF bus
        SPI.begin(SPI_CLK, SPI_MISO, SPI_MOSI, -1);

        nfc.begin();
        nfc.reset();

        ESP_LOGI(TAG, "PN5180 Initialized on Shared SPI Bus!");
    }
// ...
    void wrapper_reset_rf(void) {
            nfc.setupRF(); // Configures the RF registers
            nfc.setRF_on(); // Explicitly commands the transmitter to fire
        }

    bool wrapper_pn5180_read_card(uint8_t *uid_out, uint8_t *uid_len) {
        uint8_t uid[10];
        uint8_t length = nfc.activateTypeA(uid, 0);
        if (length > 0) {
            *uid_len = length;
            memcpy(uid_out, uid, length);
            return true;
        }
        return false;
    }

    bool wrapper_iso14443_rats(void) {
        uint8_t rats_cmd[] = { 0xE0, 0x80 };
        nfc.clearIRQStatus(0xFFFFFFFF);
        if (!nfc.sendData(rats_cmd, 2, 0)) return false;

        uint32_t start = millis();
        while (!(nfc.getIRQStatus() & PN5180_RX_IRQ_STAT)) {
            if (millis() - start > 1500) return false;
            vTaskDelay(pdMS_TO_TICKS(5));
        }

        uint32_t rx_status = 0;
        nfc.readRegister(0x13, &rx_status);
        uint16_t rx_len = rx_status & 0x1FF;
        if (rx_len == 0) return false;

        uint8_t *ats = nfc.readData(rx_len);
        if (!ats) return false;

        ESP_LOGI(TAG, "RATS Success! ATS Length: %u", rx_len);
        iso_dep_block_num = 0;
        vTaskDelay(pdMS_TO_TICKS(30));
        return true;
    }

    bool wrapper_send_apdu(uint8_t *cmd, uint16_t cmd_len, uint8_t *response, uint16_t *response_len) {
        uint16_t offset = 0;
        *response_len = 0;

        while (offset < cmd_len) {
            uint16_t remaining = cmd_len - offset;
            uint16_t chunk_size = (remaining > 240) ? 240 : remaining;
            bool is_last_chunk = (remaining <= 240);
            uint8_t tx_buffer[260];

            tx_buffer[0] = 0x02 | iso_dep_block_num | (is_last_chunk ? 0x00 : 0x10);
            memcpy(&tx_buffer[1], cmd + offset, chunk_size);
            uint16_t tx_len = chunk_size + 1;
            bool chunk_ack_received = false;

            while (!chunk_ack_received) {
                nfc.clearIRQStatus(0xFFFFFFFF);
                if (!nfc.sendData(tx_buffer, tx_len, 0)) return false;

                uint32_t start_time = millis();
                while (1) {
                    uint32_t irq = nfc.getIRQStatus();
                    if (irq & PN5180_RX_IRQ_STAT) break;
                    if (millis() - start_time > 3000) return false;
                    vTaskDelay(pdMS_TO_TICKS(5));
                }

                uint32_t rx_status = 0;
                nfc.readRegister(0x13, &rx_status);
                uint16_t rx_len = rx_status & 0x1FF;

                if (rx_len >= 1) {
                    uint8_t *rx_buf = nfc.readData(rx_len);
                    if (!rx_buf) return false;

                    if (rx_buf[0] == 0xF2) {
                        tx_buffer[0] = 0xF2;
                        tx_buffer[1] = (rx_len > 1) ? rx_buf[1] : 0x01;
                        tx_len = 2;
                        continue;
                    }
                    if ((rx_buf[0] & 0xE6) == 0xA2) {
                        uint8_t ack_block_num = rx_buf[0] & 0x01;
                        if (ack_block_num == iso_dep_block_num) {
                            iso_dep_block_num ^= 1;
                            if (!is_last_chunk) {
                                offset += chunk_size;
                                chunk_ack_received = true;
                                break;
                            }
                        } else {
                            return false;
                        }
                    }
                    if ((rx_buf[0] & 0xE2) == 0x02) {
                        iso_dep_block_num ^= 1;
                        memcpy(response + *response_len, &rx_buf[1], rx_len - 1);
                        *response_len += (rx_len - 1);

                        if ((rx_buf[0] & 0x10) != 0) {
                            tx_buffer[0] = 0xA2 | iso_dep_block_num;
                            tx_len = 1;
                            continue;
                        } else {
                            return true;
                        }
                    }
                } else {
                    return false;
                }
            }
        }
        return false;
    }
}
