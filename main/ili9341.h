#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "driver/spi_master.h"

// ── RGB565 colour helpers ─────────────────────────────────
#define RGB565(r,g,b) ((uint16_t)(((r & 0xF8)<<8)|((g & 0xFC)<<3)|(b>>3)))

// ── Purple brand palette (matches tft_ui_v5.h) ───────────
#define CLR_BG         0x0000
#define CLR_SURFACE    0x0821
#define CLR_PURPLE     0x8ADE
#define CLR_PURPLE_LT  0xA45F
#define CLR_PURPLE_DIM 0x2009
#define CLR_INK        0xF79E
#define CLR_SUB        0x8BF4
#define CLR_SUCCESS    0x3693
#define CLR_WARNING    0xFE8A
#define CLR_DANGER     0xF8EE
#define CLR_BORDER     0x1089

// ── Display dimensions (landscape rotation 3) ────────────
#define TFT_W  320
#define TFT_H  240

#ifdef __cplusplus
extern "C" {
#endif

// Initialise the ILI9341 on SPI2 (shares bus with PN5180).
// Call once from app_main before any drawing.
void ili9341_init(spi_host_device_t host, int mosi, int miso, int clk,
                  int cs, int dc, int rst);

// ── Primitive drawing API ─────────────────────────────────

void ili9341_fill_screen(uint16_t color);
void ili9341_fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t color);
void ili9341_draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t color);
void ili9341_fill_round_rect(int16_t x, int16_t y, int16_t w, int16_t h, int16_t r, uint16_t color);
void ili9341_draw_round_rect(int16_t x, int16_t y, int16_t w, int16_t h, int16_t r, uint16_t color);
void ili9341_draw_hline(int16_t x, int16_t y, int16_t len, uint16_t color);
void ili9341_draw_vline(int16_t x, int16_t y, int16_t len, uint16_t color);
void ili9341_draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, uint16_t color);
void ili9341_draw_circle(int16_t cx, int16_t cy, int16_t r, uint16_t color);
void ili9341_fill_circle(int16_t cx, int16_t cy, int16_t r, uint16_t color);
void ili9341_fill_triangle(int16_t x0, int16_t y0,
                           int16_t x1, int16_t y1,
                           int16_t x2, int16_t y2, uint16_t color);

// ── Text API (matches Adafruit_GFX interface used in tft_ui) ─
void ili9341_set_cursor(int16_t x, int16_t y);
void ili9341_set_text_color(uint16_t color);
void ili9341_set_text_size(uint8_t size);   // 1, 2, or 3
void ili9341_print(const char *str);
void ili9341_println(const char *str);
void ili9341_print_char(char c);
void ili9341_print_int(int val);

// ── Internal (used by tft_ui.cpp only) ───────────────────
spi_device_handle_t ili9341_get_spi_handle(void);

#ifdef __cplusplus
}
#endif
