# Environment Variable Setup

## Local Development (.env.local at project root)

```
# Leave VITE_API_URL empty in dev — the Vite proxy handles routing to the backend
# This way the browser never sees the self-signed cert on localhost:8443
VITE_API_URL=
VITE_WS_URL=
VITE_DEMO_MODE=false
```

The Vite proxy (vite.config.js) will forward all `/api/*` requests to
`https://localhost:8443` automatically, accepting the self-signed cert.

## Production (Vercel Environment Variables)

Set these in your Vercel project dashboard → Settings → Environment Variables:

```
VITE_API_URL=https://your-actual-backend-domain.com
VITE_WS_URL=https://your-actual-backend-domain.com
VITE_DEMO_MODE=false
```

**Do NOT set VITE_API_URL to an IP address or a domain with a self-signed cert
in production.** Browsers block untrusted certificates on HTTPS pages.

## Backend (application.yml or environment variables)

```
# Allow both your Vercel domain AND localhost dev:
CORS_ALLOWED_ORIGIN=https://your-app.vercel.app,http://localhost:3000,http://localhost:5173
```

Or in application.yml:
```yaml
security:
  cors:
    allowed-origin: "https://your-app.vercel.app,http://localhost:3000,http://localhost:5173"
```

## Why login fails on HTTPS

The most common causes:

1. **Backend not reachable** — `VITE_API_URL` points to an IP/domain the browser
   can't reach, or the port is blocked by a firewall.

2. **Self-signed certificate** — If the backend uses a self-signed cert and
   `VITE_API_URL` is set to `https://localhost:8443` in production (Vercel),
   the browser will refuse the connection. Use the Vite proxy in dev instead.

3. **CORS mismatch** — The `CORS_ALLOWED_ORIGIN` on the backend doesn't include
   your Vercel URL. Check the browser Network tab: if you see a `CORS` error on
   the preflight `OPTIONS` request, this is the cause. Add your Vercel domain to
   `CORS_ALLOWED_ORIGIN`.

4. **HTTP vs HTTPS** — An HTTPS Vercel page cannot call an HTTP backend
   (mixed content is blocked). The backend must be on HTTPS with a valid cert.
