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
