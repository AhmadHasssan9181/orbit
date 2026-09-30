# Running your own Matrix homeserver for Orbit

This is the complete path: a homeserver you control, on your own network, that
Orbit can sign in to and make calls over.

Two halves, and you need both for calls to work:

| Half | What it is | Where |
|---|---|---|
| **Homeserver** | Synapse (or Tuwunel). Holds accounts, rooms, messages, E2EE. | `deploy/synapse/` |
| **Call stack** | LiveKit SFU + `lk-jwt-service` + coturn. Carries voice/video. | `deploy/` (this directory) |

Orbit talks to both. The homeserver is found by URL; the call stack is found
through the homeserver's `/.well-known/matrix/client`, or overridden in-app.

---

## Read this before you start

**Calls need a TURN relay that the internet can reach.** More on this below —
it is the one thing that most often surprises people.

**Do not put Synapse on a URL subpath.** Use `https://matrix.example.com`.
Orbit's URL handling supports subpaths and that is correct client behaviour, but
Synapse's own subpath support has caused real bugs (its OIDC callback cookie
path was hardcoded, and its `/.well-known/matrix/server` generator ignores
`public_baseurl` in a way that breaks federation). Root path only.

**Turn registration off until you have finished setting up.** Synapse's own docs
call open registration a known spam vector.

---

## Option A — Tailscale (recommended for a private group)

Best when everyone is on your network or willing to install Tailscale. Nothing
is exposed to the public internet, and you get a real Let's Encrypt certificate,
so there is no self-signed TLS problem anywhere.

### 1. A Tailscale tailnet

Install Tailscale on the server and on each client machine, join them to one
tailnet, and pick a **randomised** tailnet name — Tailscale publishes your
machine names into the public Certificate Transparency log when HTTPS is
enabled, and a random name keeps them out of it.

Your homeserver's name will be something like `matrix.yak-bebop.ts.net`.

### 2. Synapse

```bash
cd deploy/synapse
cp .env.example .env        # fill in SERVER_NAME, REGISTRATION_SHARED_SECRET
docker compose up -d
```

### 3. TLS, via Tailscale

```bash
tailscale serve --bg --https=443 tcp://localhost:8008
```

That terminates TLS with a valid certificate and proxies to Synapse on
localhost. No Caddy, no nginx. Your clients use:

```
https://matrix.yak-bebop.ts.net
```

Add a `/etc/hosts`-style note for the non-Tailscale case if you need one — but
the point is that every client joins the tailnet.

### 4. The call stack

```bash
cd deploy
cp .env.example .env        # LIVEKIT_URL=https://matrix.yak-bebop.ts.net:7880
docker compose up -d
```

Then tell Synapse about it — see **Wiring the call stack into Synapse** below.

### What works over Tailscale, and what does not

This is the part worth internalising:

- **Signalling** (the client's WSS connection to LiveKit) and the **JWT service**
  are ordinary HTTPS/WSS, and `tailscale serve` carries them fine.
- **Media** is WebRTC UDP/TCP. Tailscale carries that *directly* over
  WireGuard, so a group where everyone is on the tailnet connects peer-to-peer
  with no TURN involved.
- **But Tailscale Serve/Funnel are HTTP/TCP only.** They cannot proxy the
  LiveKit UDP range (7882, 50000-50200). So a client that is *not* on your
  tailnet cannot join media through Tailscale at all.

If everyone is on the tailnet, you are done. If you want people to join without
Tailscale, you need a publicly-reachable TURN relay (Option B) or Tailscale
Funnel (which only carries 443/8443/10000, so it fixes signalling but not media).

---

## Option B — Public domain (for mixed or remote groups)

Use this when participants are not all on your network.

1. Point a domain's DNS at the server's public IP.
2. Put a real reverse proxy (Caddy is the shortest config) in front of Synapse
   for `https://matrix.example.com` and in front of LiveKit for
   `wss://livekit.example.com`.
3. Open the LiveKit UDP range **and** coturn's relay port range in the firewall.
   `deploy/coturn/turnserver.conf` documents which.
4. Everything else is identical.

---

## Option C — Tuwunel, if you want it to just work and be fast

Tuwunel (`matrix-construct/tuwunel`) is the maintained successor to Conduit: a
single Rust binary, RocksDB, very low RAM. It has first-class MatrixRTC
configuration built in.

Its catch: the MatrixRTC integration is the simple `livekit_url` form rather
than the full appservice integration, so you give up the multi-SFU
cross-federation path. For a private server that is rarely the thing you need.

```toml
[global.well_known]
client = "https://matrix.example.com"
livekit_url = "https://livekit.example.com"   # Required for Element Call / MatrixRTC
```

Note: Conduit itself is gone (`gcaron/conduit` no longer exists, and both
`conduwuit` and `continuwuity` are community mirrors). Dendrite is archived.
Do not plan around either.

---

## Wiring the call stack into Synapse

Orbit finds the SFU through the homeserver's
`/.well-known/matrix/client`, under the `org.matrix.msc4143.rtc_foci` key. Two
ways to publish it.

### 1. `extra_well_known_client_content` (works with the compose in this directory)

Add to `homeserver.yaml`:

```yaml
extra_well_known_client_content:
  "org.matrix.msc4143.rtc_foci":
    - type: livekit
      livekit_service_url: "https://livekit.example.com"
```

### 2. Serve the file from your reverse proxy

Create `/.well-known/matrix/client` on the same origin as the homeserver:

```json
{
  "m.homeserver": {
    "base_url": "https://matrix.example.com"
  },
  "org.matrix.msc4143.rtc_foci": [
    { "type": "livekit", "livekit_service_url": "https://livekit.example.com" }
  ]
}
```

This file is served from the **origin root**, not from the homeserver's base URL.

### The newer endpoint

Recent Synapse versions advertise the same thing at
`GET /_matrix/client/v1/rtc/transports` (MSC4519) instead of via well-known.
matrix.org itself still serves only the older well-known form. Orbit reads
well-known, which matches both matrix.org and Tuwunel.

---

## Federation

A purely private server can set:

```yaml
federation_domain_whitelist: []
```

and skip the port-8448 listener entirely. The trade-off is that nobody outside
your server can invite you to their rooms, and you cannot invite them.

To federate, run the `federation` listener on 8448 and open it in the firewall.

---

## Creating your first account

1. Start with registration **off** and create the admin directly:

   ```bash
   docker compose exec synapse register_new_matrix_user \
     -u alice -p 'a-strong-password' -a
   ```

2. Turn on registration the way you actually want it. For a small private group,
   a registration token is the sane default — it is not an open server:

   ```yaml
   enable_registration: true
   registration_requires_token: true
   registration_shared_secret: "<a long random string>"
   ```

   Orbit asks for that token when the server demands it, so a group member can
   self-serve with a token you handed them.

3. Sign in to Orbit with `https://your-server` and create the account.

---

## Signing in from Orbit

The login screen takes a homeserver URL and normalises it. Past that it does the
ordinary Matrix login, with a few self-host specifics it now handles:

- **Registration** completes user-interactive auth. A self-hosted Synapse
  almost always answers the first `/register` with a 401 and a `flows` array —
  typically `m.login.dummy` (a bot check) and often `m.login.terms`. Orbit shows
  those as real steps and completes them, so this is a normal path rather than
  the "Registration failed" wall it used to be.
- **The SSO button hides itself** when the homeserver does not advertise
  `m.login.sso`, instead of offering a button that always fails.
- **The SFU URL** comes from the well-known file, or you can override it in
  *Settings → Voice & Video*.

---

## When something does not work

**Calls connect then everyone is deaf.** Usually E2EE media keys: a participant's
key arrived while the key provider was not ready and was not installed. See
`docs/planning/ESCAPE_FROM_HELL.md` §H8 — this is the single most intricate area of the
codebase.

**"Not supported on the transport" / no media at all.** The TURN relay is
unreachable. Check that the LiveKit UDP range and coturn's range are open, and
that `livekit.yaml`'s turn credentials match `turnserver.conf`'s
`static-auth-secret`.

**Discovery returns the wrong SFU.** Check what
`curl https://your-server/.well-known/matrix/client` actually returns, and
whether it is being served from the origin root.

**A plain-HTTP homeserver.** Orbit warns but allows it, which sends your access
token in cleartext. Use HTTPS. With Tailscale or a real certificate you never
need to.

---

## Files

```
deploy/
  docker-compose.yml          LiveKit + lk-jwt-service + coturn
  livekit.yaml                SFU config, including the TURN section
  coturn/turnserver.conf      TURN relay; static-auth-secret must match livekit.yaml
  .env.example                copy to .env and fill in
  synapse/
    docker-compose.yml        Synapse + Postgres
    homeserver.yaml           annotated config for a private, call-capable server
    .env.example
    README.md                 the Synapse-specific steps
```
