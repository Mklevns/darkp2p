# Remediation progress

## Log

### Step 0 — Test harness and rules (2026-11-21)
- **Tasks:** 0.A, 0.1, 0.2, 0.3, 0.4
- **Files changed:** `package.json` (modified); `docs/remediation/RULES.md`, `docs/remediation/PROGRESS.md`, `scripts/check-inline-js.mjs`, `test/helpers/server.mjs`, `test/baseline.test.mjs` (new). `server.js` and `public/` are unchanged.
- **Gate:** `npm run check` passed (server.js syntax OK, 1 inline script block OK). `npm test` passed 11/11.
- **Deviation:** the test script is `node --test "test/**/*.test.mjs"`, not `node --test test/`. Node 22 treats `--test` arguments as globs, and a bare directory fails with MODULE_NOT_FOUND. The glob also stops `test/helpers/` from running as a test file.
- **Verified:** mutation check. Breaking the inline script makes `check-inline-js` exit 1 with file and line. Reverted.
- **Notes:** `npm ci` was needed (no node_modules). `package-lock.json` is unchanged; its root entry does not list `engines`, and a lockfile refresh can add it later.
- **SKIPPED:** none.
- **Open questions:** none.

### Step 1 — Compose env merge, onion identity isolation (2026-11-21)
- **Tasks:** 1.A, 1.B
- **Files changed:** `docker-compose.yml`, `server.js`, `test/helpers/server.mjs` (exports `freePort`); new: `test/fixtures/compose.env`, `test/compose.test.mjs`, `test/onion.test.mjs`.
- **1.A:** darkp2p now relies on `env_file: [.env]` (trailing space removed). `environment:` is only `PORT=3000` and `ONION_PORT=3001`, with no interpolation. Caddy `DOMAIN=${DOMAIN}` is kept.
- **1.B:** new `ONION_PORT` setting and a second listener, `onionServer`, built with `createListener()`. The two listeners share the named `onRequest`/`onUpgrade` handlers and have their own error handlers. `isOnionSocket()` decides by `localPort` and replaces `isOnionRequest()`, which was removed. The onion listener never reads X-Forwarded-For, and `Onion-Location` is sent only on the main listener. Startup is refused when `ONION_HOST` is set without `ONION_PORT`, or when `ONION_PORT` equals `PORT`. `ONION_PORT` without `ONION_HOST` logs a warning. `shutdown()` closes both servers and exits when both have closed. The listen log shows both ports. The tor torrc points to `darkp2p:3001`.
- **Gate:**
  - `npm run check`: PASS.
  - `npm test`: 20/20 PASS (11 baseline + 3 compose + 6 onion).
  - `docker compose --env-file test/fixtures/compose.env config -q`: PASS (exit 0), run with a temporary `.env` copied from the fixture. No `.env` existed beforehand, and the temporary one was removed afterwards.
- **Verified:** all 9 new tests fail against the Step 0 `server.js` and `docker-compose.yml`.
- **SKIPPED:** none (docker available).
- **Open questions:**
  1. Because `env_file: .env` is mandatory, the literal gate command fails in a checkout without `.env`. Options: keep the temporary-.env approach, or make it `required: false` (a missing `.env` would then boot with Pro disabled).
  2. Existing deployments that set `ONION_HOST` without `ONION_PORT` now refuse to start. This is intended per 1.B; compose sets `ONION_PORT=3001`. `.env.example` and `README.md` do not mention `ONION_PORT` yet.

### Step 2 — coturn logging and relay hardening (2026-11-21)
- **Tasks:** 2.A, 2.B, 2.C
- **Files changed:** `docker-compose.yml`, `test/compose.test.mjs`.
- **2.A:** `--log-file=stdout` is replaced by `--no-stdout-log` and `--log-file=/dev/null`. coturn has `logging: { driver: "none" }`, with a comment on enabling logging temporarily for debugging and cleaning up afterwards.
- **2.B:**
  - Added `--no-tcp-relay` and `--total-quota=40`.
  - Added the deny ranges 0/8, 127/8, 192.0.0/24, 198.18/15, 240/4, `::`, `::ffff:0:0/96`, 64:ff9b::/96 and 2002::/16, plus `${TURN_PUBLIC_IP:?…}` (compose refuses to render without it).
  - `"--denied-peer-ip=::-::"` is quoted, because unquoted YAML parses it as a mapping key and compose rejects it ("command.30 must be a string").
  - The image is pinned to `coturn/coturn:4.6.3-r3-alpine`, the newest exact 4.6.x alpine tag on Docker Hub. It is not pinned by digest.
- **2.C (flag verification):** `docker run --rm coturn/coturn:4.6.3-r3-alpine --help` lists every flag used except **`--no-loopback-peers`**. That image has only the opt-in `--allow-loopback-peers`, so the flag was removed and the explicit 127/8 deny (plus the existing `::1` deny) covers it.
  - The image also started cleanly with the fully rendered command (stdout logging enabled for that check only) and was still running at the 8 s timeout, with no "unrecognized option" or "Wrong address format" lines.
  - A negative control with a bogus flag and range produced both errors, so the check is meaningful.
- **Gate:**
  - `npm run check`: PASS.
  - `npm test`: 22/22 PASS, 0 skipped. The 2 new compose tests fail against the Step 1 compose file.
  - `docker compose --env-file test/fixtures/compose.env config -q`: PASS (exit 0), run with a temporary `.env` copied from the fixture and removed afterwards, as in Step 1.
- **Environment note:** the docker daemon was not running in this container, so it was started locally (`dockerd`) for the image checks.
- **SKIPPED:** none.
- **Open questions:**
  1. Should the coturn image be pinned by digest as well as by tag?
  2. `TURN_PUBLIC_IP` is now required by compose but not documented in `.env.example` or `README.md`.
- **Follow-up (requested by user):** `.env.example` and `README.md` now document the Step 1–2 changes:
  - `TURN_PUBLIC_IP` is required.
  - `.env` is the only config source (`env_file`).
  - The onion listener is `ONION_PORT=3001`. `ONION_HOST` without `ONION_PORT` refuses to start, and the README has steps to enable the onion mirror.
  - TURN relaying is UDP-only and the peer deny list is wider.
  - coturn logging is disabled.
  - Four containers.
  - This resolves Step 1 open question 2 and Step 2 open question 2. The gate was re-run: check PASS, 22/22 tests PASS.

### Decisions after Step 2 (user, 2026-11-21)
- **coturn pin:** coturn is pinned by tag and digest: `coturn/coturn:4.6.3-r3-alpine@sha256:e2bca2f7…46be`. This is the multi-arch manifest-list digest from `docker buildx imagetools inspect`, and it matches the RepoDigest of the image flag-checked in Step 2. The update procedure is a comment above the `image:` line. The compose test now requires the tag+digest form.
- **Compose gate:** run it with `npm run check:compose` (`scripts/compose-gate.sh`).
  - It creates a temporary `.env` from `test/fixtures/compose.env` with noclobber, runs `docker compose --env-file test/fixtures/compose.env config -q`, and removes the `.env` through an EXIT trap. INT, TERM and HUP are routed through that trap.
  - It refuses (exit 2) if a `.env` already exists, and prints SKIPPED if docker compose is missing.
  - Verified paths: success, compose error, existing `.env` (left untouched) and SIGTERM mid-run. In every case no `.env` is left behind. `.env` is also gitignored.
  - Later steps' "docker compose … config -q" gate means `npm run check:compose`.
- **Gate re-run:** `npm run check` PASS; `npm test` 22/22 PASS; `npm run check:compose` PASS. `shellcheck` is SKIPPED for `scripts/compose-gate.sh` (tool unavailable).
