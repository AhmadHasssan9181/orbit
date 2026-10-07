# Security Policy

Orbit is a Matrix client that handles end-to-end-encrypted messages and calls,
holds a long-lived account access token, and signs media keys for group calls.
A vulnerability report here can mean "someone else can read my messages".

## Supported versions

| Version | Supported |
|---------|-----------|
| latest `main` | yes |
| anything older | no — update first |

## Reporting a vulnerability

Please report privately rather than opening a public issue. Use GitHub's
**Security → Report a vulnerability** on this repository, which opens a private
advisory.

Include:

- what an attacker can do, and what they need in order to do it;
- the file and line, if you have one;
- how to reproduce — a room with two controlled accounts is the most useful thing
  you can attach;
- whether any existing credential should be treated as compromised.

Aim to give the maintainer a few days before public disclosure. If a report is
already public somewhere, say so in the advisory rather than assuming it is not.

## What is in scope

- E2EE message or media-key confidentiality, in chat or in calls.
- Access-token disclosure: anywhere the token can reach a host it should not, or
  be written in a place it should not be.
- Anything that lets a room member, homeserver operator, or SFU operator read
  content that Orbit's UI presents as end-to-end encrypted.
- Local privilege or credential exposure on a shared machine.

## Out of scope

- A homeserver or SFU operator reading content in a room that is **not**
  encrypted. Orbit shows no encryption indicator distinction for unencrypted
  rooms, and that matches Element; it is a UX question, not a vulnerability.
- Denial of service through sheer volume (call floods, notification storms).
- Findings that need a compromised build host or a modified client.
- Missing hardening with no concrete path to a credential or plaintext message —
  though if you think that judgement is wrong, say so and explain the path.

## Things a reporter should know

These have come up in this codebase and are worth stating up front so a report
is not wasted re-discovering them.

**Every Matrix client sees plaintext for the rooms it is in.** The homeserver
relays ciphertext; a malicious homeserver can substitute its own keys and read
everything. Orbit verifies device-key signatures and refuses unverifiable
one-time keys precisely because of this. A report that a hostile homeserver can
read a room is correct and expected — what matters is whether Orbit *verifies*
what it is given.

**Group calls have two separate channels.** Media keys travel over Matrix
(olm-encrypted per-device, never through the SFU); media frames travel through
the SFU and are encrypted with those keys. Compromising the key exchange defeats
both. Compromising the SFU alone defeats neither.

**A username is not a secret**, and neither is a room id, a device id, or a
message id. Orbit's own `AGENTS.md` says Matrix ids are safe to log; it is the
access token, passwords, recovery keys and message bodies that are not.

## If a credential is exposed

Rotate it at the homeserver. Orbit's **Settings → Account → Log out** does a
server-side `POST /_matrix/client/v3/logout`, so it revokes the token properly
and wipes the local key store. Deleting a file from a git repository is *not*
enough — a credential in history stays in every clone that has pulled it, so the
history has to be rewritten as well.