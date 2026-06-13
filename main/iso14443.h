/**
 * @file iso14443.h
 * @brief ISO 14443-A card activation and ISO-DEP (T=CL) APDU transport.
 *
 * Activation sequence for JCOP4:
 *   WUPA (7-bit) → ATQA → Anti-collision CL1 (→ CL2 if 7-byte UID)
 *   → SELECT → RATS → ATS → [PPS optional]
 *
 * ISO-DEP I-block framing (ISO/IEC 14443-4:2016):
 *   PCB: bits[7:6]=00 (I-block), bit[5]=chaining, bit[4]=CID, bit[3]=NAD,
 *        bit[2]=1, bit[1]=0, bit[0]=BN (block number, toggles each pair)
 *
 *   I-block no-chain BN=0: 0x02
 *   I-block no-chain BN=1: 0x03
 *   I-block chain    BN=0: 0x22
 *   I-block chain    BN=1: 0x23
 *   R(ACK)           BN=0: 0xA2
 *   R(ACK)           BN=1: 0xA3
 *   S(DESELECT) req:       0xC2
 *   S(DESELECT) resp:      0xC0  (card echoes PCB)
 *
 * Chaining: large APDUs are automatically split into FSD-3-byte chunks.
 *   After each chained block we wait for R(ACK). After the last block we
 *   wait for the card's response I-block chain and reassemble.
 *
 * Extended APDUs: the APDU layer sends raw bytes; this layer handles
 *   framing only. Extended Lc (3-byte) is built by the caller.
 */
#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Cascade tag (presence means the UID has more cascade levels) */
#define ISO14443_CASCADE_TAG               0x88u

/* ── RATS command byte ───────────────────────────────────────── */
#define ISO14443_RATS_CMD                  0xE0u

/* ── FSDI → FSD mapping (ISO 14443-4 Table 3) ───────────────── */
/* FSDI=8 gives FSD=256, which we request in RATS. */
#define ISO14443_FSDI_256                  0x08u   /**< Frame Size Device 256 bytes */
#define ISO14443_FSD_256                   256u

/* ── Max sizes ───────────────────────────────────────────────── */
#define ISO14443_MAX_UID_BYTES             10u     /**< up to 10 bytes (triple CL) */
#define ISO14443_MAX_ATS_BYTES             32u
#define ISO14443_MAX_APDU_BYTES            32768u  /**< JCOP4 supports 32 KB       */
#define ISO14443_MAX_FRAME_BYTES           256u    /**< max frame after RATS       */

/* ── Timeouts ────────────────────────────────────────────────── */
#define ISO14443_WUPA_TIMEOUT_MS           10u
#define ISO14443_ACTIVATION_TIMEOUT_MS     50u
#define ISO14443_APDU_TIMEOUT_MS           5000u   /**< 5 s for card processing    */

/* ── ISO-DEP error codes ──────────────────────────────────────── */
#define ISO14443_ERR_BASE                  0x5200
#define ISO14443_ERR_NO_CARD               (esp_err_t)(ISO14443_ERR_BASE | 0x01)
#define ISO14443_ERR_COLLISION             (esp_err_t)(ISO14443_ERR_BASE | 0x02)
#define ISO14443_ERR_NOT_ISO_DEP           (esp_err_t)(ISO14443_ERR_BASE | 0x03)
#define ISO14443_ERR_PROTOCOL              (esp_err_t)(ISO14443_ERR_BASE | 0x04)
#define ISO14443_ERR_OVERFLOW              (esp_err_t)(ISO14443_ERR_BASE | 0x05)
#define ISO14443_ERR_TIMEOUT               (esp_err_t)(ISO14443_ERR_BASE | 0x06)
#define ISO14443_ERR_WTX                   (esp_err_t)(ISO14443_ERR_BASE | 0x07)

/* ── Card info returned after activation ─────────────────────── */
typedef struct {
    uint8_t  uid[ISO14443_MAX_UID_BYTES];
    uint8_t  uid_len;           /**< 4, 7, or 10 bytes                       */
    uint8_t  sak;               /**< SELECT Acknowledge byte                 */
    bool     iso_dep_support;   /**< SAK bit5 set → card supports ISO 14443-4 */
    uint8_t  ats[ISO14443_MAX_ATS_BYTES];
    uint8_t  ats_len;
    uint8_t  fsci;              /**< Frame Size Card Integer (from ATS T0)   */
    uint16_t fsc;               /**< Frame Size Card in bytes                */
    uint8_t  fwi;               /**< Frame Wait time Integer (from ATS TB1) */
    uint32_t fwt_us;            /**< Frame Wait Time in microseconds         */
} iso14443_card_t;

/* ── Public API ──────────────────────────────────────────────── */

/**
 * Detect and activate a card in the field.
 *
 * Performs WUPA → anti-collision (CL1 [CL2]) → SELECT → RATS.
 * On success, @p card is populated with UID, SAK, ATS, and frame size info.
 *
 * @param card   [out] Populated with card activation data.
 * @return ESP_OK, ISO14443_ERR_NO_CARD if no card found, or another error.
 */
esp_err_t iso14443_activate(iso14443_card_t *card);

/**
 * APDU transceive over ISO-DEP.
 *
 * Sends @p apdu_cmd and receives the card response into @p apdu_resp.
 * Handles I-block chaining transparently for both TX and RX.
 * Handles S(WTX) — waits for card and sends WTX response.
 *
 * @param card       Card activated by iso14443_activate().
 * @param apdu_cmd   Full APDU command bytes (CLA INS P1 P2 Lc DATA Le ...).
 * @param cmd_len    Length of apdu_cmd.
 * @param apdu_resp  Buffer for the full APDU response (including SW1 SW2).
 * @param resp_size  Capacity of apdu_resp.
 * @param resp_len   [out] Bytes in apdu_resp.
 * @param timeout_ms Maximum wait for each card response block.
 * @return ESP_OK or an error code.
 */
esp_err_t iso14443_apdu_transceive(const iso14443_card_t *card,
                                    const uint8_t *apdu_cmd, uint16_t cmd_len,
                                    uint8_t *apdu_resp, uint16_t resp_size,
                                    uint16_t *resp_len,
                                    uint32_t timeout_ms);

/**
 * Graceful deselection — sends S(DESELECT) and waits for card response.
 * Call before removing card or powering down RF.
 */
esp_err_t iso14443_deselect(void);

/**
 * Map FSCI value to Frame Size in bytes (ISO 14443-4 Table 3).
 * Valid FSCI: 0..8 → 16..256 bytes. 9..15 are RFU (clamped to 256).
 */
uint16_t iso14443_fsci_to_fsd(uint8_t fsci);

/**
 * Extract SW1 SW2 status word from a raw APDU response buffer.
 * @param resp     APDU response buffer.
 * @param resp_len Total bytes in resp.
 * @return 16-bit status word (SW1 << 8 | SW2), or 0 if resp_len < 2.
 */
uint16_t iso14443_get_sw(const uint8_t *resp, uint16_t resp_len);

#ifdef __cplusplus
}
#endif
