# Synapse, the homeserver half of the Orbit self-hosted stack

The full guide — including Tailscale, federation, and wiring up the call stack —
is in [`../README.md`](../README.md). This file is the Synapse-specific
shortcut.

## 1. Fill in the env

```bash
cp .env.example .env
```

- `SERVER_NAME` — what clients will use. With Tailscale this is your MagicDNS
  name (`matrix.yak-bebop.ts.net`), which is a valid Matrix server name and gets
  a real Let's Encrypt certificate from `tailscale serve`.
- `REGISTRATION_SHARED_SECRET` — `openssl rand -hex 32`.
- `POSTGRES_PASSWORD` — `openssl rand -base64 24`.

## 2. Start it

```bash
docker compose up -d
```

The first run generates `data/homeserver.yaml`. Merge the keys from
[`homeserver.yaml`](./homeserver.yaml) in this directory into it — the
annotations there explain which ones are load-bearing and which are optional.

The ones that are **required for calls** and easy to miss:

- `experimental_features`: `msc3266_enabled`, `msc4143_enabled`,
  `msc4222_enabled`. Without them Synapse rejects the MatrixRTC state events
  and no call ever forms.
- `max_event_delay_duration: "24h"` plus raised `rc_message` and
  `rc_delayed_event_mgmt` limits. Call membership uses delayed events; the
  default budget is too small and peers never see each other join.
- An `openid` resource on the federation listener (already set in
  `docker-compose.yml`). `lk-jwt-service` uses it to validate the user's OpenID
  token before issuing a LiveKit access token.
- `enable_encryption: true`. Orbit's calls are E2EE.

## 3. TLS

For Tailscale, nothing else is needed:

```bash
tailscale serve --bg --https=443 tcp://localhost:8008
```

For a public domain, put Caddy or nginx in front on 443 and set
`public_baseurl` accordingly. Do **not** put Synapse on a URL subpath — root
path only; see the main README for why.

## 4. Create your first account

With registration closed (the default):

```bash
docker compose exec synapse register_new_matrix_user -u alice -p 'a-strong-password' -a
```

Then enable token-gated registration in `homeserver.yaml` so group members can
self-serve:

```yaml
enable_registration: true
registration_requires_token: true
registration_shared_secret: "<matches .env>"
```

Orbit asks for that token when the server demands it, so a member can register
with a token you handed them without you opening the server to the world.

## 5. Point Orbit at it

Enter the homeserver URL on the login screen:

```
https://matrix.yak-bebop.ts.net     # or https://matrix.example.com
```

Registration completes the `m.login.dummy` / `m.login.terms` stages that a
self-hosted Synapse normally requires, and the SSO button hides itself when
the server does not advertise it.

Calls need step 6.

## 6. Add the call stack

See [`../README.md`](../README.md) § "Wiring the call stack into Synapse" — the
LiveKit SFU, `lk-jwt-service` and coturn live in the parent directory, and
Synapse needs to advertise the SFU URL via
`extra_well_known_client_content` or a `/.well-known/matrix/client` file.
