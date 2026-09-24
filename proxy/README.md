# AniBlaze API proxy (Cloudflare Worker)

Reverse-proxy for `api.anixart.tv`, which RKN blocked in Russia (2025-06-05).
The app hits the real API directly; only when that fails (RF without VPN) does it
fall back to this Worker. Posters and video are on un-blocked hosts and are never
proxied.

## Deploy (≈5 min, free, no server)

### Option A — dashboard (no tools)
1. Go to https://dash.cloudflare.com → sign in (free account).
2. Left menu: **Workers & Pages** → **Create application** → **Create Worker**.
3. Name it `aniblaze-api` → **Deploy**.
4. Click **Edit code**, delete the template, paste the contents of `worker.js`,
   then **Deploy** again.
5. Copy the URL shown, e.g. `https://aniblaze-api.<your-subdomain>.workers.dev`.
6. Send that URL back so it can be baked into the app.

### Option B — wrangler CLI
```bash
npm i -g wrangler
wrangler login
cd proxy
wrangler deploy worker.js --name aniblaze-api
```
The deploy prints the `*.workers.dev` URL.

## Verify it works
```bash
# Should return JSON (a catalog page) just like the real API:
curl -s -X POST "https://aniblaze-api.<your-subdomain>.workers.dev/filter/0?perPage=2" \
  -H "Content-Type: application/json" -d "{\"sort\":4}" | head -c 300
```

## Notes
- Free plan = 100,000 requests/day. One Home open ≈ 20 requests, so this covers
  a few thousand sessions/day. Enough for moderation + normal use.
- If `*.workers.dev` ever gets throttled in RF, the same logic drops onto a VPS:
  put `worker.js`'s forwarding behind nginx and point the app at that domain
  instead — only the host string in the app changes.
