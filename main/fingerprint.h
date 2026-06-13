#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Call once from app_main (configures UART1)
void     fp_init(int rx_pin, int tx_pin);

bool     fp_verify_sensor(void);
bool     fp_capture_image(void);
bool     fp_generate_template(void);
uint16_t fp_get_template(uint8_t *buf_out);  // returns template size in bytes

#ifdef __cplusplus
}
#endif
