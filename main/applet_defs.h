#pragma once
#include <stdint.h>

// ── Applet AID ────────────────────────────────────────────
// A0 00 00 00 03 45 56 4F 54 45  →  "EVOTE"
// BUG-FIX: byte 9 was 0x52 ('R'), must be 0x54 ('T').
// The JCOP4 card has A0000000034556 4F5445 installed (confirmed via gp --list).
static const uint8_t APPLET_AID[]   = {0xA0,0x00,0x00,0x00,0x03,0x45,0x56,0x4F,0x54,0x45};
static const uint8_t APPLET_AID_LEN = 10;

// ── CLA byte ─────────────────────────────────────────────
static const uint8_t CLA_EVOTING               = 0x80;

// ── INS bytes ────────────────────────────────────────────
static const uint8_t INS_PERSONALIZE             = 0x10;  // enrollment only
static const uint8_t INS_GET_COMMITMENT          = 0x11;  // enrollment only — returns 32-byte SHA-256 commitment
static const uint8_t INS_VERIFY_PIN              = 0x20;
static const uint8_t INS_STORE_FINGERPRINT       = 0x30;
static const uint8_t INS_VERIFY_FINGERPRINT      = 0x31;
static const uint8_t INS_STORE_LIVENESS          = 0x32;  // enrollment: write 256-byte embedding
static const uint8_t INS_GET_VOTER_ID            = 0x40;
static const uint8_t INS_GET_LIVENESS            = 0x43;  // voting: read 256-byte embedding
static const uint8_t INS_CHECK_VOTE_STATUS       = 0x50;
static const uint8_t INS_SET_VOTED               = 0x51;
static const uint8_t INS_INIT_SECURE_CHANNEL     = 0x60;
static const uint8_t INS_ESTABLISH_SESSION       = 0x61;
static const uint8_t INS_GET_CHALLENGE           = 0x70;
static const uint8_t INS_GET_SIGNATURE           = 0x71;
static const uint8_t INS_GET_PUBLIC_KEY          = 0x72;
static const uint8_t INS_WRITE_VOTER_CRED        = 0x80;
static const uint8_t INS_LOCK_CARD               = 0x90;

// ── Status words ─────────────────────────────────────────
static const uint16_t SW_SUCCESS                        = 0x9000;
static const uint16_t SW_PIN_VERIFICATION_REQUIRED      = 0x6300;
static const uint16_t SW_PIN_BLOCKED                    = 0x6983;
static const uint16_t SW_ALREADY_VOTED                  = 0x6A81;
static const uint16_t SW_FINGERPRINT_NOT_MATCH          = 0x6A82;
static const uint16_t SW_SECURE_CHANNEL_NOT_ESTABLISHED = 0x6982;
static const uint16_t SW_PIN_REQUIRED                   = 0x6301;
static const uint16_t SW_CONDITIONS_NOT_SATISFIED       = 0x6985;
static const uint16_t SW_NOT_PERSONALIZED               = 0x6987;
