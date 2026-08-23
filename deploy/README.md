# Self-hosted MatrixRTC stack for Orbit

Brings up everything needed to run group calls against **your own** infrastructure
instead of the public matrix.org dev-fallback SFU:

| Service | Image | Role |
|---|---|---|
| `livekit` | `livekit/livekit-server` | SFU — relays audio/video between participants |
| `livekit-jwt` | `ghcr.io/element-hq/lk-jwt-service` | Exchanges Matrix OpenID tokens for LiveKit access tokens (`POST /get_token`, legacy `POST /sfu/get`) |
| `coturn` | `coturn/coturn` | TURN/STUN relay for restrictive networks |

## Quick start

1. `cp .env.example .env` and fill in:
   - `LIVEKIT_URL` — public `wss://` signaling URL of your LiveKit host
   - `LIVEKIT_KEY` / `LIVEKIT_SECRET` — API key pair (`openssl rand -hex 32`)
2. Edit `livekit.yaml`: set `turn.domain` and (behind NAT) `external_ip`.
3. Edit `coturn/turnserver.conf`: set the same `static-auth-secret` as `livekit.yaml`,
   plus `realm` / `external-ip`.
4. `docker compose up -d`

Open firewall ports: `7880/tcp`, `7881/tcp`, `7882/udp`, `50000-50200/udp` (LiveKit),
`3478/tcp+udp`, `5349/tcp`, `49160-49200/udp` (coturn).

## Pointing clients at your SFU

Orbit resolves the SFU endpoint in this order:

1. **In-app override** — Settings → Voice & Video → SFU URL (stored in app settings).
2. **Homeserver `.well-known`** — `GET https://<homeserver>/.well-known/matrix/client`
   containing:
   ```json
   {
     "org.matrix.msc4143.rtc_foci": [
       { "type": "livekit", "livekit_service_url": "https://livekit.example.com" }
     ]
   }
   ```
   This is the same mechanism Element Call uses (MSC4143), so Element and Orbit
   converge on the same focus automatically.
3. **Public dev fallback** — `https://livekit-jwt.call.matrix.org`. This is a
   **development fallback only**; it is rate-limited, not guaranteed available, and
   should never be relied on for production use.

The JWT service must be reachable at the `livekit_service_url` you advertise — it
serves both `/get_token` and the legacy `/sfu/get` path, matching Orbit's token
client.

## Homeserver-side notes

- The JWT service validates the user's `m.login.openid_token` against the homeserver
  listed in `LIVEKIT_ALLOW_HOMESERVERS` (comma-separated; unset = allow any).
- If your homeserver also runs Element Call, configure its `livekit_service_url` the
  same way so both clients join the same rooms on the same SFU.
- TURN credentials are minted by LiveKit via the REST shared-secret mechanism; keep
  `static-auth-secret` identical in `livekit.yaml` and `turnserver.conf`.
