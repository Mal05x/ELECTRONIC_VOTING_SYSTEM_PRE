#pragma once
#include "ili9341.h"
#include "session.h"
#include <stdint.h>
#include <string>

// All functions mirror the tft_ui_v5.h API, rewritten for native IDF driver.

void ui_draw_card(int x, int y, int w, int h,
                  uint16_t bg = CLR_SURFACE, uint16_t border = CLR_PURPLE);
void ui_draw_header(const char *title, uint16_t bar_color = CLR_PURPLE);
void ui_draw_progress_bar(int x, int y, int w, int h, int pct,
                          uint16_t fill = CLR_PURPLE, uint16_t bg = CLR_SURFACE);
void ui_draw_step_dots(int steps, int active, int cx, int y,
                       int r = 5, int gap = 18);
void ui_draw_face_guide(int cx, int cy, uint16_t color = CLR_PURPLE,
                        bool detected = false);

// ── Screen functions ──────────────────────────────────────
void ui_display_logo(void);
void ui_display_idle(void);
void ui_display_pin_entry(void);
void ui_update_pin_display(void);
void ui_display_pin_digit_selector(int digit);
void ui_display_fingerprint(bool verified);
void ui_display_liveness_guide(bool face_detected);
void ui_display_liveness_burst(int frame_idx);
void ui_display_liveness_result(bool passed);
void ui_display_officer_pin(const char *mode_name, int filled_dots, int current_digit);
void ui_officer_update_dot(int index, bool filled);
void ui_officer_update_selector(int digit);
void ui_display_officer_locked(int remaining_secs);
void ui_display_officer_wrong_pin(int attempts_left);
void ui_display_active_challenge(const char *action);
void ui_display_voting_interface(void);
void ui_display_vote_confirmation(void);
void ui_display_vote_success(const char *receipt_code);
void ui_display_status(const char *msg);
void ui_display_error(const char *msg);
void ui_display_admin_reset_prompt(void);

// ── Enrollment screens ────────────────────────────────────
void ui_display_enroll_idle(void);
void ui_display_enroll_pin_set(bool confirm_mode);   // false=set, true=confirm
void ui_display_enroll_digit(int digit);             // update selector only
void ui_display_enroll_pin_progress(int dots_filled);// update filled dots only
void ui_display_enroll_fingerprint(bool captured);
void ui_display_enroll_liveness(bool captured);
void ui_display_enroll_success(const char *enrollment_id);

// ─────────────────────────────────────────────────────────────────────────
//  Homepage dashboard
// ─────────────────────────────────────────────────────────────────────────

/**
 * Home screen menu item indices.
 * Pass these to ui_display_home() and ui_home_move_selection().
 */
#define HOME_VOTE     0
#define HOME_ENROLL   1
#define HOME_SETTINGS 2
#define HOME_ABOUT    3
#define HOME_ITEM_COUNT 4

// Layout constants (px, landscape 320×240)
#define HOME_STATUS_H   24     // top status bar
#define HOME_BAND_H     38     // election info band
#define HOME_MENU_Y     62     // first menu item top edge
#define HOME_ITEM_H     35     // height of each menu item row
#define HOME_FOOTER_Y   202    // footer bar top edge

/**
 * ui_display_home — full redraw of the home dashboard.
 * selected_item: HOME_VOTE … HOME_ABOUT
 */
void ui_display_home(int selected_item);

/**
 * ui_home_update_election_band — repaint only the 38-px election info band.
 * Call from the background election config task in main.cpp after
 * g_electionCfg is populated.  Does not touch the status bar or menu.
 * Safe to call from any task since it only writes to a known screen region.
 */
void ui_home_update_election_band(void);

/**
 * ui_home_update_status — repaint only the 24-px status bar.
 * Call every ~1 s from the main task to refresh the clock, battery,
 * and WiFi indicator without redrawing the whole screen.
 * @param wifi_connected  true = WiFi up
 * @param battery_pct     0-100
 */
void ui_home_update_status(bool wifi_connected, int battery_pct);

/**
 * ui_home_move_selection — partial redraw of two menu rows only.
 * Use instead of ui_display_home() when the user presses UP/DOWN.
 */
void ui_home_move_selection(int old_item, int new_item);

// ─────────────────────────────────────────────────────────────────────────
//  Settings screen
// ─────────────────────────────────────────────────────────────────────────

#define SETTINGS_ITEM_COUNT 6
#define SETTINGS_LIVENESS   0
#define SETTINGS_DISPLAY    1
#define SETTINGS_NETWORK    2
#define SETTINGS_HEARTBEAT  3
#define SETTINGS_TERMKEY    4
#define SETTINGS_ADMIN_RESET 5

/**
 * ui_display_settings — full redraw of the settings list.
 * selected_item: 0-4
 */
void ui_display_settings(int selected_item);

/** ui_settings_move — partial redraw of two rows only. */
void ui_settings_move(int old_item, int new_item);

/** ui_settings_update_liveness — repaint only the liveness-mode row value. */
void ui_settings_update_liveness(const char *mode_str);

// ─────────────────────────────────────────────────────────────────────────
//  About / diagnostics screen
// ─────────────────────────────────────────────────────────────────────────

/**
 * ui_display_about — shows terminal ID, firmware version, election,
 * battery, uptime, WiFi status, last heartbeat time, and liveness mode.
 */
void ui_display_about(void);

// ─────────────────────────────────────────────────────────────────────────
//  Tamper overlay
// ─────────────────────────────────────────────────────────────────────────

/** ui_display_tamper_alert — full-screen red overlay, shown when the
 *  reed switch detects the terminal enclosure has been opened. */
void ui_display_tamper_alert(void);
