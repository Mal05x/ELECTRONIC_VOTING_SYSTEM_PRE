# eVoting Admin Frontend — v4.0

Purple & black admin dashboard for the eVoting Spring Boot backend (v4.0).

## Tech Stack

| Layer | Choice |
|-------|--------|
| Framework | React 18 + Vite 5 |
| Styling | Tailwind CSS 3 + custom CSS (index.css) |
| Routing | React Router v6 |
| HTTP | Axios (JWT interceptor, auto-logout on 401) |
| Charts | Recharts |
| WebSocket | STOMP over SockJS (live tally) |
| Auth | JWT stored in localStorage |

## Project Structure

```
src/
  api/
    client.js       ← Axios instance + JWT interceptor
    auth.js         ← POST /api/auth/login
    elections.js    ← GET/POST /api/admin/elections
    voters.js       ← GET/PUT  /api/admin/voters
    enrollment.js   ← GET/POST /api/admin/enrollment + /api/terminal/*
    tally.js        ← GET      /api/results/*
    audit.js        ← GET      /api/admin/audit
    mock.js         ← Full mock data (used when VITE_DEMO_MODE=true)
  context/
    AuthContext.jsx ← Login/logout state, JWT management
  hooks/
    useWebSocket.js ← STOMP WebSocket + polling fallback
  components/
    ui.jsx          ← Shared primitives (StatCard, Modal, Pbar, Ic…)
    Layout.jsx      ← Sidebar + Topbar
  pages/
    LoginPage.jsx
    DashboardView.jsx
    ElectionsView.jsx
    VotersView.jsx
    EnrollmentView.jsx
    TallyView.jsx
    AuditView.jsx
  App.jsx           ← Router + shell
  main.jsx          ← Entry point
  index.css         ← Tailwind + global styles
```

## Local Development

```bash
# 1. Install dependencies
npm install

# 2. Configure environment
cp .env.example .env.local
# Edit .env.local and set VITE_API_URL=https://localhost:8443

# 3. Run dev server (proxies /api → Spring Boot)
npm run dev
# App runs at http://localhost:3000

# 4. Build for production
npm run build
```

### Demo mode (no backend required)

```bash
VITE_DEMO_MODE=true npm run dev
# Login: superadmin / password
```

## Deploy to Vercel

### One-click deploy
1. Push this folder to a GitHub/GitLab repo
2. Import into Vercel: https://vercel.com/new
3. Vercel auto-detects Vite — framework preset is set in `vercel.json`
4. Set these environment variables in Vercel project settings:

| Variable | Value |
|----------|-------|
| `VITE_API_URL` | `https://your-backend-host.com` |
| `VITE_WS_URL`  | `https://your-backend-host.com` |
| `VITE_DEMO_MODE` | `false` |

5. Deploy → done. All SPA routes are handled by `vercel.json` rewrites.

### CLI deploy
```bash
npm i -g vercel
vercel login
vercel --prod
```

## Backend Integration

All API calls map directly to **evoting-backend-v4** endpoints:

| Frontend API file | Backend endpoint |
|---|---|
| `api/auth.js` | `POST /api/auth/login` |
| `api/elections.js` | `GET/POST /api/admin/elections` |
| `api/voters.js` | `GET/PUT /api/admin/voters` |
| `api/enrollment.js` | `POST /api/admin/enrollment/queue`, `GET /api/terminal/pending_enrollment` |
| `api/tally.js` | `GET /api/results/{electionId}` |
| `api/audit.js` | `GET /api/admin/audit` |

### CORS
Ensure `CORS_ALLOWED_ORIGIN=https://your-vercel-app.vercel.app` is set
in the Spring Boot backend environment (maps to `SecurityConfig.java` Fix B-12).

### WebSocket (Live Tally)
The frontend connects to `${VITE_WS_URL}/ws` using STOMP over SockJS and
subscribes to `/topic/results/{electionId}` and `/topic/merkle/{electionId}`.

Spring Boot WebSocket config must expose these destinations.
The JWT token is sent in the STOMP `connect` header for authentication.

## Security Notes

- JWT stored in `localStorage` — adequate for admin-only internal tools.
  For public-facing deployments consider `httpOnly` cookies instead.
- All routes behind `RequireAuth` — 401 responses auto-redirect to `/login`.
- Security headers set in `vercel.json` (X-Frame-Options, CSP, etc.).
