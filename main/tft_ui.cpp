#include "tft_ui.h"
#include "ili9341.h"
#include "session.h"
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <math.h>

#define TERMINAL_ID "TERM-KD-001"

// ── Shared helpers ────────────────────────────────────────

void ui_draw_card(int x, int y, int w, int h, uint16_t bg, uint16_t border) {
    ili9341_fill_round_rect(x, y, w, h, 6, bg);
    ili9341_draw_round_rect(x, y, w, h, 6, border);
}

void ui_draw_header(const char *title, uint16_t bar_color) {
    ili9341_fill_rect(0, 0, 320, 28, bar_color);
    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(2);
    int tw = strlen(title) * 12;
    ili9341_set_cursor((320 - tw) / 2, 7);
    ili9341_print(title);
}

void ui_draw_progress_bar(int x, int y, int w, int h, int pct,
                          uint16_t fill, uint16_t bg) {
    ili9341_draw_round_rect(x, y, w, h, h / 2, CLR_BORDER);
    if (pct > 0)
        ili9341_fill_round_rect(x + 1, y + 1, (w - 2) * pct / 100, h - 2,
                                h / 2 - 1, fill);
    (void)bg;
}

void ui_draw_step_dots(int steps, int active, int cx, int y, int r, int gap) {
    int totalW = steps * (2 * r) + (steps - 1) * (gap - 2 * r);
    int startX = cx - totalW / 2 + r;
    for (int i = 0; i < steps; i++) {
        int x = startX + i * gap;
        if (i == active) {
            ili9341_fill_circle(x, y, r, CLR_PURPLE);
            ili9341_draw_circle(x, y, r + 1, CLR_PURPLE_LT);
        } else if (i < active) {
            ili9341_fill_circle(x, y, r, CLR_SUCCESS);
        } else {
            ili9341_fill_circle(x, y, r, CLR_SURFACE);
            ili9341_draw_circle(x, y, r, CLR_SUB);
        }
    }
}

void ui_draw_face_guide(int cx, int cy, uint16_t color, bool detected) {
    uint16_t c = detected ? CLR_SUCCESS : color;
    ili9341_draw_round_rect(cx - 45, cy - 55, 90, 110, 44, c);
    ili9341_draw_round_rect(cx - 43, cy - 53, 86, 106, 42, c);
    ili9341_fill_circle(cx, cy, 2, c);
    int tx = cx - 45, ty = cy - 55;
    ili9341_draw_hline(tx,      ty,      12, c);
    ili9341_draw_vline(tx,      ty,      12, c);
    ili9341_draw_hline(tx + 78, ty,      12, c);
    ili9341_draw_vline(tx + 90, ty,      12, c);
    ili9341_draw_hline(tx,      ty + 98, 12, c);
    ili9341_draw_vline(tx,      ty + 98, 12, c);
    ili9341_draw_hline(tx + 78, ty + 98, 12, c);
    ili9341_draw_vline(tx + 90, ty + 98, 12, c);
}

// ── Logo / splash ─────────────────────────────────────────

void ui_display_logo(void) {
    ili9341_fill_screen(CLR_BG);
    for (int i = 0; i < 4; i++) ili9341_draw_hline(0, i, 320, CLR_PURPLE);

    // Shield
    ili9341_fill_round_rect(138, 30, 44, 52, 8, CLR_PURPLE);
    ili9341_draw_round_rect(136, 28, 48, 56, 10, CLR_PURPLE_LT);
    ili9341_draw_line(148, 56, 156, 68, CLR_INK);
    ili9341_draw_line(156, 68, 174, 46, CLR_INK);
    ili9341_draw_line(149, 56, 157, 68, CLR_INK);
    ili9341_draw_line(157, 68, 175, 46, CLR_INK);

    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(3);
    ili9341_set_cursor(78, 95);
    ili9341_print("MAL");

    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(1);
    ili9341_set_cursor(42, 125);
    ili9341_print("Multi-Factor E-Voting System");

    ili9341_draw_hline(60, 140, 200, CLR_BORDER);

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(68, 150);
    ili9341_print("ECDSA  |  NFC  |  Fingerprint");
    ili9341_set_cursor(92, 163);
    ili9341_print("Liveness  |  V3 Burst");

    ui_draw_card(130, 178, 60, 20, CLR_PURPLE_DIM, CLR_PURPLE);
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(1);
    ili9341_set_cursor(143, 184);
    ili9341_print("v5.0");

    for (int i = 236; i < 240; i++) ili9341_draw_hline(0, i, 320, CLR_PURPLE);

    vTaskDelay(pdMS_TO_TICKS(2500));
}

// ── Idle screen ───────────────────────────────────────────

void ui_display_idle(void) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("READY TO VOTE");
    ui_draw_card(20, 38, 280, 90, CLR_SURFACE, CLR_PURPLE);

    // Card icon
    ili9341_fill_round_rect(110, 50, 100, 64, 8, CLR_PURPLE_DIM);
    ili9341_draw_round_rect(110, 50, 100, 64, 8, CLR_PURPLE);
    for (int r = 10; r <= 28; r += 9)
        ili9341_draw_circle(210, 82, r, CLR_PURPLE_LT);
    ili9341_fill_round_rect(124, 66, 22, 16, 3, CLR_PURPLE);

    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(2);
    ili9341_set_cursor(60, 140);
    ili9341_print("Tap Smart Card");

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(82, 165);
    ili9341_print("Hold card flat on reader");

    ui_draw_step_dots(5, 0, 160, 210);

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(10,  225); ili9341_print("Card");
    ili9341_set_cursor(62,  225); ili9341_print("PIN");
    ili9341_set_cursor(106, 225); ili9341_print("Finger");
    ili9341_set_cursor(163, 225); ili9341_print("Face");
    ili9341_set_cursor(213, 225); ili9341_print("Vote");
}

// ── PIN entry ─────────────────────────────────────────────

void ui_display_pin_entry(void) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("ENTER PIN");
    ui_draw_card(20, 38, 280, 76, CLR_SURFACE, CLR_PURPLE);

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(32, 48);
    ili9341_print("Enter your 4-digit voter PIN");

    ui_draw_card(20, 125, 280, 64, CLR_SURFACE, CLR_BORDER);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(30, 133); ili9341_print("[UP]/[DOWN]  Change digit");
    ili9341_set_cursor(30, 147); ili9341_print("[RIGHT]      Confirm digit");
    ili9341_set_cursor(30, 161); ili9341_print("[BACK]       Delete  /  Cancel");

    ui_draw_step_dots(5, 1, 160, 210);
    ui_update_pin_display();
}

void ui_update_pin_display(void) {
    ili9341_fill_rect(20, 68, 280, 50, CLR_BG);
    int startX = 80;
    int pinLen = (int)g_pinBuffer.length();
    for (int i = 0; i < 4; i++) {
        int x = startX + i * 42;
        bool filled = (i < pinLen);
        ili9341_fill_round_rect(x, 72, 30, 36, 6,
                                filled ? CLR_PURPLE : CLR_SURFACE);
        ili9341_draw_round_rect(x, 72, 30, 36, 6,
                                filled ? CLR_PURPLE_LT : CLR_BORDER);
        if (filled) {
            ili9341_set_text_color(CLR_INK);
            ili9341_set_text_size(3);
            ili9341_set_cursor(x + 9, 78);
            ili9341_print("*");
        }
    }
}

void ui_display_pin_digit_selector(int digit) {
    ili9341_fill_rect(80, 195, 160, 32, CLR_BG);
    ui_draw_card(130, 193, 60, 30, CLR_PURPLE_DIM, CLR_PURPLE);
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(3);
    ili9341_set_cursor(149, 198);
    ili9341_print_int(digit);
}

// ── Fingerprint ───────────────────────────────────────────

void ui_display_fingerprint(bool verified) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header(verified ? "FINGERPRINT OK" : "FINGERPRINT SCAN");

    uint16_t ring_color = verified ? CLR_SUCCESS : CLR_PURPLE;
    int cx = 160, cy = 115;
    ili9341_fill_circle(cx, cy, 38, CLR_SURFACE);
    for (int r = 8; r <= 38; r += 8) ili9341_draw_circle(cx, cy, r, ring_color);
    ili9341_fill_circle(cx, cy, 4, ring_color);

    if (verified) {
        ili9341_draw_line(cx - 14, cy + 2,  cx - 4,  cy + 14, CLR_INK);
        ili9341_draw_line(cx - 14, cy + 3,  cx - 4,  cy + 15, CLR_INK);
        ili9341_draw_line(cx - 4,  cy + 14, cx + 16, cy - 8,  CLR_INK);
        ili9341_draw_line(cx - 4,  cy + 15, cx + 16, cy - 7,  CLR_INK);
        ili9341_set_text_color(CLR_SUCCESS);
        ili9341_set_text_size(1);
        ili9341_set_cursor(115, 162);
        ili9341_print("Fingerprint Verified");
    } else {
        ili9341_set_text_color(CLR_INK);
        ili9341_set_text_size(2);
        ili9341_set_cursor(94, 162);
        ili9341_print("Place finger");
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_text_size(1);
        ili9341_set_cursor(108, 182);
        ili9341_print("Hold steady...");
    }
    ui_draw_step_dots(5, 2, 160, 210);
}

// ── Liveness ──────────────────────────────────────────────

void ui_display_liveness_guide(bool face_detected) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("FACE SCAN");

    ili9341_set_text_color(face_detected ? CLR_SUCCESS : CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(face_detected ? 98 : 72, 33);
    ili9341_print(face_detected ? "Face detected!" : "Position face in the frame");

    ui_draw_face_guide(100, 128, CLR_PURPLE, face_detected);

    ui_draw_card(195, 38, 116, 148, CLR_SURFACE, CLR_BORDER);
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(1);
    ili9341_set_cursor(202, 46);  ili9341_print("Guidelines:");
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(202, 62);  ili9341_print("* 30-50cm away");
    ili9341_set_cursor(202, 76);  ili9341_print("* Face forward");
    ili9341_set_cursor(202, 90);  ili9341_print("* Eyes open");
    ili9341_set_cursor(202, 104); ili9341_print("* Remove mask");
    ili9341_set_cursor(202, 118); ili9341_print("* Good lighting");

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(202, 140); ili9341_print("Distance:");
    ili9341_draw_round_rect(202, 152, 100, 10, 5, CLR_BORDER);
    ili9341_fill_round_rect(203, 153, 50, 8, 4, CLR_PURPLE);
    ili9341_set_text_color(CLR_SUCCESS);
    ili9341_set_cursor(222, 166); ili9341_print("Ideal range");

    ui_draw_step_dots(5, 3, 160, 210);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(10,  225); ili9341_print("Card");
    ili9341_set_cursor(62,  225); ili9341_print("PIN");
    ili9341_set_cursor(106, 225); ili9341_print("Finger");
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_cursor(163, 225); ili9341_print("Face");
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(213, 225); ili9341_print("Vote");
}

void ui_display_liveness_burst(int frame_idx) {
    ili9341_fill_rect(0, 174, 320, 32, CLR_BG);
    int pct = (frame_idx + 1) * 20;
    ui_draw_progress_bar(20, 192, 280, 12, pct, CLR_PURPLE, CLR_SURFACE);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    char buf[64];
    snprintf(buf, sizeof(buf), "Scanning... frame %d / 5", frame_idx + 1);
    ili9341_set_cursor(88, 176);
    ili9341_print(buf);
}

void ui_display_liveness_result(bool passed) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header(passed ? "IDENTITY VERIFIED" : "SCAN FAILED");

    uint16_t ic = passed ? CLR_SUCCESS : CLR_DANGER;
    int cx = 160, cy = 110;
    ili9341_fill_circle(cx, cy, 42, CLR_SURFACE);
    ili9341_draw_circle(cx, cy, 42, ic);
    ili9341_draw_circle(cx, cy, 40, ic);

    if (passed) {
        ili9341_draw_line(cx - 16, cy + 4,  cx - 4,  cy + 18, ic);
        ili9341_draw_line(cx - 15, cy + 4,  cx - 3,  cy + 18, ic);
        ili9341_draw_line(cx - 4,  cy + 18, cx + 22, cy - 12, ic);
        ili9341_draw_line(cx - 3,  cy + 18, cx + 23, cy - 12, ic);
        ili9341_set_text_color(CLR_SUCCESS);
        ili9341_set_text_size(1);
        ili9341_set_cursor(106, 165); ili9341_print("Liveness Confirmed");
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_cursor(118, 180); ili9341_print("Proceeding to vote...");
    } else {
        ili9341_draw_line(cx - 16, cy - 16, cx + 16, cy + 16, ic);
        ili9341_draw_line(cx - 15, cy - 16, cx + 17, cy + 16, ic);
        ili9341_draw_line(cx + 16, cy - 16, cx - 16, cy + 16, ic);
        ili9341_draw_line(cx + 17, cy - 16, cx - 15, cy + 16, ic);
        ili9341_set_text_color(CLR_DANGER);
        ili9341_set_text_size(1);
        ili9341_set_cursor(94, 165); ili9341_print("Liveness Check Failed");
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_cursor(64, 180); ili9341_print("Remove obstructions and retry");
    }
    ui_draw_step_dots(5, 3, 160, 210);
    vTaskDelay(pdMS_TO_TICKS(1800));
}

/* ═══════════════════════════════════════════════════════════════════════════
 * PATCH B — tft_ui.cpp
 * Add this entire block after ui_display_liveness_result().
 *
 * Layout (320×240 ILI9341):
 *   [0  – 28 ] Header bar   "OFFICER ACCESS" (orange/warning accent)
 *   [38 – 78 ] Mode label   e.g. "ENROLLMENT MODE"
 *   [90 – 130] 6 dot row    centred, 28px spacing
 *   [140– 165] Digit wheel  "▲  7  ▼" style
 *   [172– 192] Hint text    "RIGHT=confirm  BACK=del  CENTER=submit"
 *   [200– 215] Attempt dots (filled/dim based on remaining attempts)
 * ═══════════════════════════════════════════════════════════════════════════ */


/* Dot geometry — 6 dots centred on x=160 */
#define OFF_DOT_R     10          /* dot radius */
#define OFF_DOT_SPACE 28          /* centre-to-centre spacing */
#define OFF_DOT_Y     108         /* dot row y-centre */
/* Compute x-centre of dot i (0..5) */
/* total width = 5 * OFF_DOT_SPACE = 140px → start at 160 - 70 = 90 */
#define OFF_DOT_X(i)  (90 + (i) * OFF_DOT_SPACE)

#define OFF_SEL_Y     148         /* digit selector row y */

/* ── Full officer PIN entry screen ──────────────────────────────────────── */
void ui_display_officer_pin(const char *mode_name, int filled_dots, int current_digit) {
    ili9341_fill_screen(CLR_BG);

    /* Header bar — warning colour to visually distinguish from voter flow */
    for (int i = 0; i < 4; i++) ili9341_draw_hline(0, i, 320, CLR_WARNING);
    ili9341_fill_rect(0, 4, 320, 24, CLR_WARNING);
    ili9341_set_text_color(CLR_BG);
    ili9341_set_text_size(2);
    ili9341_set_cursor(56, 8);
    ili9341_print("OFFICER ACCESS");
    for (int i = 28; i < 32; i++) ili9341_draw_hline(0, i, 320, CLR_WARNING);

    /* Mode label */
    ili9341_set_text_color(CLR_WARNING);
    ili9341_set_text_size(1);
    /* Centre the label: approximate 6px per char at size 1 */
    int lbl_len = strlen(mode_name);
    char lbl[32];
    snprintf(lbl, sizeof(lbl), "— %s MODE —", mode_name);
    int lbl_px = strlen(lbl) * 6;
    ili9341_set_cursor((320 - lbl_px) / 2, 42);
    ili9341_print(lbl);

    /* Lock icon — simple padlock drawn with primitives */
    int lx = 144, ly = 52;
    /* Shackle (arc approximation: two vertical lines + top horizontal) */
    ili9341_draw_vline(lx + 8,  ly,     12, CLR_SUB);
    ili9341_draw_vline(lx + 24, ly,     12, CLR_SUB);
    ili9341_draw_hline(lx + 8,  ly,     17, CLR_SUB);
    /* Body */
    ili9341_fill_round_rect(lx + 4, ly + 10, 24, 18, 3, CLR_SURFACE);
    ili9341_draw_round_rect(lx + 4, ly + 10, 24, 18, 3, CLR_SUB);
    /* Keyhole dot */
    ili9341_fill_circle(lx + 16, ly + 20, 3, CLR_WARNING);

    /* 6 PIN dots */
    for (int i = 0; i < 6; i++) {
        if (i < filled_dots) {
            ili9341_fill_circle(OFF_DOT_X(i), OFF_DOT_Y, OFF_DOT_R, CLR_WARNING);
        } else {
            ili9341_fill_circle(OFF_DOT_X(i), OFF_DOT_Y, OFF_DOT_R, CLR_SURFACE);
            ili9341_draw_circle(OFF_DOT_X(i), OFF_DOT_Y, OFF_DOT_R, CLR_SUB);
        }
    }
    /* Highlight ring around next dot to fill */
    if (filled_dots < 6) {
        ili9341_draw_circle(OFF_DOT_X(filled_dots), OFF_DOT_Y,
                            OFF_DOT_R + 2, CLR_WARNING);
    }

    /* Digit selector */
    ui_officer_update_selector(current_digit);

    /* Hint bar */
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(8, 174);
    ili9341_print("RIGHT=confirm  BACK=del  CENTER=submit");

    /* Bottom border */
    for (int i = 236; i < 240; i++) ili9341_draw_hline(0, i, 320, CLR_WARNING);
}

/* ── Update a single dot without redrawing the whole screen ─────────────── */
void ui_officer_update_dot(int index, bool filled) {
    if (index < 0 || index >= 6) return;
    int cx = OFF_DOT_X(index);
    if (filled) {
        ili9341_fill_circle(cx, OFF_DOT_Y, OFF_DOT_R, CLR_WARNING);
        /* Remove highlight ring from this dot */
        ili9341_draw_circle(cx, OFF_DOT_Y, OFF_DOT_R + 2, CLR_BG);
    } else {
        ili9341_fill_circle(cx, OFF_DOT_Y, OFF_DOT_R, CLR_SURFACE);
        ili9341_draw_circle(cx, OFF_DOT_Y, OFF_DOT_R, CLR_SUB);
        ili9341_draw_circle(cx, OFF_DOT_Y, OFF_DOT_R + 2, CLR_BG);
    }
    /* Highlight ring always follows the next empty dot */
    if (!filled && index < 6) {
        ili9341_draw_circle(OFF_DOT_X(index), OFF_DOT_Y,
                            OFF_DOT_R + 2, CLR_WARNING);
    }
}

/* ── Update digit selector widget ──────────────────────────────────────── */
void ui_officer_update_selector(int digit) {
    /* Clear selector area */
    ili9341_fill_rect(80, OFF_SEL_Y - 2, 160, 24, CLR_BG);

    /* Up arrow */
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(152, OFF_SEL_Y - 2);
    ili9341_print("^");

    /* Digit — large, centred */
    char d[2] = { (char)('0' + digit), '\0' };
    ili9341_set_text_color(CLR_WARNING);
    ili9341_set_text_size(3);
    ili9341_set_cursor(152, OFF_SEL_Y + 4);
    ili9341_print(d);

    /* Down arrow */
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(152, OFF_SEL_Y + 22);
    ili9341_print("v");
}

/* ── Lockout screen ─────────────────────────────────────────────────────── */
void ui_display_officer_locked(int remaining_secs) {
    ili9341_fill_screen(CLR_BG);

    for (int i = 0; i < 4; i++) ili9341_draw_hline(0, i, 320, CLR_DANGER);
    ili9341_fill_rect(0, 4, 320, 24, CLR_DANGER);
    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(2);
    ili9341_set_cursor(52, 8);
    ili9341_print("TERMINAL LOCKED");

    /* Large lock icon */
    int lx = 140, ly = 48;
    ili9341_draw_vline(lx + 10, ly,      16, CLR_DANGER);
    ili9341_draw_vline(lx + 30, ly,      16, CLR_DANGER);
    ili9341_draw_hline(lx + 10, ly,      21, CLR_DANGER);
    ili9341_fill_round_rect(lx + 4, ly + 14, 32, 26, 4, CLR_SURFACE);
    ili9341_draw_round_rect(lx + 4, ly + 14, 32, 26, 4, CLR_DANGER);
    ili9341_fill_circle(lx + 20, ly + 28, 4, CLR_DANGER);

    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(1);
    ili9341_set_cursor(32, 108);
    ili9341_print("Too many incorrect attempts.");
    ili9341_set_cursor(32, 122);
    ili9341_print("Terminal locked. Contact supervisor.");

    /* Countdown */
    char buf[40];
    int  mins = remaining_secs / 60;
    int  secs = remaining_secs % 60;
    if (mins > 0)
        snprintf(buf, sizeof(buf), "Unlocks in %d min %02d sec", mins, secs);
    else
        snprintf(buf, sizeof(buf), "Unlocks in %d seconds", secs);

    /* Clear just the countdown line */
    ili9341_fill_rect(0, 145, 320, 18, CLR_BG);
    ili9341_set_text_color(CLR_WARNING);
    ili9341_set_text_size(1);
    int cw = strlen(buf) * 6;
    ili9341_set_cursor((320 - cw) / 2, 148);
    ili9341_print(buf);

    for (int i = 236; i < 240; i++) ili9341_draw_hline(0, i, 320, CLR_DANGER);
}

/* ── Wrong PIN feedback (overlay on existing screen) ────────────────────── */
void ui_display_officer_wrong_pin(int attempts_left) {
    /* Clear the hint row and print "INCORRECT PIN" warning */
    ili9341_fill_rect(0, 190, 320, 30, CLR_BG);
    ili9341_set_text_color(CLR_DANGER);
    ili9341_set_text_size(1);

    char buf[40];
    if (attempts_left == 1)
        snprintf(buf, sizeof(buf), "INCORRECT  -  1 attempt remaining!");
    else
        snprintf(buf, sizeof(buf), "INCORRECT  -  %d attempts remaining", attempts_left);

    int w = strlen(buf) * 6;
    ili9341_set_cursor((320 - w) / 2, 196);
    ili9341_print(buf);

    /* Flash the dot row red briefly */
    for (int i = 0; i < 6; i++)
        ili9341_fill_circle(OFF_DOT_X(i), OFF_DOT_Y, OFF_DOT_R, CLR_DANGER);
}


/**
 * ui_display_active_challenge — Show the voter a clear instruction for the
 * MediaPipe active liveness challenge received from the backend.
 *
 * Called by cam_perform_liveness() in camera_uart.cpp immediately after the
 * CAM relays the challenge type over UART. The voter reads the instruction
 * and performs the action while the CAM streams frames to the backend.
 *
 * Supported actions: BLINK | SMILE | TURN_HEAD_LEFT | TURN_HEAD_RIGHT
 * Unknown actions fall back to a generic "Look at camera" prompt.
 *
 * Layout (320x240 ILI9341):
 *   [0  – 28 ] Header bar  "LIVENESS CHECK"
 *   [38 – 148] Icon area   (centred, ~80px radius)
 *   [155– 172] Action text (large, coloured)
 *   [178– 194] Instruction (small, subtitle)
 *   [200– 215] Progress hint bar
 *   [220– 240] Step dots   (Card › PIN › Finger › Face › Vote)
 */
void ui_display_active_challenge(const char *action) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("LIVENESS CHECK", CLR_PURPLE);

    /* ── Determine which challenge ─────────────────────────────────────── */
    bool is_blink      = (strcmp(action, "BLINK")           == 0);
    bool is_smile      = (strcmp(action, "SMILE")           == 0);
    bool is_turn_left  = (strcmp(action, "TURN_HEAD_LEFT")  == 0);
    bool is_turn_right = (strcmp(action, "TURN_HEAD_RIGHT") == 0);

    int cx = 160, cy = 95;   /* icon centre */

    /* ── Icon ──────────────────────────────────────────────────────────── */

    if (is_blink) {
        /* Two eyes: left = open, right = closed (eyelid line) */
        /* Left eye — open */
        ili9341_fill_circle(cx - 34, cy, 22, CLR_SURFACE);
        ili9341_draw_circle(cx - 34, cy, 22, CLR_PURPLE_LT);
        ili9341_draw_circle(cx - 34, cy, 21, CLR_PURPLE_LT);
        ili9341_fill_circle(cx - 34, cy, 10, CLR_INK);   /* pupil */
        ili9341_fill_circle(cx - 30, cy - 4, 3, CLR_SUB); /* highlight */

        /* Right eye — closed (eyelid covers the circle) */
        ili9341_fill_circle(cx + 34, cy, 22, CLR_SURFACE);
        ili9341_draw_circle(cx + 34, cy, 22, CLR_PURPLE_LT);
        ili9341_draw_circle(cx + 34, cy, 21, CLR_PURPLE_LT);
        /* Eyelid fill — covers top half */
        for (int dy = -22; dy <= 2; dy++) {
            int half_w = (int)sqrtf((float)(22 * 22 - dy * dy));
            ili9341_draw_hline(cx + 34 - half_w, cy + dy, half_w * 2, CLR_PURPLE);
        }
        /* Eyelid border line */
        ili9341_draw_hline(cx + 12, cy + 2, 44, CLR_PURPLE_LT);
        /* Lower lashes */
        for (int i = 0; i < 5; i++) {
            int lx = cx + 14 + i * 9;
            ili9341_draw_line(lx, cy + 4, lx - 2, cy + 10, CLR_PURPLE_LT);
        }

        /* Nose bridge dots */
        ili9341_fill_circle(cx - 3,  cy + 10, 2, CLR_SUB);
        ili9341_fill_circle(cx + 3,  cy + 10, 2, CLR_SUB);

        /* Label */
        ili9341_set_text_color(CLR_WARNING);
        ili9341_set_text_size(2);
        ili9341_set_cursor(88, 157);
        ili9341_print("PLEASE BLINK");
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_text_size(1);
        ili9341_set_cursor(74, 179);
        ili9341_print("Blink naturally - it only takes a second");

    } else if (is_smile) {
        /* Smiley face */
        ili9341_fill_circle(cx, cy, 45, CLR_SURFACE);
        ili9341_draw_circle(cx, cy, 45, CLR_WARNING);
        ili9341_draw_circle(cx, cy, 44, CLR_WARNING);

        /* Eyes */
        ili9341_fill_circle(cx - 16, cy - 14, 6, CLR_INK);
        ili9341_fill_circle(cx + 16, cy - 14, 6, CLR_INK);

        /* Smile arc — approximated with 5 line segments */
        /* Arc from (-28, +12) to (+28, +12) through (0, +32) */
        int sx[6] = {-28, -18, -8,  8,  18, 28};
        int sy[6] = { 12,  24, 30, 30,  24, 12};
        for (int i = 0; i < 5; i++) {
            ili9341_draw_line(cx + sx[i],   cy + sy[i],
                              cx + sx[i+1], cy + sy[i+1], CLR_WARNING);
            ili9341_draw_line(cx + sx[i],   cy + sy[i] + 1,
                              cx + sx[i+1], cy + sy[i+1] + 1, CLR_WARNING);
        }
        /* Teeth */
        ili9341_fill_rect(cx - 16, cy + 16, 32, 10, CLR_INK);

        /* Label */
        ili9341_set_text_color(CLR_WARNING);
        ili9341_set_text_size(2);
        ili9341_set_cursor(96, 157);
        ili9341_print("PLEASE SMILE");
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_text_size(1);
        ili9341_set_cursor(88, 179);
        ili9341_print("Show your teeth for the camera");

    } else if (is_turn_left || is_turn_right) {
        /* Head silhouette + directional arrow */

        /* Head oval */
        ili9341_fill_circle(cx, cy - 6, 32, CLR_SURFACE);
        ili9341_draw_circle(cx, cy - 6, 32, CLR_PURPLE_LT);
        ili9341_draw_circle(cx, cy - 6, 31, CLR_PURPLE_LT);

        /* Neck */
        ili9341_fill_rect(cx - 10, cy + 26, 20, 14, CLR_SURFACE);
        ili9341_draw_rect(cx - 10, cy + 26, 20, 14, CLR_PURPLE_LT);

        /* Eye on the visible side */
        int eye_x = is_turn_left ? cx + 8 : cx - 8;
        ili9341_fill_circle(eye_x, cy - 10, 5, CLR_INK);

        /* Large arrow — left or right */
        if (is_turn_left) {
            /* Arrow body */
            ili9341_fill_rect(cx - 95, cy - 5, 38, 10, CLR_SUCCESS);
            /* Arrowhead */
            for (int i = 0; i < 18; i++) {
                ili9341_draw_hline(cx - 95 - i, cy - i, i * 2, CLR_SUCCESS);
            }
        } else {
            /* Arrow body */
            ili9341_fill_rect(cx + 57, cy - 5, 38, 10, CLR_SUCCESS);
            /* Arrowhead */
            for (int i = 0; i < 18; i++) {
                ili9341_draw_hline(cx + 95 - i + 1, cy - i, i * 2, CLR_SUCCESS);
            }
        }

        /* Label */
        ili9341_set_text_color(CLR_SUCCESS);
        ili9341_set_text_size(2);
        if (is_turn_left) {
            ili9341_set_cursor(64, 157);
            ili9341_print("TURN HEAD LEFT");
        } else {
            ili9341_set_cursor(56, 157);
            ili9341_print("TURN HEAD RIGHT");
        }
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_text_size(1);
        ili9341_set_cursor(82, 179);
        ili9341_print("Slowly turn until the check passes");

    } else {
        /* Unknown challenge — generic fallback */
        ili9341_fill_circle(cx, cy, 40, CLR_SURFACE);
        ili9341_draw_circle(cx, cy, 40, CLR_PURPLE_LT);
        /* Simple eye icon */
        ili9341_fill_circle(cx - 12, cy - 5, 5, CLR_INK);
        ili9341_fill_circle(cx + 12, cy - 5, 5, CLR_INK);
        ili9341_draw_line(cx - 20, cy + 10, cx + 20, cy + 10, CLR_PURPLE_LT);

        ili9341_set_text_color(CLR_PURPLE_LT);
        ili9341_set_text_size(2);
        ili9341_set_cursor(88, 157);
        ili9341_print("LOOK AT CAM");
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_text_size(1);
        ili9341_set_cursor(68, 179);
        ili9341_print("Hold still and face the camera");
    }

    /* ── Progress hint bar ─────────────────────────────────────────────── */
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(1);
    ili9341_set_cursor(76, 200);
    ili9341_print("Evaluating");

    /* Animated dots placeholder (three static dots) */
    ili9341_fill_circle(168, 204, 3, CLR_PURPLE);
    ili9341_fill_circle(178, 204, 3, CLR_PURPLE_DIM);
    ili9341_fill_circle(188, 204, 3, CLR_PURPLE_DIM);

    /* ── Step dots (Card › PIN › Finger › Face › Vote) ─────────────────── */
    ui_draw_step_dots(5, 3, 160, 220);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(10,  232); ili9341_print("Card");
    ili9341_set_cursor(62,  232); ili9341_print("PIN");
    ili9341_set_cursor(106, 232); ili9341_print("Finger");
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_cursor(163, 232); ili9341_print("Face");
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(213, 232); ili9341_print("Vote");
}

// ── Voting interface ──────────────────────────────────────

void ui_display_voting_interface(void) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("CAST YOUR VOTE");

    ui_draw_card(10, 34, 300, 118, CLR_SURFACE, CLR_PURPLE);
    ili9341_fill_round_rect(10, 34, 6, 118, 3, CLR_PURPLE_LT);

    char badge[16];
    snprintf(badge, sizeof(badge), "#%d", g_nav.currentSelection + 1);
    ui_draw_card(22, 42, 36, 22, CLR_PURPLE_DIM, CLR_PURPLE);
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(1);
    ili9341_set_cursor(27, 49);
    ili9341_print(badge);

    // Candidate name (truncate to 14 chars)
    char name[32];
    const std::string &cname = g_candidates[g_nav.currentSelection].name;
    if (cname.length() > 14) {
        snprintf(name, sizeof(name), "%.13s.", cname.c_str());
    } else {
        snprintf(name, sizeof(name), "%s", cname.c_str());
    }
    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(2);
    ili9341_set_cursor(66, 44);
    ili9341_print(name);

    ili9341_set_text_color(CLR_PURPLE_LT); ili9341_set_text_size(1);
    ili9341_set_cursor(22, 72);  ili9341_print("Party:    ");
    ili9341_set_text_color(CLR_SUB);
    ili9341_print(g_candidates[g_nav.currentSelection].party.c_str());

    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_cursor(22, 87); ili9341_print("Position: ");
    ili9341_set_text_color(CLR_SUB);
    ili9341_print(g_candidates[g_nav.currentSelection].position.c_str());

    ili9341_set_text_color(CLR_SUB); ili9341_set_text_size(1);
    ili9341_set_cursor(22, 106); ili9341_print("Candidate ");
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_print_int(g_nav.currentSelection + 1);
    ili9341_set_text_color(CLR_SUB);
    ili9341_print(" of ");
    ili9341_print_int(g_candidateCount);

    ui_draw_card(10, 158, 300, 30, CLR_SURFACE, CLR_BORDER);
    ili9341_set_text_color(CLR_SUB); ili9341_set_text_size(1);
    ili9341_set_cursor(18, 167); ili9341_print("[UP]/[DOWN] Navigate");
    ili9341_set_cursor(175, 167); ili9341_print("[OK] Vote");

    ili9341_fill_rect(10, 194, 300, 2, CLR_PURPLE_DIM);
    ili9341_set_text_color(CLR_SUB); ili9341_set_text_size(1);

    char title[40];
    const std::string &et = g_electionCfg.electionTitle;
    if (et.length() > 36) {
        snprintf(title, sizeof(title), "%.35s.", et.c_str());
    } else {
        snprintf(title, sizeof(title), "%s", et.c_str());
    }
    ili9341_set_cursor(10, 200); ili9341_print(title);

    // Navigation arrows
    if (g_nav.currentSelection > 0)
        ili9341_fill_triangle(285, 55, 278, 68, 292, 68, CLR_PURPLE);
    if (g_nav.currentSelection < g_candidateCount - 1)
        ili9341_fill_triangle(285, 128, 278, 115, 292, 115, CLR_PURPLE);
}

// ── Vote confirmation ─────────────────────────────────────

void ui_display_vote_confirmation(void) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("CONFIRM VOTE");

    ui_draw_card(10, 34, 300, 28, 0xA000, CLR_WARNING);
    ili9341_set_text_color(CLR_WARNING); ili9341_set_text_size(1);
    ili9341_set_cursor(22, 43); ili9341_print("This action CANNOT be undone.");

    ui_draw_card(10, 70, 300, 80, CLR_SURFACE, CLR_PURPLE);
    ili9341_fill_round_rect(10, 70, 6, 80, 3, CLR_PURPLE);

    ili9341_set_text_color(CLR_SUB); ili9341_set_text_size(1);
    ili9341_set_cursor(24, 78); ili9341_print("You are voting for:");

    char name[32];
    const std::string &cname = g_candidates[g_nav.currentSelection].name;
    if (cname.length() > 14) {
        snprintf(name, sizeof(name), "%.13s.", cname.c_str());
    } else {
        snprintf(name, sizeof(name), "%s", cname.c_str());
    }
    ili9341_set_text_color(CLR_INK); ili9341_set_text_size(2);
    ili9341_set_cursor(24, 92); ili9341_print(name);

    ili9341_set_text_color(CLR_PURPLE_LT); ili9341_set_text_size(1);
    ili9341_set_cursor(24, 116);
    ili9341_print(g_candidates[g_nav.currentSelection].party.c_str());
    ili9341_print("  -  ");
    ili9341_print(g_candidates[g_nav.currentSelection].position.c_str());

    ui_draw_card(10,  160, 140, 38, CLR_SURFACE, CLR_DANGER);
    ili9341_set_text_color(CLR_DANGER); ili9341_set_text_size(2);
    ili9341_set_cursor(36, 172); ili9341_print("BACK");

    ui_draw_card(170, 160, 140, 38, CLR_PURPLE_DIM, CLR_PURPLE);
    ili9341_set_text_color(CLR_INK); ili9341_set_text_size(2);
    ili9341_set_cursor(186, 172); ili9341_print("CONFIRM");
}

// ── Vote success ──────────────────────────────────────────

void ui_display_vote_success(const char *receipt_code) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("VOTE RECORDED");

    int cx = 160, cy = 100;
    ili9341_fill_circle(cx, cy, 44, CLR_PURPLE_DIM);
    ili9341_draw_circle(cx, cy, 44, CLR_SUCCESS);
    ili9341_draw_circle(cx, cy, 42, CLR_SUCCESS);
    ili9341_draw_line(cx - 18, cy + 4,  cx - 4,  cy + 20, CLR_SUCCESS);
    ili9341_draw_line(cx - 17, cy + 4,  cx - 3,  cy + 20, CLR_SUCCESS);
    ili9341_draw_line(cx - 4,  cy + 20, cx + 24, cy - 14, CLR_SUCCESS);
    ili9341_draw_line(cx - 3,  cy + 20, cx + 25, cy - 14, CLR_SUCCESS);

    ili9341_set_text_color(CLR_SUCCESS); ili9341_set_text_size(1);
    ili9341_set_cursor(104, 154); ili9341_print("Vote submitted!");

    ui_draw_card(20, 166, 280, 34, CLR_SURFACE, CLR_BORDER);
    ili9341_set_text_color(CLR_SUB); ili9341_set_text_size(1);
    ili9341_set_cursor(30, 173); ili9341_print("Receipt:");
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_cursor(30, 185);
    char receipt[36];
    snprintf(receipt, sizeof(receipt), "%.32s", receipt_code);
    ili9341_print(receipt);

    ili9341_set_text_color(CLR_SUB); ili9341_set_text_size(1);
    ili9341_set_cursor(68, 206); ili9341_print("Thank you for voting!");

    vTaskDelay(pdMS_TO_TICKS(4000));
}

// ── Status / error ────────────────────────────────────────
void ui_display_status(const char *msg) {
    ili9341_fill_screen(CLR_BG);
    ili9341_set_text_size(2);
    ili9341_set_text_color(CLR_INK);

    int y = 100; // Starting Y coordinate for center screen
    char temp[128];
    strncpy(temp, msg, sizeof(temp)-1);
    temp[127] = '\0';

    // Split the string at every newline character
    char *line = strtok(temp, "\n");
    while (line != NULL) {
        int tw = strlen(line) * 12; // 12 pixels per character at size 2
        int x = (320 - tw) / 2;
        if (x < 0) x = 0; // CRITICAL FIX: Prevent negative-X clipping!

        ili9341_set_cursor(x, y);
        ili9341_print(line);

        y += 24; // Move down a row for the next line
        line = strtok(NULL, "\n");
    }
}

void ui_display_error(const char *msg) {
    ili9341_fill_screen(CLR_DANGER); // Red background for errors
    ili9341_set_text_size(2);
    ili9341_set_text_color(0xFFFF);  // White text

    int y = 100;
    char temp[128];
    strncpy(temp, msg, sizeof(temp)-1);
    temp[127] = '\0';

    char *line = strtok(temp, "\n");
    while (line != NULL) {
        int tw = strlen(line) * 12;
        int x = (320 - tw) / 2;
        if (x < 0) x = 0;

        ili9341_set_cursor(x, y);
        ili9341_print(line);

        y += 24;
        line = strtok(NULL, "\n");
    }
}

// ── Enrollment screens ────────────────────────────────────

void ui_display_enroll_idle(void) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("ENROLL VOTER", CLR_PURPLE);

    ui_draw_card(20, 38, 280, 90, CLR_SURFACE, CLR_PURPLE);

    // NFC card icon
    ili9341_fill_round_rect(110, 50, 100, 64, 8, CLR_PURPLE_DIM);
    ili9341_draw_round_rect(110, 50, 100, 64, 8, CLR_PURPLE);
    for (int r = 10; r <= 28; r += 9)
        ili9341_draw_circle(210, 82, r, CLR_PURPLE_LT);
    ili9341_fill_round_rect(124, 66, 22, 16, 3, CLR_PURPLE);

    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(2);
    ili9341_set_cursor(52, 140);
    ili9341_print("Tap Blank Card");

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(70, 165);
    ili9341_print("MAL Enrollment Terminal");

    ui_draw_step_dots(4, 0, 160, 210);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(18,  225); ili9341_print("Card");
    ili9341_set_cursor(80,  225); ili9341_print("PIN");
    ili9341_set_cursor(148, 225); ili9341_print("Finger");
    ili9341_set_cursor(222, 225); ili9341_print("Write");
}

void ui_display_enroll_pin_set(bool confirm_mode) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header(confirm_mode ? "CONFIRM PIN" : "SET VOTER PIN", CLR_PURPLE);

    ui_draw_card(20, 38, 280, 60, CLR_SURFACE, CLR_PURPLE);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(30, 48);
    if (confirm_mode) {
        ili9341_print("Re-enter the same PIN");
        ili9341_set_cursor(30, 62);
        ili9341_print("to confirm.");
    } else {
        ili9341_print("Voter: choose your 4-digit PIN.");
        ili9341_set_cursor(30, 62);
        ili9341_print("You will need this to vote.");
    }

    // 4 empty dots
    for (int i = 0; i < 4; i++) {
        int x = 80 + i * 42;
        ili9341_fill_round_rect(x, 108, 30, 36, 6, CLR_SURFACE);
        ili9341_draw_round_rect(x, 108, 30, 36, 6, CLR_BORDER);
    }

    ui_draw_card(20, 158, 280, 46, CLR_SURFACE, CLR_BORDER);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(30, 166); ili9341_print("[UP]/[DOWN]  Change digit");
    ili9341_set_cursor(30, 180); ili9341_print("[RIGHT]      Confirm digit");
    ili9341_set_cursor(30, 194); ili9341_print("[BACK]       Delete / Cancel");

    ui_draw_step_dots(4, 1, 160, 210);

    // Digit selector
    ui_draw_card(130, 215, 60, 20, CLR_PURPLE_DIM, CLR_PURPLE);
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(2);
    ili9341_set_cursor(149, 220);
    ili9341_print("0");
}

void ui_display_enroll_digit(int digit) {
    // Update selector widget only — no full redraw
    ili9341_fill_rect(130, 215, 60, 20, CLR_BG);
    ui_draw_card(130, 215, 60, 20, CLR_PURPLE_DIM, CLR_PURPLE);
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_text_size(2);
    ili9341_set_cursor(149, 220);
    ili9341_print_int(digit);
}

void ui_display_enroll_pin_progress(int dots_filled) {
    // Redraw the 4 dot boxes with filled/empty state
    for (int i = 0; i < 4; i++) {
        int x = 80 + i * 42;
        bool filled = (i < dots_filled);
        ili9341_fill_round_rect(x, 108, 30, 36, 6,
                                filled ? CLR_PURPLE : CLR_SURFACE);
        ili9341_draw_round_rect(x, 108, 30, 36, 6,
                                filled ? CLR_PURPLE_LT : CLR_BORDER);
        if (filled) {
            ili9341_set_text_color(CLR_INK);
            ili9341_set_text_size(3);
            ili9341_set_cursor(x + 9, 114);
            ili9341_print("*");
        }
    }
}

void ui_display_enroll_fingerprint(bool captured) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header(captured ? "FINGERPRINT OK" : "SCAN FINGERPRINT", CLR_PURPLE);

    ui_draw_card(20, 38, 280, 46, CLR_SURFACE, CLR_PURPLE);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(30, 48);
    if (captured) {
        ili9341_print("Fingerprint captured.");
        ili9341_set_cursor(30, 62);
        ili9341_print("Writing to card...");
    } else {
        ili9341_print("Voter: place right index finger");
        ili9341_set_cursor(30, 62);
        ili9341_print("flat on the sensor.");
    }

    uint16_t ring_color = captured ? CLR_SUCCESS : CLR_PURPLE;
    int cx = 160, cy = 128;
    ili9341_fill_circle(cx, cy, 38, CLR_SURFACE);
    for (int r = 8; r <= 38; r += 8) ili9341_draw_circle(cx, cy, r, ring_color);
    ili9341_fill_circle(cx, cy, 4, ring_color);

    if (captured) {
        ili9341_draw_line(cx-14, cy+2,  cx-4,  cy+14, CLR_INK);
        ili9341_draw_line(cx-14, cy+3,  cx-4,  cy+15, CLR_INK);
        ili9341_draw_line(cx-4,  cy+14, cx+16, cy-8,  CLR_INK);
        ili9341_draw_line(cx-4,  cy+15, cx+16, cy-7,  CLR_INK);
    }

    ui_draw_step_dots(4, 2, 160, 210);
}

void ui_display_enroll_success(const char *enrollment_id) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("ENROLLMENT DONE", CLR_SUCCESS);

    int cx = 160, cy = 100;
    ili9341_fill_circle(cx, cy, 44, CLR_PURPLE_DIM);
    ili9341_draw_circle(cx, cy, 44, CLR_SUCCESS);
    ili9341_draw_circle(cx, cy, 42, CLR_SUCCESS);
    ili9341_draw_line(cx-18, cy+4,  cx-4,  cy+20, CLR_SUCCESS);
    ili9341_draw_line(cx-17, cy+4,  cx-3,  cy+20, CLR_SUCCESS);
    ili9341_draw_line(cx-4,  cy+20, cx+24, cy-14, CLR_SUCCESS);
    ili9341_draw_line(cx-3,  cy+20, cx+25, cy-14, CLR_SUCCESS);

    ili9341_set_text_color(CLR_SUCCESS);
    ili9341_set_text_size(1);
    ili9341_set_cursor(100, 154);
    ili9341_print("Card personalized!");

    ui_draw_card(20, 166, 280, 34, CLR_SURFACE, CLR_BORDER);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(30, 173); ili9341_print("Enroll ID:");
    ili9341_set_text_color(CLR_PURPLE_LT);
    ili9341_set_cursor(30, 185);
    char id_short[20];
    snprintf(id_short, sizeof(id_short), "%.18s", enrollment_id);
    ili9341_print(id_short);

    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(62, 208);
    ili9341_print("Remove card when ready.");
}

void ui_display_enroll_liveness(bool captured) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header(captured ? "FACE CAPTURED" : "FACE SCAN", CLR_PURPLE);

    ui_draw_card(20, 38, 280, 46, CLR_SURFACE, CLR_PURPLE);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(30, 48);
    if (captured) {
        ili9341_print("Face reference captured.");
        ili9341_set_cursor(30, 62);
        ili9341_print("Storing to card...");
    } else {
        ili9341_print("Look directly at the camera.");
        ili9341_set_cursor(30, 62);
        ili9341_print("Hold still for 3 seconds.");
    }

    uint16_t fc = captured ? CLR_SUCCESS : CLR_PURPLE;
    ui_draw_face_guide(100, 130, fc, captured);

    // Step indicator: step 3 of 4
    ui_draw_step_dots(4, 2, 160, 210);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_text_size(1);
    ili9341_set_cursor(18,  225); ili9341_print("Card");
    ili9341_set_cursor(80,  225); ili9341_print("PIN");
    ili9341_set_text_color(captured ? CLR_SUCCESS : CLR_PURPLE_LT);
    ili9341_set_cursor(148, 225); ili9341_print("Face");
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(222, 225); ili9341_print("Write");
}

// ═══════════════════════════════════════════════════════════════════════════
//  HOME DASHBOARD
// ═══════════════════════════════════════════════════════════════════════════
// Layout (320×240 landscape):
//
//  y=  0 ┌─────────── STATUS BAR ────────────┐  h=24  CLR_PURPLE bg
//  y= 24 ├─────────── ELECTION BAND ─────────┤  h=38  CLR_SURFACE bg
//  y= 62 │  [0] ► CAST VOTE                  │  h=35  first menu item
//  y= 97 │  [1]   ENROLL VOTER               │  h=35
//  y=132 │  [2]   SETTINGS                   │  h=35
//  y=167 │  [3]   ABOUT                      │  h=35
//  y=202 └─────────── FOOTER HINT ───────────┘  h=38
// ═══════════════════════════════════════════════════════════════════════════

#include "session.h"       // g_electionCfg, terminal_key_get_pubkey_b64
#include "terminal_key.h"
#include "network.h"       // read_battery_percent
#include <time.h>
#include "esp_timer.h"
#include "esp_wifi.h"

// ── Internal drawing helpers ──────────────────────────────────────────────

// Draw a WiFi signal icon at (x,y).  strength: 0=none 1=low 2=mid 3=full
static void draw_wifi_icon(int x, int y, int strength) {
    // 4 vertical bars of increasing height (like a phone signal meter)
    // Bar widths: 3px each, gap: 2px, total: 4*3 + 3*2 = 18px
    // Heights: 4, 7, 10, 13 px (from left to right)
    const int heights[4] = {4, 7, 10, 13};
    for (int i = 0; i < 4; i++) {
        uint16_t c = (i < strength) ? CLR_SUCCESS : CLR_BORDER;
        int bx = x + i * 5;
        int bh = heights[i];
        int by = y + 13 - bh;
        ili9341_fill_rect(bx, by, 3, bh, c);
    }
}

// Draw a battery icon at (x,y).  pct: 0-100
static void draw_battery_icon(int x, int y, int pct) {
    // Body: 22×10, nub: 3×6 on right
    ili9341_draw_rect(x, y, 22, 10, CLR_BORDER);
    ili9341_fill_rect(x + 23, y + 2, 3, 6, CLR_BORDER);   // nub

    // Fill proportional to pct
    int fill = (pct * 20) / 100;  // max inner width = 20
    if (fill < 0) fill = 0;
    if (fill > 20) fill = 20;
    uint16_t fc = (pct > 25) ? CLR_SUCCESS : CLR_DANGER;
    if (fill > 0)
        ili9341_fill_rect(x + 1, y + 1, fill, 8, fc);
    // Clear unfilled portion
    if (fill < 20)
        ili9341_fill_rect(x + 1 + fill, y + 1, 20 - fill, 8, CLR_SURFACE);
}

// Small bold label for menu item icon area (left side)
// Draws a tiny symbolic icon at (ix, iy) — icon is 20×20
static void draw_menu_icon(int ix, int iy, int item) {
    switch (item) {
        case HOME_VOTE:
            // Ballot box: rectangle with X inside
            ili9341_draw_round_rect(ix, iy + 2, 20, 16, 3, CLR_PURPLE_LT);
            // Ballot slot (top slit)
            ili9341_fill_rect(ix + 6, iy + 2, 8, 2, CLR_PURPLE_LT);
            // Check mark inside
            ili9341_draw_line(ix+4,  iy+9,  ix+8,  iy+13, CLR_PURPLE_LT);
            ili9341_draw_line(ix+8,  iy+13, ix+16, iy+5,  CLR_PURPLE_LT);
            break;

        case HOME_ENROLL:
            // Credit card outline with a "+" centre
            ili9341_draw_round_rect(ix, iy + 3, 20, 13, 2, CLR_PURPLE_LT);
            // Magnetic stripe
            ili9341_fill_rect(ix, iy + 5, 20, 3, CLR_PURPLE_DIM);
            // "+" person indicator (top-right circle)
            ili9341_draw_circle(ix + 15, iy + 12, 4, CLR_PURPLE_LT);
            ili9341_draw_hline(ix + 13, iy + 12, 5, CLR_PURPLE_LT);
            ili9341_draw_vline(ix + 15, iy + 10, 5, CLR_PURPLE_LT);
            break;

        case HOME_SETTINGS:
            // Three horizontal lines (hamburger / gear substitute)
            for (int r = 0; r < 3; r++) {
                int ly = iy + 5 + r * 5;
                ili9341_fill_round_rect(ix + 2, ly, 16, 2, 1, CLR_PURPLE_LT);
            }
            // Small circle on middle line (slider knob)
            ili9341_fill_circle(ix + 14, iy + 10, 3, CLR_PURPLE);
            ili9341_draw_circle(ix + 14, iy + 10, 3, CLR_PURPLE_LT);
            break;

        case HOME_ABOUT:
            // Circle with "i"
            ili9341_draw_circle(ix + 10, iy + 9, 9, CLR_PURPLE_LT);
            // "i" dot
            ili9341_fill_circle(ix + 10, iy + 3, 2, CLR_PURPLE_LT);
            // "i" stem
            ili9341_draw_vline(ix + 10, iy + 7, 7, CLR_PURPLE_LT);
            ili9341_draw_hline(ix + 8,  iy + 7, 5, CLR_PURPLE_LT);
            ili9341_draw_hline(ix + 8,  iy + 14, 5, CLR_PURPLE_LT);
            break;
    }
}

// Draw one full menu row. selected=true → highlighted purple card.
static void draw_home_item(int idx, bool selected) {
    int iy = HOME_MENU_Y + idx * HOME_ITEM_H;

    // Background
    if (selected) {
        ili9341_fill_rect(0, iy, 320, HOME_ITEM_H, CLR_PURPLE_DIM);
        // Left accent bar
        ili9341_fill_rect(0, iy, 4, HOME_ITEM_H, CLR_PURPLE);
        // Right arrow indicator
        int ax = 304, ay = iy + HOME_ITEM_H / 2;
        ili9341_fill_triangle(ax, ay - 5, ax, ay + 5, ax + 8, ay, CLR_PURPLE);
    } else {
        ili9341_fill_rect(0, iy, 320, HOME_ITEM_H, CLR_BG);
        // Subtle separator line
        ili9341_draw_hline(8, iy, 304, CLR_BORDER);
    }

    // Icon (20×20 at x=12, vertically centred)
    draw_menu_icon(12, iy + (HOME_ITEM_H - 20) / 2, idx);

    // Title (size 2 = 12px wide chars)
    const char *titles[] = { "CAST VOTE", "ENROLL VOTER", "SETTINGS", "ABOUT" };
    const char *subs[]   = {
        "Tap smart card to begin",
        "Register a new voter card",
        "Terminal configuration",
        "Diagnostics & system info"
    };

    ili9341_set_text_size(2);
    ili9341_set_text_color(selected ? CLR_INK : CLR_SUB);
    ili9341_set_cursor(40, iy + 5);
    ili9341_print(titles[idx]);

    ili9341_set_text_size(1);
    ili9341_set_text_color(selected ? CLR_PURPLE_LT : CLR_BORDER);
    ili9341_set_cursor(40, iy + 23);
    ili9341_print(subs[idx]);
}

// Draw the election info band (y=24, h=38)
// Exposed as ui_home_update_election_band() in tft_ui.h —
// called from main.cpp whenever g_electionCfg is updated after
// the background config task completes.
void draw_election_band(void) {
    ili9341_fill_rect(0, HOME_STATUS_H, 320, HOME_BAND_H, CLR_SURFACE);
    ili9341_draw_hline(0, HOME_STATUS_H,          320, CLR_BORDER);
    ili9341_draw_hline(0, HOME_STATUS_H + HOME_BAND_H - 1, 320, CLR_BORDER);

    // Election title — truncated to 32 chars
    char title[36];
    const std::string &et = g_electionCfg.loaded
                                ? g_electionCfg.electionTitle
                                : "Waiting for election config...";
    snprintf(title, sizeof(title), "%.32s", et.c_str());

    ili9341_set_text_size(1);
    ili9341_set_text_color(CLR_INK);
    ili9341_set_cursor(8, HOME_STATUS_H + 6);
    ili9341_print(title);

    // Second line: closing time + polling unit
    char sub[48];
    if (g_electionCfg.loaded) {
        // Parse hour:minute from ISO-8601 closingTime (e.g. "2027-03-15T18:00:00Z")
        const char *ct = g_electionCfg.closingTime.c_str();
        const char *tpos = strchr(ct, 'T');
        if (tpos && strlen(tpos) >= 6) {
            snprintf(sub, sizeof(sub), "Closes %.5s  |  Unit %03d / 774",
                     tpos + 1, g_electionCfg.pollingUnitId);
        } else {
            snprintf(sub, sizeof(sub), "Unit %03d / 774",
                     g_electionCfg.pollingUnitId);
        }
    } else {
        snprintf(sub, sizeof(sub), "Not connected to backend");
    }
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(8, HOME_STATUS_H + 22);
    ili9341_print(sub);
}

// ── Public homepage functions ─────────────────────────────────────────────

void ui_home_update_status(bool wifi_connected, int battery_pct) {
    ili9341_fill_rect(0, 0, 320, HOME_STATUS_H, CLR_PURPLE);

    // Clock (left side)
    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    char clk[8];
    snprintf(clk, sizeof(clk), "%02d:%02d", t->tm_hour, t->tm_min);
    ili9341_set_text_color(CLR_INK);
    ili9341_set_text_size(1);
    ili9341_set_cursor(6, 8);
    ili9341_print(clk);

    // Centre title
    const char *centre = "MFA E-VOTING";
    int tw = (int)strlen(centre) * 6;
    ili9341_set_cursor((320 - tw) / 2, 8);
    ili9341_print(centre);

    // WiFi icon (right, x=252)
    int wifi_str = wifi_connected ? 4 : 0;
    draw_wifi_icon(252, 5, wifi_str);

    // Battery icon (right of wifi, x=278)
    draw_battery_icon(278, 7, battery_pct);

    // Battery % text below icon
    char bpct[6];
    snprintf(bpct, sizeof(bpct), "%d%%", battery_pct);
    // Overlay compact — fits inside icon row at y=8 if text size=1
    // Actually just draw it; the icon is 10px tall and we have 24px bar
}

void ui_display_home(int selected_item) {
    ili9341_fill_screen(CLR_BG);

    // Status bar (will be drawn by ui_home_update_status)
    bool wifi_ok = false;
    wifi_ap_record_t ap;
    if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) wifi_ok = true;
    ui_home_update_status(wifi_ok, read_battery_percent());

    // Election band
    draw_election_band();

    // Menu items
    for (int i = 0; i < HOME_ITEM_COUNT; i++)
        draw_home_item(i, i == selected_item);

    // Footer
    ili9341_fill_rect(0, HOME_FOOTER_Y, 320, 240 - HOME_FOOTER_Y, CLR_SURFACE);
    ili9341_draw_hline(0, HOME_FOOTER_Y, 320, CLR_BORDER);
    ili9341_set_text_size(1);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(8, HOME_FOOTER_Y + 8);
    ili9341_print("[UP][DOWN] Navigate");
    ili9341_set_cursor(8, HOME_FOOTER_Y + 22);
    ili9341_print("[OK] Select   [BACK] Sleep");

    // Liveness mode badge (bottom-right corner of footer)
    const char *lm = (g_electionCfg.livenessMode == "ACTIVE") ? "AI:ACTIVE" : "AI:PASSIVE";
    uint16_t lc    = (g_electionCfg.livenessMode == "ACTIVE") ? CLR_SUCCESS : CLR_PURPLE_LT;
    ili9341_set_text_color(lc);
    int lmw = (int)strlen(lm) * 6;
    ili9341_set_cursor(320 - lmw - 6, HOME_FOOTER_Y + 22);
    ili9341_print(lm);
}

void ui_home_move_selection(int old_item, int new_item) {
    if (old_item >= 0 && old_item < HOME_ITEM_COUNT)
        draw_home_item(old_item, false);
    if (new_item >= 0 && new_item < HOME_ITEM_COUNT)
        draw_home_item(new_item, true);
}

// ═══════════════════════════════════════════════════════════════════════════
//  SETTINGS SCREEN
// ═══════════════════════════════════════════════════════════════════════════
// Layout (320×240):
//  y=  0  Header bar (28px, CLR_PURPLE)
//  y= 28  5 items × 38px = 190px
//  y=218  Footer hint (22px)
// ═══════════════════════════════════════════════════════════════════════════

#define SETT_ITEM_H   38
#define SETT_MENU_Y   28

static const char *s_settings_titles[SETTINGS_ITEM_COUNT] = {
    "Liveness Mode",
    "Display",
    "Network",
    "Heartbeat",
    "Terminal Key",
};

// Returns a short value string for each settings item
static void settings_value_str(int idx, char *out, size_t out_len) {
    switch (idx) {
        case SETTINGS_LIVENESS:
            snprintf(out, out_len, "%s",
                     (g_electionCfg.livenessMode == "ACTIVE") ? "ACTIVE" : "PASSIVE");
            break;
        case SETTINGS_DISPLAY:
            snprintf(out, out_len, "Full brightness");
            break;
        case SETTINGS_NETWORK:
        {
            wifi_ap_record_t ap;
            snprintf(out, out_len, "%s",
                     (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) ? "Connected" : "Offline");
            break;
        }
        case SETTINGS_HEARTBEAT:
            snprintf(out, out_len, "Auto 60s");
            break;
        case SETTINGS_TERMKEY:
            snprintf(out, out_len, "P-256 ECDSA");
            break;
        default:
            snprintf(out, out_len, "--");
    }
}

static void draw_settings_item(int idx, bool selected) {
    int iy = SETT_MENU_Y + idx * SETT_ITEM_H;

    if (selected) {
        ili9341_fill_rect(0, iy, 320, SETT_ITEM_H, CLR_PURPLE_DIM);
        ili9341_fill_rect(0, iy, 4, SETT_ITEM_H, CLR_PURPLE);
    } else {
        ili9341_fill_rect(0, iy, 320, SETT_ITEM_H, CLR_BG);
        ili9341_draw_hline(8, iy, 304, CLR_BORDER);
    }

    // Title
    ili9341_set_text_size(1);
    ili9341_set_text_color(selected ? CLR_INK : CLR_SUB);
    ili9341_set_cursor(12, iy + 8);
    ili9341_print(s_settings_titles[idx]);

    // Value (right-aligned, size 1)
    char val[32];
    settings_value_str(idx, val, sizeof(val));
    int vw = (int)strlen(val) * 6;
    uint16_t vc = (idx == SETTINGS_LIVENESS && g_electionCfg.livenessMode == "ACTIVE")
                      ? CLR_SUCCESS : CLR_PURPLE_LT;
    ili9341_set_text_color(selected ? vc : CLR_BORDER);
    ili9341_set_cursor(320 - vw - 10, iy + 8);
    ili9341_print(val);

    // Sub-line for liveness: mode description
    if (idx == SETTINGS_LIVENESS) {
        const char *desc = (g_electionCfg.livenessMode == "ACTIVE")
            ? "MediaPipe challenge-response (wss)"
            : "MiniFASNet burst — 5 frames REST";
        ili9341_set_text_color(selected ? CLR_PURPLE_LT : CLR_BORDER);
        ili9341_set_cursor(12, iy + 22);
        ili9341_print(desc);
    } else if (idx == SETTINGS_TERMKEY) {
        // Show first 28 chars of public key
        const std::string &pk = terminal_key_get_pubkey_b64();
        char pkshort[30];
        snprintf(pkshort, sizeof(pkshort), "%.26s...", pk.c_str());
        ili9341_set_text_color(selected ? CLR_PURPLE_LT : CLR_BORDER);
        ili9341_set_cursor(12, iy + 22);
        ili9341_print(pkshort);
    } else if (idx == SETTINGS_NETWORK) {
        wifi_ap_record_t ap;
        if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {
            char netinfo[64];
            snprintf(netinfo, sizeof(netinfo), "RSSI %d dBm  ·  SSID: %.12s",
                     ap.rssi, (char *)ap.ssid);
            ili9341_set_text_color(selected ? CLR_PURPLE_LT : CLR_BORDER);
            ili9341_set_cursor(12, iy + 22);
            ili9341_print(netinfo);
        }
    }

    // Right chevron on selected
    if (selected) {
        int ax = 306, ay = iy + SETT_ITEM_H / 2;
        ili9341_fill_triangle(ax - 6, ay - 5, ax - 6, ay + 5, ax, ay, CLR_PURPLE);
    }
}

void ui_display_settings(int selected_item) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("SETTINGS");

    for (int i = 0; i < SETTINGS_ITEM_COUNT; i++)
        draw_settings_item(i, i == selected_item);

    // Footer
    int fy = SETT_MENU_Y + SETTINGS_ITEM_COUNT * SETT_ITEM_H;
    ili9341_fill_rect(0, fy, 320, 240 - fy, CLR_SURFACE);
    ili9341_draw_hline(0, fy, 320, CLR_BORDER);
    ili9341_set_text_size(1);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(8, fy + 8);
    ili9341_print("[UP][DOWN] Navigate   [OK] Toggle   [BACK] Home");
    // Note: liveness toggle changes g_electionCfg.livenessMode locally.
    // Backend is authoritative — change persists until next config fetch.
}

void ui_settings_move(int old_item, int new_item) {
    if (old_item >= 0 && old_item < SETTINGS_ITEM_COUNT)
        draw_settings_item(old_item, false);
    if (new_item >= 0 && new_item < SETTINGS_ITEM_COUNT)
        draw_settings_item(new_item, true);
}

void ui_settings_update_liveness(const char *mode_str) {
    (void)mode_str;
    // Redraw just the liveness row with the new value
    bool is_selected = false;   // caller passes selection state if needed
    draw_settings_item(SETTINGS_LIVENESS, is_selected);
}

// ═══════════════════════════════════════════════════════════════════════════
//  ABOUT / DIAGNOSTICS SCREEN
// ═══════════════════════════════════════════════════════════════════════════

void ui_display_about(void) {
    ili9341_fill_screen(CLR_BG);
    ui_draw_header("ABOUT / DIAGNOSTICS");

    int y = 34;
    const int ROW = 18;

    auto row = [&](const char *label, const char *value, uint16_t vc = 0) {
        ili9341_set_text_size(1);
        ili9341_set_text_color(CLR_SUB);
        ili9341_set_cursor(8, y);
        ili9341_print(label);

        int lw = (int)strlen(label) * 6 + 8;
        ili9341_set_text_color(vc ? vc : CLR_INK);
        ili9341_set_cursor(lw, y);
        ili9341_print(value);
        y += ROW;
    };

    // Terminal ID
    row("Terminal : ", TERMINAL_ID);

    // Firmware version
    row("Firmware : ", "v5.1  (ESP-IDF)");

    // Election
    {
        char et[24];
        snprintf(et, sizeof(et), "%.22s", g_electionCfg.loaded
                     ? g_electionCfg.electionTitle.c_str() : "Not loaded");
        row("Election : ", et, g_electionCfg.loaded ? CLR_INK : CLR_WARNING);
    }

    // Liveness mode
    {
        const char *lm = (g_electionCfg.livenessMode == "ACTIVE")
                             ? "ACTIVE (MediaPipe)" : "PASSIVE (MiniFASNet)";
        uint16_t lc = (g_electionCfg.livenessMode == "ACTIVE")
                          ? CLR_SUCCESS : CLR_PURPLE_LT;
        row("Liveness : ", lm, lc);
    }

    // Battery
    {
        int pct = read_battery_percent();
        char bstr[10];
        snprintf(bstr, sizeof(bstr), "%d %%", pct);
        uint16_t bc = (pct > 25) ? CLR_SUCCESS : CLR_DANGER;
        row("Battery  : ", bstr, bc);
    }

    // WiFi
    {
        wifi_ap_record_t ap;
        if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {
            char winfo[28];
            snprintf(winfo, sizeof(winfo), "OK  RSSI %d  %.12s",
                     ap.rssi, (char *)ap.ssid);
            row("WiFi     : ", winfo, CLR_SUCCESS);
        } else {
            row("WiFi     : ", "OFFLINE", CLR_DANGER);
        }
    }

    // Uptime
    {
        uint64_t us = esp_timer_get_time();
        uint32_t s  = (uint32_t)(us / 1000000ULL);
        char upt[24];
        snprintf(upt, sizeof(upt), "%luh %02lum %02lus", s / 3600, (s % 3600) / 60, s % 60);
        row("Uptime   : ", upt);
    }

    // Polling unit
    if (g_electionCfg.loaded) {
        char pu[12];
        snprintf(pu, sizeof(pu), "%d / 774", g_electionCfg.pollingUnitId);
        row("Unit     : ", pu);
    }

    // Public key (first 32 chars of base64 SPKI)
    {
        const std::string &pk = terminal_key_get_pubkey_b64();
        char pkshort[36];
        snprintf(pkshort, sizeof(pkshort), "%.30s...", pk.c_str());
        row("Pub Key  : ", pkshort, CLR_PURPLE_LT);
    }

    // Separator and back hint
    ili9341_draw_hline(0, 220, 320, CLR_BORDER);
    ili9341_set_text_size(1);
    ili9341_set_text_color(CLR_SUB);
    ili9341_set_cursor(88, 228);
    ili9341_print("[BACK] Return to home");
}

// ═══════════════════════════════════════════════════════════════════════════
//  TAMPER ALERT OVERLAY
// ═══════════════════════════════════════════════════════════════════════════

void ui_display_tamper_alert(void) {
    ili9341_fill_screen(0xA000);  // dark red background

    // Bold warning symbol (triangle with !)
    int cx = 160, cy = 90;
    ili9341_fill_triangle(cx, cy - 50, cx - 44, cy + 30, cx + 44, cy + 30, 0xF800);
    ili9341_fill_triangle(cx, cy - 40, cx - 36, cy + 24, cx + 36, cy + 24, 0xA000);
    // "!" exclamation
    ili9341_fill_rect(cx - 4, cy - 24, 8, 26, 0xF800);
    ili9341_fill_circle(cx, cy + 14, 5, 0xF800);

    ili9341_set_text_color(0xFFFF);
    ili9341_set_text_size(2);
    ili9341_set_cursor(72, 136);
    ili9341_print("TAMPER DETECTED");

    ili9341_set_text_size(1);
    ili9341_set_cursor(48, 164);
    ili9341_print("Terminal enclosure opened!");

    ili9341_set_cursor(38, 182);
    ili9341_print("Session locked. Alert sent.");

    ili9341_set_text_color(0xF800);
    ili9341_set_text_size(2);
    ili9341_set_cursor(94, 208);
    ili9341_print("CONTACT MAL");
}

// ── Public election band refresh ──────────────────────────────────────────
// Call from main.cpp after g_electionCfg is populated by the background
// election config task.  Repaints only the 38-px election band without
// disturbing the status bar or menu items.
void ui_home_update_election_band(void) {
    draw_election_band();
}
