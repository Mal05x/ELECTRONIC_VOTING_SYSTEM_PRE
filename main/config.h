#pragma once

// ============================================================
//  INEC MFA E-Voting Terminal — Build-time Configuration
//  Edit this file before flashing each terminal.
// ============================================================

// ── Network ─────────────────────────────────────────────────
#define WIFI_SSID       "MTN_4G_573240"
#define WIFI_PASSWORD   "A4B6C557"

// BACKEND_HOST: hostname ONLY — no scheme, no trailing slash
#define BACKEND_HOST    "mfa-evoting-backend.onrender.com"
#define BACKEND_PORT    443

// ── Terminal identity ────────────────────────────────────────
#define TERMINAL_ID     "TERM-KD-001"

// ── NTP ─────────────────────────────────────────────────────
#define NTP_SERVER_1    "pool.ntp.org"
#define NTP_SERVER_2    "time.nist.gov"

// ── Timing ──────────────────────────────────────────────────
#define HEARTBEAT_INTERVAL_MS   60000UL
#define WIFI_CONNECT_TIMEOUT_MS 15000UL
#define NTP_SYNC_TIMEOUT_MS     20000UL
#define NETWORK_TASK_TIMEOUT_MS 30000UL

// Hard session timeout: if a voter does not complete the full flow within
// this window from card detection, the session is force-reset.
// Prevents an abandoned terminal from remaining locked in a partial state.
// 120 s is generous — the full flow (PIN + fingerprint + liveness + backend
// auth) typically takes 25–40 s on a good connection.
#define SESSION_TIMEOUT_MS      120000UL

// Idle REQA poll interval: how often to ping the RF field for a new card
// when STATE_IDLE.  500 ms is the minimum recommended by NXP for PN5180
// to avoid RF noise interfering with nearby readers.
#define IDLE_POLL_INTERVAL_MS   500UL

// ── Terminal operating mode selection ────────────────────
// Hold BTN_BACK during the 2.5-second splash screen to enter
// ENROLLMENT mode.  Release to stay in VOTING mode (default).
//
// Enrollment mode activates a completely separate state machine
// (enrollment.cpp / STATE_ENROLL_*).  It does NOT fetch candidates
// or require an active election.  It DOES require WiFi + NTP for
// the backend enrollment record fetch.
//
// For production deployments, build two separate binaries:
//   idf.py build -DENROLLMENT_ONLY=1   → enrollment terminal
//   idf.py build                        → voting terminal
// The CMakeLists.txt ENROLLMENT_ONLY guard below enforces this.
#ifndef ENROLLMENT_ONLY
  #define ENROLL_BTN_HOLD_MS  2000UL  // must hold BTN_BACK this long during splash
#endif

// ── AES-256-GCM backend key ─────────────────────────────────
// Must match base64-decoded AES_256_SECRET in backend application.yml
// Generate: openssl rand -base64 32  → decode to 32 bytes
static const uint8_t BACKEND_AES_KEY[32] = {
		0x68, 0x4a, 0xb8, 0x88, 0x1d, 0x6b, 0x22, 0x2b, 0x01, 0xcd,
		0x98, 0x1c, 0xfb, 0x2e, 0xdc, 0xff, 0xd7, 0x6c, 0x60, 0x68,
		0x95, 0x4f, 0x2d, 0x17, 0x9d, 0xb2, 0x0a, 0xff, 0x71, 0x5c, 0xe7, 0xd5
};

// ── mTLS Certificates ────────────────────────────────────────
// Root CA that signed the backend's TLS cert
/*static const char ROOT_CA_CERT[] = R"EOF(-----BEGIN CERTIFICATE-----
MIIDqjCCA0+gAwIBAgIRAMMAGyq8zEWDDo0Pp21BopMwCgYIKoZIzj0EAwIwOzEL
MAkGA1UEBhMCVVMxHjAcBgNVBAoTFUdvb2dsZSBUcnVzdCBTZXJ2aWNlczEMMAoG
A1UEAxMDV0UxMB4XDTI2MDMyODIxMDAyNloXDTI2MDYyNjIyMDAyMlowFzEVMBMG
A1UEAxMMb25yZW5kZXIuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUxQh
lYjAcRs39ef7v7I5nK2bKEBCRT9SKm+yxnZ0ilorkjOP80XFKTKkO1piE3BKskzq
/e5LF6yuVn/wiezaIKOCAlYwggJSMA4GA1UdDwEB/wQEAwIHgDATBgNVHSUEDDAK
BggrBgEFBQcDATAMBgNVHRMBAf8EAjAAMB0GA1UdDgQWBBRaMmWaF3ka48GuymJ7
TgjPtpxXmzAfBgNVHSMEGDAWgBSQd5I1Z8T/qMyp5nvZgHl7zJP5ODBeBggrBgEF
BQcBAQRSMFAwJwYIKwYBBQUHMAGGG2h0dHA6Ly9vLnBraS5nb29nL3Mvd2UxL3d3
QTAlBggrBgEFBQcwAoYZaHR0cDovL2kucGtpLmdvb2cvd2UxLmNydDAnBgNVHREE
IDAeggxvbnJlbmRlci5jb22CDioub25yZW5kZXIuY29tMBMGA1UdIAQMMAowCAYG
Z4EMAQIBMDYGA1UdHwQvMC0wK6ApoCeGJWh0dHA6Ly9jLnBraS5nb29nL3dlMS9a
SW92RnZHdUpNVS5jcmwwggEFBgorBgEEAdZ5AgQCBIH2BIHzAPEAdgCWl2S/VViX
rfdDh2g3CEJ36fA61fak8zZuRqQ/D8qpxgAAAZ02dne4AAAEAwBHMEUCICGkx1u+
5NyQNP7jCv9HDPtKFqiGoQ+tHYEeIvL6tGJrAiEAkppmOKEgMRf06VjbmZfYrG63
aPMqpI+KtTIZmZrOmasAdwBJnJtp3h187Pw23s2HZKa4W68Kh4AZ0VVS++nrKd34
wwAAAZ02dneYAAAEAwBIMEYCIQCTyHorghtn+KoDB+h+i+bMcaFCqSYobN1xC4yC
VOF/ywIhANzVCoeHsvj8yim9kG+2cdfD0/j0Z7Cbc84uFRD6zCnSMAoGCCqGSM49
BAMCA0kAMEYCIQDz+YFTNjl4HZlVndFK6Bm7rHFp1tgXJGsBrmj3deLMFgIhALYo
AUseygUBDupdArq6xYLCQJwj7n3VVzqlaEqMP3hR
-----END CERTIFICATE-----)EOF";*/

// Client certificate for this terminal (mTLS identity)
static const char CLIENT_CERT[] = R"EOF(-----BEGIN CERTIFICATE-----
MIIDgDCCAmigAwIBAgIUYEinSu8NWF1hxEs0zxMwDzOfQXQwDQYJKoZIhvcNAQEL
BQAwXDELMAkGA1UEBhMCTkcxDzANBgNVBAgMBkthZHVuYTEPMA0GA1UEBwwGS2Fk
dW5hMQ0wCwYDVQQKDARJTkVDMRwwGgYDVQQDDBNJTkVDX1ZvdGluZ19Sb290X0NB
MB4XDTI2MDMxNzAwMjU0OVoXDTI3MDMxNzAwMjU0OVowVDELMAkGA1UEBhMCTkcx
DzANBgNVBAgMBkthZHVuYTEPMA0GA1UEBwwGS2FkdW5hMQ0wCwYDVQQKDARJTkVD
MRQwEgYDVQQDDAtURVJNLUtELTAwMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC
AQoCggEBALRTWqVWBOC+z4iQBbP9S9zxTFj5AzOR1FB646LqvdBBnANL+OSxTH7T
1Y5mSVLQ8wXy+5HD+xI1Dn3HmzRPGC9PCHy9UErQI1zT2nNr+B5N/UXjnLUvb8kO
iWZLBJ1zQZyhwYvVVq5o5J6H4ndNbu8E/nag2Qc19HqYWdikrVSO7OTf1ZLrP3YX
6Z4rpCIPKgupMqaXhkjcket8+/LE8+dvDA8P7miEQeewh6oOa3e8zm5xNmw/kFBU
f8y7/mBgvcTdkgsP40JiR/kNmELAEieO237NiW8r/9yeQ+MsJejLV/2Ic6vAwi26
zT1hBk8zcGD+W7tCCJeUGeygBzlLVTkCAwEAAaNCMEAwHQYDVR0OBBYEFBLFHMc9
T54spRBY8Kj47pzEM/HGMB8GA1UdIwQYMBaAFPrFPsVnELmTbC+1uKNvPX7gyIVs
MA0GCSqGSIb3DQEBCwUAA4IBAQAsvUdUHWObUrLD8xiwDA1tOt4N7q3uWiKhDBrw
rCyOaxp9UyBOJnhDp520WOgqzCKHs4nv3Of8ya2zh1MkqN1URGnFVsYDXQeCgMZN
VqP26uHIVa6vOF/TKyHMOWE5JQMVKoU8giZLjb68lvg9QW4IjoK70dZNbV15fhxL
56iPxa1/LIUoXDtlwcB3/2Pb17whZErg6YWdLvhwXnLtJA/cFvEQzgD/SLqxXl2m
Kq2Jzi+MkbDybss7x7pOKHo/wRnSO3nM3riq6VXekyqnxraEi44sSF26vC48Yzfk
g4CRE9H8d9UClbBM7d6RIvIlnPsLIU8cMOSGyQSWCvcAC+z3
-----END CERTIFICATE-----)EOF";

// Client private key for this terminal
static const char CLIENT_KEY[] = R"EOF(-----BEGIN PRIVATE KEY-----
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC0U1qlVgTgvs+I
kAWz/Uvc8UxY+QMzkdRQeuOi6r3QQZwDS/jksUx+09WOZklS0PMF8vuRw/sSNQ59
x5s0TxgvTwh8vVBK0CNc09pza/geTf1F45y1L2/JDolmSwSdc0GcocGL1VauaOSe
h+J3TW7vBP52oNkHNfR6mFnYpK1Ujuzk39WS6z92F+meK6QiDyoLqTKml4ZI3JHr
fPvyxPPnbwwPD+5ohEHnsIeqDmt3vM5ucTZsP5BQVH/Mu/5gYL3E3ZILD+NCYkf5
DZhCwBInjtt+zYlvK//cnkPjLCXoy1f9iHOrwMItus09YQZPM3Bg/lu7QgiXlBns
oAc5S1U5AgMBAAECggEASg0k31q4vBZ/oUQLo4N0QPIyeSMhRm8vcjFVA9VER555
0/zB5CBPGpIhU837dG/sLNhAqPaDR1HUdqCewtdsRhk9kgQoUeCxdPBm+BkxljhO
twGmm900GavHjuNlrMbk/c6LbcIZS5w1rkh9HqME2FEDsnGRTl3A3QttcgMGTv+D
xnwAcmZnV1wyxetpxS52P9MINSN705w/JV9rNRj5njkhIGXD1J92A8DbOk9WSYEL
o85o/ztmwikcGBCLurrpxmBlqxJEcMVUzBo7PlAYuVKqOVA6jRle1HiXYoVBJbyw
BJhw2cfGAuxlLd0+KOsGaUP84F6Dd2aunnGLNF4oZQKBgQD84gpKp7TE+LvKPqYQ
3sd0PYcR8fgaNd0fNwsWxWUGvYyleP7NK6ylWrim+ASZ5gUQlg2t42sf5rJpEBdB
+etaghs7r9Jqg6d/pb8A37fd5LF1Y8O++8jcTUkHnFsx3FKcwtqGAoilRZAAHZcu
9mqMg7AGqB9FBI6awx2lgUzemwKBgQC2jFzYB6LwMe7a/UJ6yE4ZJa4n0aqNO4wg
E5vAqi6BC/16ewZjcqsqLyudSAoPbZjV1hYdsCihjnS0OkOeePGrH0b5XIF/X+fE
/fbAx0lj83af5Fuzec8Gs5uz4/+EsyUOM2rTVr45+Cc5Rk795eBc51GG7KfPzFcV
A9vWT0zOuwKBgQDFcyz7+5Q+08O091OtCnWLwlrLXTYOKQ59R/oljn0CVVATB7VT
t403Eu3omPDmxV4hBDisFEzZfMTDGeg/AIeOhNYEtZTCzAcluOG5KfUjkWQqN3po
janYsZObGK9v8AqlEdOUHThaa0UzgedAqPCqxEvV9sFrn1JFbqGgT6QYjwKBgQCy
K2tO2p04rLPBf6DMMAax2qoRaT16XV1VzW2ebTu4blh3m+3PUJMpOCLsfux6xHCA
fVTWoyH1FRJo+dkXqlnNuFhQd+5YwOV4ypt06s2BxZXJV1v9X20l0FWvDWBOR39q
Tdr87NP7xm2aJDpi62PBqXBULVVYpstz0nFVyc5gkwKBgQCFI3BkDL+Wk31UR8Jg
dlyLAzxvXdQwjL3RsJOQjxahBlNtPAnhJqVl2xcHL3GOWX3Gc8kDxN0PQ29ALSJg
VkEtG6L3NUwxX4t33aorThaPk1EWXwXeilWns5P45kl2uXNcewYjbMm3OMnyQoDA
3ZxIWVWZoiBc3COogJo2c5uzdg==
-----END PRIVATE KEY-----)EOF";
