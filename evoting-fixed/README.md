# MFA Electronic Voting System — Backend

Spring Boot 3.2.5 · Java 21 · PostgreSQL 16 · Redis 7 · AWS S3

---

## Quick Start

```bash
# 1. Generate security secrets
export AES_256_SECRET=$(openssl rand -base64 32)
export JWT_SECRET=$(openssl rand -base64 64)

# 2. Configure environment
cp .env.example .env
# Edit .env — fill in secrets, AWS credentials, S3 bucket name

# 3. Launch
docker-compose up --build
# Flyway runs V1 → V2 → V3 automatically on first startup
```

API: `http://localhost:8080`  
Default admin: **superadmin / Admin@12345** — change immediately.

---

## Database Migrations

| Version | What it does |
|---------|-------------|
| V1 | Core tables: elections, candidates, voter_registry, ballot_box, audit_log, voting_sessions, admin_users, terminal_heartbeats |
| V2 | Geographic hierarchy: states (37), lgas (774), polling_units. Adds polling_unit_id FKs |
| V3 | Parties table + 10 seeded Nigerian parties. Adds voting_id + card_locked to voter_registry. candidate image columns. card_status_log |

---

## Feature Overview

### 1. INEC-Style Voting IDs
Every registered voter receives a unique human-readable ID in the format:

```
{StateCode}/{LGA2d}/{PU3d}/{Seq4d}
e.g.  KD/01/003/0042   (Kaduna / LGA 01 / PU 003 / voter #42 at that unit)
      LA/05/012/0001   (Lagos / LGA 05 / PU 012 / voter #1 at that unit)
      RI/18/001/0217   (Rivers / LGA 18 / PU 001 / voter #217)
```

- Returned in the registration response — print on voter's physical card.
- Unique across the entire system (UNIQUE constraint in DB).
- In-memory counter per polling unit (seeded from DB on first use, thread-safe).

### 2. Candidate & Party Image Storage (AWS S3)
- Candidate photos → `s3://{bucket}/candidates/{uuid}.jpg`
- Party logos      → `s3://{bucket}/parties/{uuid}.png`
- Presigned URLs valid for 7 days (configurable via `aws.s3.presigned-url-expiry-hours`).
- Old image deleted from S3 automatically when replaced.
- URL refresh endpoint available when presigned URL expires.

**Required IAM permissions on your S3 bucket:**
```json
{ "Action": ["s3:PutObject","s3:GetObject","s3:DeleteObject"],
  "Resource": "arn:aws:s3:::evoting-media/*" }
```

### 3. Election Data Loading — Three Methods
**a) JSON (API body)**
```bash
POST /api/admin/import/json
Content-Type: application/json
Authorization: Bearer <token>

[
  {"fullName":"Bola Ahmed Tinubu","partyAbbreviation":"APC","position":"President","electionId":"..."},
  {"fullName":"Atiku Abubakar","partyAbbreviation":"PDP","position":"President","electionId":"..."}
]
```

**b) CSV upload**
```bash
POST /api/admin/import/csv?electionId={uuid}
Content-Type: multipart/form-data
field: file = candidates.csv
```
CSV format (row 1 is header, skipped):
```
fullName,partyAbbreviation,position
Bola Ahmed Tinubu,APC,President
Atiku Abubakar,PDP,President
```

**c) Excel upload (.xlsx)**
```bash
POST /api/admin/import/excel?electionId={uuid}
field: file = candidates.xlsx
```
Same column layout as CSV. Handles blank rows, numeric cells.

All three return:
```json
{"totalRows":2,"succeeded":2,"failed":0,"errors":[]}
```

### 4. Smart Card Lock / Unlock

**State machine:**
```
[REGISTRATION] → card_locked = FALSE
[VOTE CAST]    → card_locked = TRUE  (automatic — happens atomically with has_voted)
[ELECTION CLOSED] → card_locked = FALSE (automatic bulk reset for ALL cards in election)
[MANUAL UNLOCK] → card_locked = FALSE  (SUPER_ADMIN override, single card)
[MANUAL LOCK]   → card_locked = TRUE   (SUPER_ADMIN override — lost/stolen card)
```

**Authentication gate** — if `card_locked = TRUE`, the terminal receives:
```json
{"error": "Smart card is locked. Contact your presiding officer."}
```

**Bulk auto-unlock** fires when `PUT /api/admin/elections/{id}/close` is called:
```json
{
  "election": {...},
  "cardsUnlocked": 847,
  "message": "Election closed. 847 smart cards unlocked for future elections."
}
```

Every lock/unlock event is written to `card_status_log` (immutable, no UPDATE/DELETE).

---

## Full API Reference

### Terminal Endpoints (AES-256-GCM encrypted · rate-limited 1 req/30s)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/terminal/authenticate` | Phase 3 auth: liveness + card sig + lock check |
| POST | `/api/terminal/vote` | Cast vote · locks card · returns Transaction ID |
| POST | `/api/terminal/heartbeat` | Battery + tamper status beacon |

### Public Endpoints (no auth)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/results/{electionId}` | National tally + Merkle root |
| GET | `/api/results/{electionId}/by-state` | 37-state breakdown + turnout % |
| GET | `/api/results/{electionId}/state/{stateId}` | Single-state tally |
| GET | `/api/receipt/{transactionId}` | Verify vote (no candidate revealed) |
| GET | `/api/locations/states` | All 36 states + FCT |
| GET | `/api/locations/states/{stateId}/lgas` | LGAs in a state |
| GET | `/api/locations/lgas/{lgaId}/polling-units` | Polling units in an LGA |

### Admin — Elections & Parties (JWT required)
| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/api/auth/login` | — | Returns JWT |
| POST | `/api/admin/elections` | ADMIN+ | Create election |
| PUT | `/api/admin/elections/{id}/activate` | SUPER_ADMIN | |
| PUT | `/api/admin/elections/{id}/close` | SUPER_ADMIN | Auto-unlocks all cards |
| GET | `/api/admin/elections` | All | |
| POST | `/api/admin/parties` | ADMIN+ | Create party |
| GET | `/api/admin/parties` | All | 10 Nigerian parties pre-seeded |
| POST | `/api/admin/candidates` | ADMIN+ | Single candidate |
| GET | `/api/admin/elections/{id}/candidates` | All | |

### Admin — Bulk Import
| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/api/admin/import/json` | ADMIN+ | JSON array |
| POST | `/api/admin/import/csv?electionId=` | ADMIN+ | CSV multipart |
| POST | `/api/admin/import/excel?electionId=` | ADMIN+ | .xlsx multipart |

### Admin — Images
| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/api/admin/images/candidate/{id}` | ADMIN+ | field: `image` · max 5MB |
| POST | `/api/admin/images/party/{id}` | ADMIN+ | field: `image` |
| DELETE | `/api/admin/images/candidate/{id}` | SUPER_ADMIN | Deletes from S3 |
| POST | `/api/admin/images/candidate/{id}/refresh-url` | ADMIN+ | Regenerate presigned URL |

### Admin — Card Management
| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/api/admin/cards/unlock` | SUPER_ADMIN | Single card |
| POST | `/api/admin/cards/lock` | SUPER_ADMIN | Lost/stolen card |
| POST | `/api/admin/cards/unlock-all/{electionId}` | SUPER_ADMIN | Manual bulk (also fires on close) |
| GET | `/api/admin/cards/history/{cardIdHash}` | All | Card's full event log |
| GET | `/api/admin/cards/election/{electionId}` | All | All card events for election |

### Admin — Polling Units & Voters
| Method | Path | Role | Notes |
|--------|------|------|-------|
| POST | `/api/admin/polling-units` | ADMIN+ | Returns full geo chain |
| GET | `/api/admin/polling-units/lga/{lgaId}` | All | |
| POST | `/api/admin/voters/register` | ADMIN+ | Returns Voting ID (e.g. KD/01/003/0042) |

### WebSocket Topics (STOMP at `/ws`)
| Topic | Description |
|-------|-------------|
| `/topic/results/{electionId}` | Live national tally |
| `/topic/results/{electionId}/state/{stateId}` | Live state tally |
| `/topic/merkle/{electionId}` | Live Merkle root |

---

## Voter Registration Flow

```
1. GET  /api/locations/states              → pick state (e.g. KD = Kaduna)
2. GET  /api/locations/states/19/lgas      → pick LGA
3. GET  /api/locations/lgas/123/polling-units  → pick polling unit
4. POST /api/admin/voters/register
   body: { cardIdHash, voterPublicKey, encryptedDemographic, electionId, pollingUnitId }

Response:
{
  "votingId":       "KD/01/003/0042",
  "pollingUnitName": "Rigasa Primary School",
  "lgaName":        "Igabi",
  "stateName":      "Kaduna",
  "message":        "Voter registered. Voting ID: KD/01/003/0042"
}
```

---

## Security Architecture

| Layer | Mechanism |
|-------|-----------|
| Biometric | Match-on-Card (MoC) — never leaves JCOP4 chip |
| Identity | EC public key stored in voter_registry; private key on card |
| Transport | AES-256-GCM terminal encryption |
| Verification | SHA256withECDSA card signature validated server-side |
| Anti-replay | Session token single-use + 5min TTL + card lock |
| Anonymity | ballot_box has zero FK to voter_registry |
| Integrity | SHA-256 Merkle tree — root published publicly |
| Audit | Hash-chained audit_log — verified every 5 min |
| Rate limit | 1 req/30s per IP on terminal endpoints (Bucket4j) |
| Card mgmt | card_locked flag; full lock/unlock history in card_status_log |
| mTLS ready | Uncomment SSL block in application.yml for production |
