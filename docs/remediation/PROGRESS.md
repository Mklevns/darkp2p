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
