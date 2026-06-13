/**
 * @file iso14443.c
 * @brief ISO 14443-A activation and ISO-DEP block exchange with chaining.
 *
 * This file assumes pn5180_transceive() is the exclusive RF channel.
 * All PN5180 state (CRC mode, transceive state) is set per-transaction.
 */
#include "iso14443.h"
#include "pn5180.h"

#include <string.h>
#include <inttypes.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "ISO14443";

/* ─── ISO-DEP PCB byte constants ─────────────────────────────── */
/* I-block: bits[7:6]=00, bit[5]=chain, bit[2]=1, bit[0]=BN      */
#define PCB_IBLOCK(chain, bn)   (uint8_t)(0x02u | ((chain) ? 0x20u : 0x00u) | ((bn) & 0x01u))
/* R(ACK): 1010 | 0010 | bn                                       */
#define PCB_RACK(bn)            (uint8_t)(0xA2u | ((bn) & 0x01u))
/* R(NAK): 1011 | 0010 | bn                                       */
#define PCB_RNAK(bn)            (uint8_t)(0xB2u | ((bn) & 0x01u))
/* S(DESELECT) request                                             */
#define PCB_SDESELECT_REQ       0xC2u

/* Identify block type from PCB */
#define IS_IBLOCK(pcb)          (((pcb) & 0xE2u) == 0x02u)
#define IS_RACK(pcb)            (((pcb) & 0xF2u) == 0xA2u)
#define IS_RNAK(pcb)            (((pcb) & 0xF2u) == 0xB2u)
#define IS_SWTX(pcb)            (((pcb) & 0xF7u) == 0xF2u)  /* S(WTX) from card */
#define IS_SDESELECT(pcb)       (((pcb) & 0xF7u) == 0xC2u)
#define IBLOCK_CHAIN_BIT(pcb)   (((pcb) >> 5) & 0x01u)
#define IBLOCK_BN(pcb)          ((pcb) & 0x01u)

/* Per-session ISO-DEP state */
static uint8_t  s_block_num = 0;   /* toggles between 0 and 1 each I-block pair */
static uint16_t s_max_frame = 253; /* max I-block data (FSD - 3 bytes overhead)  */

/* ──────────────────────────────────────────────────────────────
 * Utility
 * ────────────────────────────────────────────────────────────── */

uint16_t iso14443_fsci_to_fsd(uint8_t fsci)
{
    static const uint16_t fsd_table[] = { 16, 24, 32, 40, 48, 64, 96, 128, 256 };
    if (fsci > 8) fsci = 8;
    return fsd_table[fsci];
}

uint16_t iso14443_get_sw(const uint8_t *resp, uint16_t resp_len)
{
    if (resp_len < 2) return 0x0000;
    return (uint16_t)((resp[resp_len - 2] << 8) | resp[resp_len - 1]);
}

/* ──────────────────────────────────────────────────────────────
 * Activation helpers
 * ────────────────────────────────────────────────────────────── */

/** Send WUPA (0x52, 7-bit short frame, no CRC) and receive ATQA. */
static esp_err_t do_wupa(uint8_t atqa[2])
{
    const uint8_t wupa = 0x52;
    uint16_t rx_len;
    esp_err_t ret = pn5180_transceive(&wupa, 1,
                                       7,     /* 7 valid bits */
                                       false, /* no CRC */
                                       atqa, 2, &rx_len,
                                       ISO14443_WUPA_TIMEOUT_MS);
    if (ret != ESP_OK) return ret;
    if (rx_len != 2) {
        ESP_LOGE(TAG, "ATQA: expected 2 bytes, got %u", rx_len);
        return ISO14443_ERR_PROTOCOL;
    }
    ESP_LOGD(TAG, "ATQA: %02X %02X", atqa[0], atqa[1]);
    return ESP_OK;
}

/**
 * Anti-collision at cascade level @p cl (0x93 CL1, 0x95 CL2, 0x97 CL3).
 * Sends the ALL-00 NVB (0x20) to request full UID CLn without known bits.
 * Returns the 5-byte collision loop result: uid_cl[4] + BCC.
 */
static esp_err_t do_anticollision(uint8_t cl_cmd, uint8_t uid_cl[5])
{
    uint8_t cmd[2] = { cl_cmd, 0x20 }; /* NVB=0x20: 2 bytes of cmd, 0 known UID bits */
    uint16_t rx_len;
    esp_err_t ret = pn5180_transceive(cmd, 2,
                                       0,     /* full bytes */
                                       false, /* no CRC for anti-collision */
                                       uid_cl, 5, &rx_len,
                                       ISO14443_ACTIVATION_TIMEOUT_MS);
    if (ret != ESP_OK) return ret;
    if (rx_len != 5) {
        ESP_LOGE(TAG, "Anti-collision CL 0x%02X: expected 5, got %u", cl_cmd, rx_len);
        return ISO14443_ERR_PROTOCOL;
    }
    return ESP_OK;
}

/**
 * SELECT at cascade level @p cl_cmd with the 5-byte UID data (uid_data[4] + BCC).
 * Hardware appends CRC. Returns SAK (1 byte).
 */
static esp_err_t do_select(uint8_t cl_cmd, const uint8_t uid_data[5], uint8_t *sak)
{
    /* SELECT frame: [cl_cmd][NVB=0x70][uid_data[0..3]][BCC] → hardware adds CRC */
    uint8_t cmd[7] = { cl_cmd, 0x70,
                        uid_data[0], uid_data[1], uid_data[2], uid_data[3],
                        uid_data[4] /* BCC */ };
    uint16_t rx_len;
    uint8_t rx[3]; /* SAK + CRC (stripped by HW) → we get 1 byte */
    esp_err_t ret = pn5180_transceive(cmd, 7,
                                       0,    /* full bytes */
                                       true, /* CRC enabled */
                                       rx, sizeof(rx), &rx_len,
                                       ISO14443_ACTIVATION_TIMEOUT_MS);
    if (ret != ESP_OK) return ret;
    if (rx_len < 1) {
        ESP_LOGE(TAG, "SELECT CL 0x%02X: no SAK", cl_cmd);
        return ISO14443_ERR_PROTOCOL;
    }
    *sak = rx[0];
    ESP_LOGD(TAG, "SAK: 0x%02X", *sak);
    return ESP_OK;
}

/**
 * RATS — Request for Answer To Select.
 * Sends E0 [FSDI=8 | CID=0] with CRC. Returns ATS bytes.
 */
static esp_err_t do_rats(uint8_t *ats, uint8_t ats_buf_size, uint8_t *ats_len)
{
    /* E0 [FSDI|CID]: FSDI=8 → FSD=256; CID=0 */
    uint8_t cmd[2] = { ISO14443_RATS_CMD,
                        (uint8_t)(ISO14443_FSDI_256 << 4) | 0x00 };
    uint16_t rx_len;
    esp_err_t ret = pn5180_transceive(cmd, 2,
                                       0, true,
                                       ats, ats_buf_size, &rx_len,
                                       ISO14443_ACTIVATION_TIMEOUT_MS);
    if (ret != ESP_OK) return ret;
    if (rx_len < 1) {
        ESP_LOGE(TAG, "RATS: empty ATS response");
        return ISO14443_ERR_PROTOCOL;
    }
    *ats_len = (uint8_t)rx_len;
    ESP_LOG_BUFFER_HEX_LEVEL(TAG, ats, rx_len, ESP_LOG_DEBUG);
    return ESP_OK;
}

/**
 * Parse the ATS to extract FSCI and FWI.
 * ATS format: TL [T0 [TA1 [TB1 [TC1]]]] [historical bytes]
 */
static void parse_ats(iso14443_card_t *card)
{
    const uint8_t *ats = card->ats;
    uint8_t        len = card->ats_len;

    /* Defaults if ATS parsing fails */
    card->fsci  = 2;   /* FSD=32 bytes — conservative */
    card->fsc   = 32;
    card->fwi   = 4;   /* FWT ~ 4.8 ms */
    card->fwt_us = 4800;

    if (len < 1) return;

    /* TL: length of ATS including TL byte itself */
    uint8_t tl = ats[0];
    if (tl < 1 || tl > len) return;

    if (tl < 2) return; /* No T0 */
    uint8_t t0 = ats[1];

    card->fsci = t0 & 0x0Fu;
    card->fsc  = iso14443_fsci_to_fsd(card->fsci);

    bool ta1_present = (t0 >> 4) & 0x04u; /* bit 6 of T0 */
    bool tb1_present = (t0 >> 4) & 0x02u; /* bit 5 of T0 */
    /* tc1_present: bit 4 of T0 */

    uint8_t idx = 2;
    if (ta1_present && idx < tl) {
        /* TA1: data rate — we stay at 106 kbps for now */
        idx++;
    }
    if (tb1_present && idx < tl) {
        uint8_t tb1 = ats[idx++];
        card->fwi = tb1 & 0x0Fu;
        /* FWT = 4096 × 2^FWI / 13.56 MHz ≈ 302 µs × 2^FWI */
        card->fwt_us = 302u * (1u << card->fwi);
        ESP_LOGD(TAG, "ATS TB1=0x%02X → FWI=%" PRIu32 " FWT=%" PRIu32 " µs",
                 tb1, (uint32_t)card->fwi, (uint32_t)card->fwt_us);
    }
    ESP_LOGI(TAG, "ATS: FSCI=%" PRIu32 " FSD=%" PRIu32 " FWI=%" PRIu32 " FWT=%" PRIu32 " µs",
             (uint32_t)card->fsci, (uint32_t)card->fsc, (uint32_t)card->fwi, (uint32_t)card->fwt_us);
}

/* ──────────────────────────────────────────────────────────────
 * Public: activate
 * ────────────────────────────────────────────────────────────── */

esp_err_t iso14443_activate(iso14443_card_t *card)
{
    if (!card) return ESP_ERR_INVALID_ARG;
    memset(card, 0, sizeof(*card));
    esp_err_t ret;

    /* WUPA */
    uint8_t atqa[2];
    ret = do_wupa(atqa);
    if (ret != ESP_OK) return ret;

    uint8_t uid_buf[10] = {0};
    uint8_t uid_len     = 0;

    /* CL1 */
    uint8_t uid_cl1[5];
    ret = do_anticollision(0x93, uid_cl1);
    if (ret != ESP_OK) return ret;

    bool cascade1 = (uid_cl1[0] == ISO14443_CASCADE_TAG);

    ret = do_select(0x93, uid_cl1, &card->sak);
    if (ret != ESP_OK) return ret;

    if (!cascade1) {
        /* 4-byte UID */
        memcpy(uid_buf, uid_cl1, 4);
        uid_len = 4;
    } else {
        /* First 3 bytes of UID come after the cascade tag */
        memcpy(uid_buf, uid_cl1 + 1, 3);
        uid_len = 3;

        /* CL2 */
        uint8_t uid_cl2[5];
        ret = do_anticollision(0x95, uid_cl2);
        if (ret != ESP_OK) return ret;

        bool cascade2 = (uid_cl2[0] == ISO14443_CASCADE_TAG);

        ret = do_select(0x95, uid_cl2, &card->sak);
        if (ret != ESP_OK) return ret;

        if (!cascade2) {
            /* 7-byte UID */
            memcpy(uid_buf + 3, uid_cl2, 4);
            uid_len = 7;
        } else {
            /* CL3 — 10-byte UID */
            memcpy(uid_buf + 3, uid_cl2 + 1, 3);

            uint8_t uid_cl3[5];
            ret = do_anticollision(0x97, uid_cl3);
            if (ret != ESP_OK) return ret;

            ret = do_select(0x97, uid_cl3, &card->sak);
            if (ret != ESP_OK) return ret;

            memcpy(uid_buf + 6, uid_cl3, 4);
            uid_len = 10;
        }
    }

    memcpy(card->uid, uid_buf, uid_len);
    card->uid_len = uid_len;

    ESP_LOGI(TAG, "UID (%u bytes): %02X:%02X:%02X:%02X%s",
             uid_len,
             uid_buf[0], uid_buf[1], uid_buf[2], uid_buf[3],
             uid_len > 4 ? ":..." : "");

    /* Check ISO-DEP capability: SAK bit5 (0x20) */
    card->iso_dep_support = (card->sak & 0x20u) != 0;
    if (!card->iso_dep_support) {
        ESP_LOGW(TAG, "SAK=0x%02X: card does NOT support ISO 14443-4", card->sak);
        return ISO14443_ERR_NOT_ISO_DEP;
    }

    /* RATS */
    ret = do_rats(card->ats, ISO14443_MAX_ATS_BYTES, &card->ats_len);
    if (ret != ESP_OK) return ret;

    parse_ats(card);

    /* Set per-session max frame size: min(our FSD=256, card FSC) - 3 overhead */
    uint16_t negotiated = (card->fsc < ISO14443_FSD_256) ? card->fsc : ISO14443_FSD_256;
    s_max_frame = (negotiated > 3) ? (negotiated - 3) : 1;
    s_block_num = 0;

    ESP_LOGI(TAG, "ISO-DEP ready. Max I-block data: %u bytes", s_max_frame);
    return ESP_OK;
}

/* ──────────────────────────────────────────────────────────────
 * ISO-DEP block exchange internals
 * ────────────────────────────────────────────────────────────── */

/**
 * Send one I-block and wait for one response block from the card.
 *
 * @param pcb          PCB byte (use PCB_IBLOCK macro).
 * @param payload      I-block data bytes (no PCB, no CRC).
 * @param payload_len  Byte count.
 * @param resp         Buffer for the raw response frame (PCB + data, no CRC).
 * @param resp_size    Capacity of resp.
 * @param resp_len     [out] Total bytes in resp.
 * @param timeout_ms   Card response deadline.
 */
static esp_err_t send_iblock(uint8_t pcb,
                              const uint8_t *payload, uint16_t payload_len,
                              uint8_t *resp, uint16_t resp_size,
                              uint16_t *resp_len,
                              uint32_t timeout_ms)
{
    if (payload_len + 1 > PN5180_MAX_TRANSFER_BYTES) {
        return ISO14443_ERR_OVERFLOW;
    }

    /* Build frame: [PCB][data...] — HW adds CRC */
    uint8_t frame[PN5180_MAX_TRANSFER_BYTES + 1];
    frame[0] = pcb;
    if (payload_len) memcpy(&frame[1], payload, payload_len);

    return pn5180_transceive(frame, (uint16_t)(1 + payload_len),
                              0, true,
                              resp, resp_size, resp_len,
                              timeout_ms);
}

/**
 * Handle S(WTX) — the card is asking for extra wait time.
 * We acknowledge with S(WTX) response and continue waiting.
 */
static esp_err_t handle_wtx(uint8_t wtx_pcb, uint8_t wtm,
                              uint8_t *resp, uint16_t resp_size,
                              uint16_t *resp_len,
                              uint32_t timeout_ms)
{
    /* WTX response: same PCB with power-bit cleared, same WTM byte */
    uint8_t wtx_resp_pcb = 0xF2u; /* S(WTX) response */
    uint8_t frame[2] = { wtx_resp_pcb, wtm };
    esp_err_t ret = pn5180_transceive(frame, 2, 0, true,
                                       resp, resp_size, resp_len,
                                       timeout_ms);
    (void)wtx_pcb;
    return ret;
}

/* ──────────────────────────────────────────────────────────────
 * Public: APDU transceive with full TX/RX chaining
 * ────────────────────────────────────────────────────────────── */

esp_err_t iso14443_apdu_transceive(const iso14443_card_t *card,
                                    const uint8_t *apdu_cmd, uint16_t cmd_len,
                                    uint8_t *apdu_resp, uint16_t resp_size,
                                    uint16_t *resp_len,
                                    uint32_t timeout_ms)
{
    if (!card || !apdu_cmd || !apdu_resp || !resp_len) {
        return ESP_ERR_INVALID_ARG;
    }
    *resp_len = 0;

    esp_err_t ret;
    uint16_t  sent = 0;        /* bytes of apdu_cmd sent so far */
    uint8_t   resp_frame[ISO14443_MAX_FRAME_BYTES + 1];
    uint16_t  frame_len;

    /* ── TX phase: send APDU, chaining if needed ──────────────── */
    while (sent < cmd_len) {
        uint16_t chunk = cmd_len - sent;
        bool     chain = (chunk > s_max_frame);
        if (chain) chunk = s_max_frame;

        uint8_t pcb = PCB_IBLOCK(chain, s_block_num);

        ESP_LOGV(TAG, "TX I-block PCB=0x%02X chunk=%u chain=%u BN=%u",
                 pcb, chunk, chain, s_block_num);

        ret = send_iblock(pcb, apdu_cmd + sent, chunk,
                          resp_frame, sizeof(resp_frame), &frame_len,
                          timeout_ms);
        if (ret != ESP_OK) return ret;

        sent += chunk;

        if (chain) {
            /* Card should acknowledge with R(ACK) with toggled block number */
            if (frame_len < 1) return ISO14443_ERR_PROTOCOL;
            uint8_t rpcb = resp_frame[0];
            if (!IS_RACK(rpcb)) {
                ESP_LOGE(TAG, "Expected R(ACK), got PCB=0x%02X", rpcb);
                return ISO14443_ERR_PROTOCOL;
            }
            /* Verify block number in R(ACK) matches ours */
            if (IBLOCK_BN(rpcb) != s_block_num) {
                ESP_LOGE(TAG, "R(ACK) BN mismatch: expected %u, got %u",
                         s_block_num, IBLOCK_BN(rpcb));
                return ISO14443_ERR_PROTOCOL;
            }
            /* Toggle block number for next I-block */
            s_block_num ^= 1;
        }
    }

    /* After last TX block, resp_frame already holds the card's first response block */

    /* ── RX phase: reassemble card response (may also be chained) ─ */
    uint16_t assembled = 0;
    bool     more      = true;

    while (more) {
        if (frame_len < 1) {
            ESP_LOGE(TAG, "Empty response frame");
            return ISO14443_ERR_PROTOCOL;
        }
        uint8_t pcb = resp_frame[0];

        /* Handle S(WTX): card needs more processing time */
        if (IS_SWTX(pcb)) {
            if (frame_len < 2) return ISO14443_ERR_PROTOCOL;
            uint8_t wtm = resp_frame[1];
            ESP_LOGD(TAG, "S(WTX) received, WTM=%u", wtm);
            ret = handle_wtx(pcb, wtm,
                             resp_frame, sizeof(resp_frame), &frame_len,
                             timeout_ms);
            if (ret != ESP_OK) return ret;
            continue; /* re-evaluate the new response block */
        }

        if (!IS_IBLOCK(pcb)) {
            ESP_LOGE(TAG, "Unexpected PCB=0x%02X in RX phase", pcb);
            return ISO14443_ERR_PROTOCOL;
        }

        bool this_chain = IBLOCK_CHAIN_BIT(pcb) != 0;
        uint8_t this_bn = IBLOCK_BN(pcb);

        /* Payload is everything after the PCB byte (no CRC, already stripped) */
        uint16_t data_len = (frame_len > 1) ? (frame_len - 1) : 0;
        const uint8_t *data = resp_frame + 1;

        if (assembled + data_len > resp_size) {
            ESP_LOGE(TAG, "Response overflow: %u + %u > %u",
                     assembled, data_len, resp_size);
            return ISO14443_ERR_OVERFLOW;
        }
        memcpy(apdu_resp + assembled, data, data_len);
        assembled += data_len;

        more = this_chain;

        if (this_chain) {
            /* Acknowledge the card's chained block with R(ACK) at card's BN */
            uint8_t rack_frame[1] = { PCB_RACK(this_bn) };
            ret = pn5180_transceive(rack_frame, 1, 0, true,
                                     resp_frame, sizeof(resp_frame), &frame_len,
                                     timeout_ms);
            if (ret != ESP_OK) return ret;
        }

        /* Toggle our block number for next transaction */
        s_block_num ^= 1;
    }

    *resp_len = assembled;
    ESP_LOGD(TAG, "APDU resp %u bytes, SW=%04X",
             assembled, iso14443_get_sw(apdu_resp, assembled));
    return ESP_OK;
}

/* ──────────────────────────────────────────────────────────────
 * Public: deselect
 * ────────────────────────────────────────────────────────────── */

esp_err_t iso14443_deselect(void)
{
    uint8_t cmd[1]  = { PCB_SDESELECT_REQ };
    uint8_t resp[4] = {0};
    uint16_t rx_len;
    esp_err_t ret = pn5180_transceive(cmd, 1, 0, true,
                                       resp, sizeof(resp), &rx_len, 100);
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "DESELECT failed or no response (card removed?): %s",
                 esp_err_to_name(ret));
    }
    return ESP_OK; /* best-effort; don't fail the caller */
}
