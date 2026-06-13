#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

void wrapper_pn5180_init(void);
void wrapper_reset_rf(void);
bool wrapper_pn5180_read_card(uint8_t *uid_out, uint8_t *uid_len);
bool wrapper_iso14443_rats(void);
bool wrapper_send_apdu(uint8_t *cmd, uint16_t cmd_len, uint8_t *response, uint16_t *response_len);

#ifdef __cplusplus
}
#endif
