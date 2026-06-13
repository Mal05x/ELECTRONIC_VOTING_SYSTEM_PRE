idf_component_register(
    SRCS
        "main.cpp"
        "ili9341.cpp"
        "tft_ui.cpp"
        "buzzer.cpp"
        "pn5180.cpp"
        "fingerprint.cpp"
        "camera_uart.cpp"
        "crypto_utils.cpp"
        "terminal_key.cpp"
        "smart_card.cpp"
        "network.cpp"
        "enrollment_store.cpp"
        "enrollment.cpp"
        "officer_auth.cpp"
        "applet_crypto.cpp"
    INCLUDE_DIRS
        "."
    REQUIRES
   		arduino
        PN5180-Library
        driver
        esp_wifi
        esp_event
        esp_http_client
        esp-tls
        nvs_flash
        mbedtls
        json
        esp_timer
        freertos
        log
        spi_flash
        esp_adc
        app_update
)

target_link_libraries(${COMPONENT_LIB} PUBLIC m)

# Force Arduino Serial/ESP_LOG to use the ESP32-S3 Native USB port
add_compile_options(
    -DARDUINO_USB_MODE=1
    -DARDUINO_USB_CDC_ON_BOOT=1
)

# ── Optional: enrollment-only build ─────────────────────────────────────────
# cmake -DENROLLMENT_ONLY=1 ..   (or idf.py build -DENROLLMENT_ONLY=1)
# Forces MODE_ENROLLMENT at compile time so the binary cannot be used for
# voting even if BTN_BACK is not held.  Use this for dedicated enrollment
# terminals in production to prevent misuse.
if(DEFINED ENROLLMENT_ONLY AND ENROLLMENT_ONLY)
    target_compile_definitions(${COMPONENT_LIB} PUBLIC ENROLLMENT_ONLY=1)
    message(STATUS "ENROLLMENT_ONLY build — voting state machine excluded")
endif()
