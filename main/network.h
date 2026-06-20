#pragma once
#include <stdbool.h>
#include <string>

// Add this near your other network function declarations
extern volatile bool g_candidates_loaded;

// WiFi
bool net_wifi_connect(void);     // blocks up to WIFI_CONNECT_TIMEOUT_MS
void net_wifi_reconnect_if_needed(void);

// NTP
bool net_ntp_sync(void);

// Backend endpoints
bool net_fetch_election_config(void);
bool net_fetch_candidates(void);
bool net_request_tap_session(void);  // uses g_session, sets g_session.sessionToken
bool net_submit_vote(void);         // uses g_session & g_nav, sets g_lastTransactionId
void net_send_heartbeat(void);
bool net_register_pending(void);
bool net_fetch_officer_pin_hash();
bool net_check_and_recover_vote(void);
void task_background_candidate_sync(void *pvParameters);
// ── Enrollment endpoints ───────────────────────────────────
// Fetches pending enrollment record for this terminal.
// Populates g_enroll_record on success. Returns false if none queued.
bool net_fetch_pending_enrollment(void);

// POSTs completion to /api/terminal/enrollment.
// Backend marks the record COMPLETED and zeroes the raw cardStaticKey.
bool net_complete_enrollment(void);
// Add this line to your network.h file
int read_battery_percent(void);
