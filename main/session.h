#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <string>
#include "camera_uart.h"
#include "smart_card.h"

// ── Terminal operating mode ───────────────────────────────
// Selected at boot based on a physical jumper or NVS flag.
// ENROLLMENT: used by INEC staff to personalize blank cards.
// VOTING:     deployed at polling units on election day.
enum TerminalMode {
    MODE_VOTING,
    MODE_ENROLLMENT
};

// ── Structs ───────────────────────────────────────────────

struct ElectionConfig {
       std::string electionId;
       std::string electionTitle;
       std::string closingTime;
       int         pollingUnitId;
       bool        loaded;
       // NEW: liveness evaluation mode from /api/terminal/config.
       // "PASSIVE" = MiniFASNet burst (BURST: command to CAM)  [default]
       // "ACTIVE"  = MediaPipe challenge-response (ACTIVE: command to CAM)
       // Parsed by net_fetch_election_config(), used by cam_perform_liveness().
       std::string livenessMode;
   };
	extern ElectionConfig g_electionCfg;


struct Candidate {
    std::string id;
    std::string name;
    std::string party;
    std::string position;
};

struct NavigationState {
    int currentSelection;
    int maxSelections;
};

struct SecureChannel {
    uint8_t sessionKey[16];
    uint8_t terminalRandom[16];
    uint8_t cardRandom[16];
    uint8_t cardStaticKey[16];  // per-card diversifier — from NVS (voting) or backend (enrollment)
    bool    established;
};

struct VoterSession {
    std::string  cardUID;
    std::string  voterID;
    std::string  voterPublicKey;
    bool         cardAuthenticated;
    bool         pinVerified;
    bool         fingerprintVerified;
    bool         livenessVerified;
    bool         hasVoted;
    std::string  sessionToken;
    std::string  sessionId;
    std::string  cardBurnProof;
    std::string  pendingRegId;
    SecureChannel secureChannel;
    applet_session_t applet_session;
};

// ── Enrollment record (fetched from backend) ──────────────
// Mirrors TerminalEnrollmentRecordDTO from the Spring backend.
struct EnrollmentRecord {
    std::string enrollmentId;       // UUID
    std::string electionId;         // UUID
    long        pollingUnitId;
    std::string voterPublicKey;     // Base64 SPKI (already on card from pending reg)
    std::string encryptedDemographic; // AES-256 blob for display (not written to card)
    uint8_t     cardStaticKey[16];  // Decoded from Base64 — written to card + stored in NVS
    uint8_t     adminTokenHash[32]; // SHA-256 of admin token — written to card
    bool        loaded;
};

// ── Enrollment session ────────────────────────────────────
struct EnrollmentSession {
    std::string cardUID;
    std::string cardUIDHash;
    std::string sessionId; // SHA-256(cardUID) — used as NVS key
    uint8_t     pin[4];             // Raw PIN digits chosen by voter
    uint8_t     pinConfirm[4];      // Re-entry for confirmation
    uint8_t fpTemplate[1536];    // R307 template (512 bytes)
    uint16_t    fpTemplateLen;
    uint8_t     livenessEmbedding[256]; // MiniFASNetV2_SE 8×8 grid embedding (64 bytes)
    uint16_t    livenessEmbeddingLen;
    bool        cardSelectOk;
    bool        pinSet;
    bool        pinConfirmed;
    bool        fingerprintCaptured;
    bool        livenessEmbeddingCaptured;
    bool        cardWritten;
    bool        enrollVerified;// post-write: secure channel + PIN + FP verified
    std::string voterPublicKey;
};

// ── System state machine ──────────────────────────────────
enum SystemState {

	STATE_HOME = 0,         // <<< NEW — dashboard menu
	STATE_SETTINGS,         // <<< NEW — settings screen
	STATE_ABOUT,            // <<< NEW — about/diagnostics

    // ── Voting states ─────────────────────────────────────
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
    STATE_ERROR,
	STATE_ADMIN_RESET_SCAN,      // 💥 Add this
	STATE_ADMIN_RESET_PIN_ENTRY,

    // ── Enrollment states ─────────────────────────────────
    STATE_ENROLL_IDLE,          // waiting for blank card
	STATE_SLEEP,
	STATE_ENROLL_SCAN,
    STATE_ENROLL_FETCH,         // fetching enrollment record from backend
    STATE_ENROLL_PIN_SET,       // voter chooses PIN
    STATE_ENROLL_PIN_CONFIRM,   // voter re-enters PIN to confirm
    STATE_ENROLL_FINGERPRINT,   // R307 capture
    STATE_ENROLL_LIVENESS,      // face embedding capture via ESP32-CAM
    STATE_ENROLL_WRITING,       // sending INS_PERSONALIZE to card
    STATE_ENROLL_VERIFY,        // post-write: secure channel + PIN verify + FP verify + store embedding
    STATE_ENROLL_CONFIRM,       // POSTing result to backend
    STATE_ENROLL_DONE,         // success — remove card
	STATE_ENROLL_COMPLETE,
    STATE_ENROLL_ERROR,        // error → ENROLL_IDLE

};

// ── FreeRTOS event group bits ─────────────────────────────
#define EVT_NET_AUTH_TRIGGER     (1 << 0)
#define EVT_NET_VOTE_TRIGGER     (1 << 1)
#define EVT_NET_SUCCESS          (1 << 2)
#define EVT_NET_ERROR            (1 << 3)
#define EVT_NET_ENROLL_FETCH     (1 << 4)
#define EVT_NET_ENROLL_CONFIRM   (1 << 5)

// ── Globals (defined in main.cpp) ────────────────────────
extern VoterSession      g_session;
extern EnrollmentSession g_enroll_session;
extern EnrollmentRecord  g_enroll_record;
extern Candidate         g_candidates[10];
extern int               g_candidateCount;
extern std::string       g_pinBuffer;
extern NavigationState   g_nav;
extern SystemState       g_state;
extern ElectionConfig    g_electionCfg;
extern std::string       g_lastTransactionId;
extern TerminalMode      g_terminal_mode;
