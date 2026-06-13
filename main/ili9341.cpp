#include "ili9341.h"
#include "font.h"
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_log.h"
#include <string.h>
#include <stdlib.h>
#include <algorithm>

static const char *TAG = "ILI9341";

// ── ILI9341 register map ──────────────────────────────────
#define ILI9341_SWRESET  0x01
#define ILI9341_SLPIN    0x10
#define ILI9341_SLPOUT   0x11
#define ILI9341_NORON    0x13
#define ILI9341_INVOFF   0x20
#define ILI9341_INVON    0x21
#define ILI9341_DISPOFF  0x28
#define ILI9341_DISPON   0x29
#define ILI9341_CASET    0x2A
#define ILI9341_RASET    0x2B
#define ILI9341_RAMWR    0x2C
#define ILI9341_MADCTL   0x36
#define ILI9341_COLMOD   0x3A
#define ILI9341_FRMCTR1  0xB1
#define ILI9341_DFUNCTR  0xB6
#define ILI9341_PWCTR1   0xC0
#define ILI9341_PWCTR2   0xC1
#define ILI9341_VMCTR1   0xC5
#define ILI9341_VMCTR2   0xC7
#define ILI9341_GMCTRP1  0xE0
#define ILI9341_GMCTRN1  0xE1

// MADCTL bits
#define MADCTL_MY   0x80
#define MADCTL_MX   0x40
#define MADCTL_MV   0x20
#define MADCTL_BGR  0x08

// Rotation 3: MX | MY | MV | BGR = landscape, 320×240
#define MADCTL_ROTATION3 (MADCTL_MX | MADCTL_MY | MADCTL_MV | MADCTL_BGR)

// ── State ─────────────────────────────────────────────────
static spi_device_handle_t s_spi = NULL;
static int s_dc_pin  = -1;
static int s_rst_pin = -1;

// Text state
static int16_t  s_cursor_x = 0, s_cursor_y = 0;
static uint16_t s_text_color = 0xFFFF;
static uint8_t  s_text_size  = 1;

// ── DC pin pre-transfer callback ─────────────────────────
// user = 0 → command, user = 1 → data
static void IRAM_ATTR tft_pre_transfer_cb(spi_transaction_t *t) {
    gpio_set_level((gpio_num_t)s_dc_pin, (int)(intptr_t)t->user);
}

// ── Low-level SPI helpers ─────────────────────────────────

static void tft_cmd(uint8_t cmd) {
    spi_transaction_t t = {};
    t.length    = 8;
    t.tx_data[0] = cmd;
    t.user      = (void *)0;   // DC=0 → command
    t.flags     = SPI_TRANS_USE_TXDATA;
    spi_device_polling_transmit(s_spi, &t);
}

static void tft_data8(uint8_t d) {
    spi_transaction_t t = {};
    t.length    = 8;
    t.tx_data[0] = d;
    t.user      = (void *)1;   // DC=1 → data
    t.flags     = SPI_TRANS_USE_TXDATA;
    spi_device_polling_transmit(s_spi, &t);
}

static void tft_data_buf(const uint8_t *data, size_t len) {
    if (len == 0) return;
    spi_transaction_t t = {};
    t.length    = len * 8;
    t.tx_buffer = data;
    t.user      = (void *)1;
    spi_device_polling_transmit(s_spi, &t);
}

// ── Set draw window (column/row address) ─────────────────
static void set_window(int16_t x0, int16_t y0, int16_t x1, int16_t y1) {
    uint8_t buf[4];
    tft_cmd(ILI9341_CASET);
    buf[0] = x0 >> 8; buf[1] = x0 & 0xFF;
    buf[2] = x1 >> 8; buf[3] = x1 & 0xFF;
    tft_data_buf(buf, 4);
    tft_cmd(ILI9341_RASET);
    buf[0] = y0 >> 8; buf[1] = y0 & 0xFF;
    buf[2] = y1 >> 8; buf[3] = y1 & 0xFF;
    tft_data_buf(buf, 4);
    tft_cmd(ILI9341_RAMWR);
}

// ── Write a block of one colour via DMA line buffer ───────
// Line buffer: 320 pixels × 2 bytes, must be DMA-capable (SRAM)
static uint16_t s_line_buf[TFT_W] __attribute__((aligned(4)));

static void fill_pixels(uint16_t color, uint32_t count) {
    // FIX: Acquire the SPI bus for the entire fill operation.
    // Without this, the PN5180 Arduino library's SPI.beginTransaction()
    // can interleave between pixel chunks, switching the clock from 40 MHz
    // to 7 MHz mid-stream and corrupting the display. Acquiring the bus
    // prevents any other SPI device from transacting until we release it.
    spi_device_acquire_bus(s_spi, portMAX_DELAY);

    // Preload line buffer with byte-swapped colour
    uint16_t c_be = __builtin_bswap16(color);
    uint32_t chunk = (count < TFT_W) ? count : TFT_W;
    for (uint32_t i = 0; i < chunk; i++) s_line_buf[i] = c_be;

    while (count > 0) {
        uint32_t n = (count < TFT_W) ? count : TFT_W;
        spi_transaction_t t = {};
        t.length    = n * 16;
        t.tx_buffer = s_line_buf;
        t.user      = (void *)1;
        spi_device_polling_transmit(s_spi, &t);
        count -= n;
    }

    spi_device_release_bus(s_spi);
}

// ── ILI9341 initialisation sequence ──────────────────────
static const struct { uint8_t cmd; uint8_t data[16]; uint8_t len; uint8_t delay_ms; }
INIT_SEQ[] = {
    {ILI9341_SWRESET, {}, 0, 150},
    {ILI9341_SLPOUT,  {}, 0, 120},
    {ILI9341_FRMCTR1, {0x00,0x18}, 2, 0},
    {ILI9341_DFUNCTR, {0x08,0x82,0x27}, 3, 0},
    {ILI9341_PWCTR1,  {0x23}, 1, 0},
    {ILI9341_PWCTR2,  {0x10}, 1, 0},
    {ILI9341_VMCTR1,  {0x3E,0x28}, 2, 0},
    {ILI9341_VMCTR2,  {0x86}, 1, 0},
    {ILI9341_MADCTL,  {MADCTL_ROTATION3}, 1, 0},
    {ILI9341_COLMOD,  {0x55}, 1, 0},   // 16-bit colour
    {ILI9341_INVOFF,  {}, 0, 0},
    {ILI9341_NORON,   {}, 0, 10},
    {ILI9341_GMCTRP1, {0x0F,0x31,0x2B,0x0C,0x0E,0x08,0x4E,0xF1,
                       0x37,0x07,0x10,0x03,0x0E,0x09,0x00}, 15, 0},
    {ILI9341_GMCTRN1, {0x00,0x0E,0x14,0x03,0x11,0x07,0x31,0xC1,
                       0x48,0x08,0x0F,0x0C,0x31,0x36,0x0F}, 15, 0},
    {ILI9341_DISPON,  {}, 0, 0},
};

void ili9341_init(spi_host_device_t host, int mosi, int miso, int clk,
                  int cs, int dc, int rst) {
    s_dc_pin  = dc;
    s_rst_pin = rst;

    // CS & DC as outputs (CS is managed by SPI driver)
    gpio_config_t io = {};
    io.mode = GPIO_MODE_OUTPUT;
    io.pull_up_en = GPIO_PULLUP_DISABLE;
    io.intr_type  = GPIO_INTR_DISABLE;
    io.pin_bit_mask = (1ULL << dc);
    if (rst >= 0) io.pin_bit_mask |= (1ULL << rst);
    gpio_config(&io);

    // CS starts high
    gpio_config_t cs_cfg = {};
    cs_cfg.mode = GPIO_MODE_OUTPUT;
    cs_cfg.pin_bit_mask = (1ULL << cs);
    gpio_config(&cs_cfg);
    gpio_set_level((gpio_num_t)cs, 1);

    // Hardware reset
    if (rst >= 0) {
        gpio_set_level((gpio_num_t)rst, 0);
        vTaskDelay(pdMS_TO_TICKS(50));
        gpio_set_level((gpio_num_t)rst, 1);
        vTaskDelay(pdMS_TO_TICKS(150));
    }

    // SPI bus init (shared with PN5180 — call only once from main)
    spi_bus_config_t bus = {};
    bus.mosi_io_num     = mosi;
    bus.miso_io_num     = miso;
    bus.sclk_io_num     = clk;
    bus.quadwp_io_num   = -1;
    bus.quadhd_io_num   = -1;
    bus.max_transfer_sz = TFT_W * TFT_H * 2 + 64;
    esp_err_t err = spi_bus_initialize(host, &bus, SPI_DMA_CH_AUTO);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "spi_bus_initialize failed: %s", esp_err_to_name(err));
    }

    // Add TFT as SPI device
    spi_device_interface_config_t dev = {};
    dev.clock_speed_hz = 40 * 1000 * 1000;  // 40 MHz
    dev.mode           = 0;
    dev.spics_io_num   = cs;
    dev.queue_size     = 7;
    dev.pre_cb         = tft_pre_transfer_cb;
    spi_bus_add_device(host, &dev, &s_spi);

    // Run init sequence
    for (size_t i = 0; i < sizeof(INIT_SEQ)/sizeof(INIT_SEQ[0]); i++) {
        tft_cmd(INIT_SEQ[i].cmd);
        if (INIT_SEQ[i].len > 0)
            tft_data_buf(INIT_SEQ[i].data, INIT_SEQ[i].len);
        if (INIT_SEQ[i].delay_ms > 0)
            vTaskDelay(pdMS_TO_TICKS(INIT_SEQ[i].delay_ms));
    }

    ili9341_fill_screen(0x0000);
    ESP_LOGI(TAG, "ILI9341 initialised (320×240 landscape)");
}

spi_device_handle_t ili9341_get_spi_handle(void) { return s_spi; }

// ── Drawing primitives ────────────────────────────────────

void ili9341_fill_screen(uint16_t color) {
    set_window(0, 0, TFT_W - 1, TFT_H - 1);
    fill_pixels(color, (uint32_t)TFT_W * TFT_H);
}

void ili9341_fill_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t color) {
    if (x >= TFT_W || y >= TFT_H || w <= 0 || h <= 0) return;
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > TFT_W) w = TFT_W - x;
    if (y + h > TFT_H) h = TFT_H - y;
    set_window(x, y, x + w - 1, y + h - 1);
    fill_pixels(color, (uint32_t)w * h);
}

void ili9341_draw_rect(int16_t x, int16_t y, int16_t w, int16_t h, uint16_t color) {
    ili9341_draw_hline(x, y,       w, color);
    ili9341_draw_hline(x, y+h-1,   w, color);
    ili9341_draw_vline(x,     y, h, color);
    ili9341_draw_vline(x+w-1, y, h, color);
}

void ili9341_draw_hline(int16_t x, int16_t y, int16_t len, uint16_t color) {
    ili9341_fill_rect(x, y, len, 1, color);
}

void ili9341_draw_vline(int16_t x, int16_t y, int16_t len, uint16_t color) {
    ili9341_fill_rect(x, y, 1, len, color);
}

// Corner helper for rounded rects
static void draw_circle_helper(int16_t cx, int16_t cy, int16_t r,
                                uint8_t corners, uint16_t color) {
    int16_t f = 1 - r, ddF_x = 1, ddF_y = -2 * r;
    int16_t x = 0, y = r;
    while (x < y) {
        if (f >= 0) { y--; ddF_y += 2; f += ddF_y; }
        x++; ddF_x += 2; f += ddF_x;
        if (corners & 0x4) { // lower right
            set_window(cx+x, cy+y, cx+x, cy+y); fill_pixels(color,1);
            set_window(cx+y, cy+x, cx+y, cy+x); fill_pixels(color,1);
        }
        if (corners & 0x2) { // upper right
            set_window(cx+x, cy-y, cx+x, cy-y); fill_pixels(color,1);
            set_window(cx+y, cy-x, cx+y, cy-x); fill_pixels(color,1);
        }
        if (corners & 0x8) { // lower left
            set_window(cx-y, cy+x, cx-y, cy+x); fill_pixels(color,1);
            set_window(cx-x, cy+y, cx-x, cy+y); fill_pixels(color,1);
        }
        if (corners & 0x1) { // upper left
            set_window(cx-y, cy-x, cx-y, cy-x); fill_pixels(color,1);
            set_window(cx-x, cy-y, cx-x, cy-y); fill_pixels(color,1);
        }
    }
}

static void fill_circle_helper(int16_t cx, int16_t cy, int16_t r,
                                uint8_t sides, uint16_t color) {
    int16_t f = 1 - r, ddF_x = 1, ddF_y = -2 * r;
    int16_t x = 0, y = r;
    while (x < y) {
        if (f >= 0) { y--; ddF_y += 2; f += ddF_y; }
        x++; ddF_x += 2; f += ddF_x;
        if (sides & 0x1) { // right
            ili9341_draw_vline(cx + x, cy - y, 2 * y + 1, color);
            ili9341_draw_vline(cx + y, cy - x, 2 * x + 1, color);
        }
        if (sides & 0x2) { // left
            ili9341_draw_vline(cx - x, cy - y, 2 * y + 1, color);
            ili9341_draw_vline(cx - y, cy - x, 2 * x + 1, color);
        }
    }
}

void ili9341_fill_round_rect(int16_t x, int16_t y, int16_t w, int16_t h,
                              int16_t r, uint16_t color) {
    ili9341_fill_rect(x + r, y, w - 2*r, h, color);
    fill_circle_helper(x + w - r - 1, y + r, r, 0x1, color);  // right
    fill_circle_helper(x         + r, y + r, r, 0x2, color);  // left
}

void ili9341_draw_round_rect(int16_t x, int16_t y, int16_t w, int16_t h,
                              int16_t r, uint16_t color) {
    ili9341_draw_hline(x + r,     y,         w - 2*r, color);
    ili9341_draw_hline(x + r,     y + h - 1, w - 2*r, color);
    ili9341_draw_vline(x,         y + r, h - 2*r, color);
    ili9341_draw_vline(x + w - 1, y + r, h - 2*r, color);
    draw_circle_helper(x + r,         y + r,         r, 0x1, color);
    draw_circle_helper(x + w - r - 1, y + r,         r, 0x2, color);
    draw_circle_helper(x + w - r - 1, y + h - r - 1, r, 0x4, color);
    draw_circle_helper(x + r,         y + h - r - 1, r, 0x8, color);
}

void ili9341_draw_circle(int16_t cx, int16_t cy, int16_t r, uint16_t color) {
    int16_t f = 1 - r, ddF_x = 1, ddF_y = -2*r, x = 0, y = r;
    auto px = [&](int16_t px, int16_t py) {
        if (px >= 0 && px < TFT_W && py >= 0 && py < TFT_H) {
            set_window(px, py, px, py); fill_pixels(color, 1);
        }
    };
    px(cx,     cy + r); px(cx,     cy - r);
    px(cx + r, cy);     px(cx - r, cy);
    while (x < y) {
        if (f >= 0) { y--; ddF_y += 2; f += ddF_y; }
        x++; ddF_x += 2; f += ddF_x;
        px(cx+x,cy+y); px(cx-x,cy+y); px(cx+x,cy-y); px(cx-x,cy-y);
        px(cx+y,cy+x); px(cx-y,cy+x); px(cx+y,cy-x); px(cx-y,cy-x);
    }
}

void ili9341_fill_circle(int16_t cx, int16_t cy, int16_t r, uint16_t color) {
    ili9341_draw_vline(cx, cy - r, 2*r+1, color);
    fill_circle_helper(cx, cy, r, 0x3, color);
}

void ili9341_draw_line(int16_t x0, int16_t y0, int16_t x1, int16_t y1, uint16_t color) {
    int16_t steep = abs(y1 - y0) > abs(x1 - x0);
    if (steep)         { std::swap(x0,y0); std::swap(x1,y1); }
    if (x0 > x1)      { std::swap(x0,x1); std::swap(y0,y1); }
    int16_t dx = x1-x0, dy = abs(y1-y0), err = dx/2;
    int16_t ystep = (y0 < y1) ? 1 : -1;
    for (; x0 <= x1; x0++) {
        if (steep) { set_window(y0,x0,y0,x0); } else { set_window(x0,y0,x0,y0); }
        fill_pixels(color,1);
        err -= dy;
        if (err < 0) { y0 += ystep; err += dx; }
    }
}

void ili9341_fill_triangle(int16_t x0, int16_t y0,
                            int16_t x1, int16_t y1,
                            int16_t x2, int16_t y2, uint16_t color) {
    // Sort vertices by Y
    if (y0 > y1) { std::swap(y0,y1); std::swap(x0,x1); }
    if (y1 > y2) { std::swap(y1,y2); std::swap(x1,x2); }
    if (y0 > y1) { std::swap(y0,y1); std::swap(x0,x1); }

    int16_t a, b, y, last;
    if (y0 == y2) {
        a = b = x0;
        if (x1 < a) a = x1;
        if (x2 < a) a = x2;
        if (x1 > b) b = x1;
        if (x2 > b) b = x2;
        ili9341_draw_hline(a, y0, b-a+1, color);
        return;
    }
    int16_t dx01 = x1-x0, dy01 = y1-y0, dx02 = x2-x0, dy02 = y2-y0;
    int16_t dx12 = x2-x1, dy12 = y2-y1;
    int32_t sa = 0, sb = 0;

    last = (y1 == y2) ? y1 : y1 - 1;
    for (y = y0; y <= last; y++) {
        a = x0 + sa / dy01; b = x0 + sb / dy02;
        sa += dx01; sb += dx02;
        if (a > b) std::swap(a, b);
        ili9341_draw_hline(a, y, b-a+1, color);
    }
    sa = (int32_t)dx12 * (y - y1);
    sb = (int32_t)dx02 * (y - y0);
    for (; y <= y2; y++) {
        a = x1 + sa / dy12; b = x0 + sb / dy02;
        sa += dx12; sb += dx02;
        if (a > b) std::swap(a, b);
        ili9341_draw_hline(a, y, b-a+1, color);
    }
}

// ── Text rendering ────────────────────────────────────────

void ili9341_set_cursor(int16_t x, int16_t y) { s_cursor_x = x; s_cursor_y = y; }
void ili9341_set_text_color(uint16_t c)        { s_text_color = c; }
void ili9341_set_text_size(uint8_t s)          { s_text_size = (s > 0) ? s : 1; }

void ili9341_print_char(char c) {
    if (c == '\n') {
        s_cursor_x  = 0;
        s_cursor_y += (FONT_CHAR_H + FONT_CHAR_GAP) * s_text_size;
        return;
    }
    if (c < FONT_FIRST_CHAR || c > (FONT_FIRST_CHAR + 95)) return;
    const uint8_t *glyph = FONT_5X7[c - FONT_FIRST_CHAR];
    int cw = (FONT_CHAR_W + FONT_CHAR_GAP) * s_text_size;
    int ch = (FONT_CHAR_H + FONT_CHAR_GAP) * s_text_size;
    // Background: clear character cell
    // (Not filled — caller should fill background before text)
    for (int col = 0; col < FONT_CHAR_W; col++) {
        uint8_t line = glyph[col];
        for (int row = 0; row < FONT_CHAR_H; row++) {
            if (line & (1 << row)) {
                // Foreground pixel
                int px = s_cursor_x + col * s_text_size;
                int py = s_cursor_y + row * s_text_size;
                ili9341_fill_rect(px, py, s_text_size, s_text_size, s_text_color);
            }
        }
    }
    s_cursor_x += cw;
    (void)ch;
}

void ili9341_print(const char *str) {
    if (!str) return;
    while (*str) ili9341_print_char(*str++);
}

void ili9341_println(const char *str) {
    ili9341_print(str);
    ili9341_print_char('\n');
}

void ili9341_print_int(int val) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%d", val);
    ili9341_print(buf);
}
