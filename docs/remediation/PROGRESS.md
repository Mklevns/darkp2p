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

### Step 3 — Durable Pro token claims (2026-11-21)
- **Tasks:** 3.A, 3.B
- **Files changed:** `server.js`, `public/index.html`; new `test/claim.test.mjs`.
- **3.A (server):**
  - `CLAIM_TTL_MS` = `CLAIM_TTL_HOURS` (default 48, range 1–168) × 1 h, with a fatal error if it is over `INVOICE_DEDUPE_MS`.
  - `mintProTokenAt(expUnix)` added; `mintProToken(days)` wraps it, so the token format is unchanged.
  - `fetchInvoiceItemCode` is replaced by `fetchInvoice`, which returns `{status, createdTime, itemCode, storeId}`. Types are validated, `redirect:'error'` and the timeout are kept, and a BTCPay 404 returns null. Other non-2xx statuses or a malformed body throw a `BtcPayError` with a code and status.
  - `tokenForInvoice()` gives a deterministic `exp = createdTime + days*86400 + 6h`. It requires `Settled`, a matching store and a known tier, and refuses if `exp <= now`.
  - With lookup enabled, both the webhook and claim paths use it. The webhook now always looks the invoice up and returns 502 on failure so BTCPay retries. Without lookup, the now-based expiry is unchanged.
  - `handleClaim` is async and awaited. On a cache miss it checks the negative cache (`claimMisses`: 30 s TTL, 5000 max, oldest evicted first, swept), then calls BTCPay. It returns 503 with `Retry-After: 30` when BTCPay is unreachable and 404 otherwise.
  - `invoices` is capped at 20000; when full, the earliest `forgetAt` is evicted.
  - A webhook `timestamp` more than 30 days old returns 200 `ignored` with `logWarn('Stale webhook ignored')`.
  - Lookup errors log only `name`, `code` and `status` (`describeLookupError`).
- **3.B (client):**
  - `{invoiceId, startedAt}` is persisted to `localStorage['darkp2p_pending_claim']` before the URL is cleaned. A pending claim with the same ID keeps its original `startedAt`.
  - A pending claim is resumed on every load. Polling backs off 3 s → 30 s for up to 6 h, honours Retry-After on 429/503 (capped at 10 min), treats 404 as keep polling, and clears the claim on success or once it is more than 48 h old.
  - `ws.onopen` waits for at most the first attempt (capped at 5 s). A token that arrives after the join logs "Pro token saved — reload to apply."
- **Gate:**
  - `npm run check`: PASS.
  - `npm test`: 32/32 PASS (22 previous + 10 claim).
  - 7 of the 10 claim tests fail against the Step 2 `server.js`. The other 3 (unknown itemCode, other-store/missing/expired, webhook 502) already passed before.
- **Extra verification (not committed):** a Playwright and Chromium run against a real server plus an HTTPS mock confirmed:
  - the join is sent at 0.2 s while the claim is still pending;
  - the pending claim is persisted and the URL stripped;
  - a reload resumes it;
  - the token arrives at about 45 s (30 s negative cache plus backoff) with the reload message;
  - the pending claim is cleared on success, and an entry older than 48 h is cleared on load;
  - BTCPay was hit only twice.
- **SKIPPED:** none (openssl available).
- **Open questions:**
  1. With lookup enabled, the claim path mints for any settled invoice until the token's own expiry (+6 h), even after `CLAIM_TTL` has passed, because a restart clears the RAM entry whose `claimUntil` would have returned 404. In practice the invoice ID is a bearer credential for the token's lifetime. Should lookup minting also be refused when `now > createdTime + 6h + CLAIM_TTL`?
  2. With lookup enabled, the webhook now calls the BTCPay API for every settled invoice (before, only when metadata was missing), and it ignores the payload's `itemCode` in favour of the invoice's own.
  3. `CLAIM_TTL_HOURS` is not yet documented in `.env.example` or `README.md`.

### Decisions after Step 3 (user, 2026-11-21)
- **Claim window cutoff:** `tokenForInvoice()` now returns null once `now > createdTime + 6h + CLAIM_TTL`. The webhook and claim paths share this rule, so a leaked invoice ID cannot re-fetch the token after a restart. The response is 404, which the client already treats as keep polling until its own 48 h limit.
  - New tests: past-window gives 404 while just-inside mints; a claim past the window after a restart gives 404; a webhook past the window returns `ignored`.
  - All 3 fail with the cutoff disabled.
- **Webhook behaviour:** kept. It always looks the invoice up and uses its authoritative `metadata.itemCode`.
  - The timeout stays at `BTCPAY_API_TIMEOUT_MS` = 5 s, and any lookup error (timeout, network, non-404 status, bad body) returns 502 so BTCPay retries.
  - New test: a hanging mock gives 502 in about 5 s and logs `name=TimeoutError`.
- **Docs:** `CLAIM_TTL_HOURS=48` is now in `.env.example` (with a comment) and in the README `.env` example. The README also explains the claim flow and window, and the optional lookup variables (`BTCPAY_URL`, `BTCPAY_API_KEY`, `BTCPAY_STORE_ID`) that make claims restart-safe.
- **Gate re-run:** `npm run check` PASS; `npm test` 36/36 PASS.
- Step 3 open questions 1–3 are resolved.

### Step 4 — Relay memory pinning, control-message budget (2026-11-21)
- **Tasks:** 4.A, 4.B
- **Files changed:** `server.js`; new `test/relay.test.mjs`.
- **4.A:**
  - `RELAY_HIGH_WATER` is now 512 KB and `RELAY_LOW_WATER` 128 KB.
  - `HEARTBEAT_MS` is env-tunable (1000–300000, default 30000). New settings: `RELAY_STALL_MS` (1000–600000, default 60000) and `MAX_RELAY_ROOMS` (1–10000, default 50).
  - `setBackpressured()` keeps `backpressuredSince` consistent wherever `backpressured` changes (relay send, its drain callback and `leaveRoom`).
  - The heartbeat still skips pings to paused sockets, but now runs `checkRelayStall()` on them. If a socket has been paused for backpressure for more than `RELAY_STALL_MS` and its receiver is still OPEN, both peers get `{type:'error', reason:'relay-stalled'}` and the receiver is closed with `1008 'relay-stalled'`. The OPEN check stops the notice repeating while that close completes.
  - When `ws-fallback` arrives in a non-relay room and the number of `relayActive` rooms (counted by iterating `rooms`) is at the cap, the sender gets `relay-busy`, `relayActive` is not set and nothing is forwarded.
- **4.B:**
  - `MAX_MSGS_PER_WINDOW` is now 120.
  - A per-socket `candidateCount` resets on join, when a second peer arrives (for both peers), and for the remaining peer on `leaveRoom`. Above 200 sanitized candidates the socket is closed with `1008 'too-many-candidates'`, and the 201st candidate is not forwarded.
- **Tests (5):**
  - The stall test fires at about 3 s (2 s stall plus 1 s heartbeat granularity) and the receiver gets 1008 `relay-stalled`.
  - The receiver must keep sending control messages while not reading, as in the root cause. A silent receiver is already killed by the existing pong check, so the stall path would never be reached.
  - Also: `relay-busy`, with no forward, no relaying, and the slot freed when the first room closes; 120 messages allowed and the 121st closes with 1008; 200 candidates allowed and the 201st closes with `too-many-candidates` and is not forwarded; the candidate budget resets on a new peer.
  - All 5 fail against the Step 3 `server.js`. The stall test's keepalive and pump timers are cleaned up in `finally`, so a failure cannot hang the runner.
- **Gate:** `npm run check` PASS; `npm test` 41/41 PASS.
- **SKIPPED:** none.
- **Open questions:**
  1. The stalled receiver gets a graceful `close(1008)`. Because it is not reading, the close handshake cannot finish, so its queued data (at most the 512 KB high-water plus in-flight frames) is only freed when `ws` destroys the socket after its 30 s close timeout. `terminate()` would free it at once, but the receiver would then never see the 1008 code. Kept as specified.
  2. With `MAX_MSGS_PER_WINDOW = 120`, a client sending more than about 119 candidates within 10 s still hits the message limit before the 200-candidate cap.
  3. The new env settings (`HEARTBEAT_MS`, `RELAY_STALL_MS`, `MAX_RELAY_ROOMS`) are not documented in `.env.example` or `README.md` (this step was limited to `server.js` and tests).

### Step 5 — Client wire format v2 (MIGRATE) (2026-11-21)
- **Tasks:** 5 (v2 framing, per-transfer keys, role-bound AAD, hello handshake, receiver checks, README, tests).
- **Files changed:** `public/index.html`, `README.md` ("Wire Format" section only); new `test/framing.test.mjs`. `server.js` is unchanged.
- **Framing block** (`// ==BEGIN darkp2p-framing==` … `// ==END darkp2p-framing==`):
  - Exports `importRoomKey`, `deriveTransferKey`, `encodeFrame({type, role, transferId, seq, payload, key})` and `decodeFrame(buf, peerRole, keyLookup)`, plus `createTransferKeyCache` (LRU, max 8, keyed by hex transferId, holding derivation promises), `FrameError` (`code`: `legacy` | `malformed` | `auth`) and the constants.
  - It uses only `crypto.subtle`, `crypto.getRandomValues` and typed arrays. ASCII constants are built with `charCodeAt`, not `TextEncoder`.
  - Header: `type | transferId(16) | seq(4, BE) | iv(12)`, 33 bytes, overhead 49. AAD = `"DP2" | type | senderRole | transferId | seq`.
  - `keyLookup(transferId, {type, seq})` may reject, so the app refuses frames before running HKDF or decrypting.
- **App:**
  - `FRAME_CTRL`/`FRAME_DATA`, `cryptoKey`/`keyReady`, `encryptFrame`/`decryptFrame` are removed.
  - Roles come from `ready.initiator`.
  - The hello is sent on DataChannel `onopen` and in `activateWsFallback`. The drop zone is enabled only through `enableDropIfReady()` (hello verified, not transferring, channel open), which replaces the four direct `setDropEnabled(true…)` re-enable sites. `sendFile` also refuses unless the handshake is `ok`.
  - An auth failure before the hello is verified shows the exact "Key mismatch" message, and a v1 type byte or the 5 s timeout shows the exact "outdated" message. Both are shown in the log and as the drop label. Both states stay until the next `ready` or `peer-left`.
  - Receiver checks follow the spec: the transferId must match the active transfer, seq must equal expectedSeq, bytes may not exceed `meta.size`, and `done` must have the right seq and counts. `meta.size` must be a safe non-negative integer.
- **Additions beyond the spec (please confirm):**
  1. A `meta` or `hello` whose transferId was already seen in this page load is rejected (to stop a relay replaying a whole earlier file). The set is capped at 64, oldest evicted.
  2. `meta.chunks` is validated: a safe integer, `size 0 ⇔ chunks 0`, otherwise `1 ≤ chunks ≤ size` and `≤ 2³²−2` so `done` fits in seq. The receiver also rejects more chunks than announced.
  3. After a transfer is aborted, its remaining frames are dropped silently (`droppedTransferHex`) instead of logging one error per chunk.
  4. `relay-busy` clears the hello timer, so a relay refusal is not reported as an outdated peer.
  5. An authenticated hello with `v !== 2` is treated as "outdated".
- **Tests (`test/framing.test.mjs`, 14):**
  - Header is 33 bytes and overhead 49.
  - CTRL and DATA round-trip, from both Uint8Array and ArrayBuffer input.
  - Every one of the 264 header bit flips fails. Ciphertext and tag flips fail.
  - Reflection: in both directions and for both types, a frame decoded by its own sender with its peer's role fails, while the honest peer decodes it.
  - Splice, both variants: B's key with A's header, and A's frame with its transferId rewritten to B's.
  - A different room key fails. v1 types give `legacy`. Truncated frames and unknown types give `malformed`. A rejecting `keyLookup` propagates.
  - A 16000-byte payload gives exactly 16049 bytes. The LRU holds 8 keys and evicts the least recently used. `encodeFrame` and `importRoomKey` validate their inputs.
- **Mutation check:** these were each applied and then reverted, with the file restored byte-identical:
  - role removed from the AAD: the reflection test fails;
  - transferId removed from the AAD plus a constant HKDF salt: the header-flip, splice and distinct-keys tests fail;
  - seq removed from the AAD: the header-flip test fails.
- **Gate 5:** `npm run check` PASS (1 inline script block OK). `npm test` 55/55 PASS (41 previous + 14 framing). This includes the baseline CSP-hash test, because the server hashes the inline script at startup.
- **Extra verification (not committed):** a Playwright/Chromium script against a real `server.js` passed 26/26 checks with no page errors:
  - direct DataChannel and forced WS relay, each sending 0, 1, 16000, 16001 and 250000-byte files plus one reverse transfer, with byte-identical downloads and the correct filenames;
  - wrong key: "Key mismatch" on both sides, the drop zone disabled, and the message not overwritten by the 5 s timer;
  - a silent raw-WS peer: "outdated" after 5028 ms;
  - a peer sending a v1 frame: "outdated" after 508 ms.
- **SKIPPED:** none.
- **Open questions:**
  1. The README "Architecture" bullet still says "the 33-byte encryption envelope". It is now 49 bytes, but that section was out of scope for this step.
  2. Replay protection only lasts for one page load (and 64 transfers). Because the link key is long-lived, a relay could replay a file recorded in an earlier session of the same link. Fixing that needs session binding, for example mixing both peers' hello nonces into key derivation, which is a format decision for Step 6.
  3. Before the hello is verified, a relay can inject one garbage CTRL frame to force a sticky "Key mismatch". That is a DoS the relay can already cause by dropping frames. Making the state non-sticky (accept a later valid hello) is possible if you prefer it.

### Decisions after Step 5 (user, 2026-11-21)
- **README envelope size:** left unchanged for now and added to the Step 6 checklist. Step 6 must update the README "Architecture" bullet ("33-byte encryption envelope", now 49 bytes or whatever Step 6 makes it) and the "Wire Format" diagram and text together, in one documentation pass once the format is final.
- **Cross-session replay protection:** deferred to Step 6. Session key derivation will mix both peers' hello nonces with the URL-fragment room key through HKDF, bundled with the Step 6 wire-format changes so the handshake and session-state migration happens only once. Until then, the per-page-load `seenTransferIds` set (cap 64) is the only replay check.
- **Sticky "Key mismatch":** kept as implemented. An unauthenticated control frame before the hello is verified fails closed until the next `ready`/`peer-left`, or a reload. It is not cleared by a later valid hello, so the tamper signal stays visible.
- **Step 5 additions:** all five are kept (user, 2026-11-21): the transferId replay set, `meta.chunks` validation, silent drop of an aborted transfer's frames, `relay-busy` clearing the hello timer, and a hello with `v !== 2` treated as outdated.

#### Step 6 checklist (carried over)
- [x] Session binding: HKDF over the room key plus both peers' hello nonces; per-transfer keys derived from the session key. **Done in Step 6.5.**
- [x] README "Architecture" envelope size and "Wire Format" section updated together (done in Step 6).

### Step 6 — Transfer handshake, credit window, completion/abort (2026-11-21)
- **Scope (user, 2026-11-21):**
  - In scope: accept/decline, the credit window, complete/abort, and pure state machines with tests.
  - The hello exchange stays as in Step 5, with no `hello-ack`; the two concurrent hellos act as mutual acknowledgement.
  - Deferred to Step 7: transport resilience (surviving `peer-left`, sigQueue, signaling reconnects, split txActive/rxActive, dynamic relay caps, OPFS `showSaveFilePicker`/cleanup).
- **Files changed:** `public/index.html`, `README.md` (Architecture "Back-Pressure & Chunking" bullet and the "Wire Format" section); new `test/protocol.test.mjs`. `server.js` is unchanged.
- **Wire protocol:**
  - Forward frames (sender → receiver) use one transferId: `meta` seq 0, DATA 1..N, `done` N+1, and `abort` the next seq.
  - Replies (receiver → sender) use the same transferId under the receiver's role (AAD), numbered 1, 2, 3…: `accept`, `decline {reason: user|size|busy}`, `ack {seq}`, `complete`, `abort {reason}`.
  - The sender requires the exact next reply number. The hello is still `v:2`.
- **Pure logic (marked block):**
  - `ProtocolError(reason)`.
  - `RxStateMachine`: `parseMeta`, `autoDeclineReason`, phases offered → receiving → verifying → complete, or declined / aborted. It has `onData`, which returns an ack every 64 chunks, `onDone`, which returns the final ack, plus `onAbort`, `abort` and `nextReplySeq`.
  - `TxWindow`: phases offered → sending → awaiting-complete → delivered, or declined / aborted. It has `canSendData` (window 256), `nextDataSeq`, `doneSeq`, `abortSeq`, `onReply` (ack must be non-decreasing and ≤ sent; `complete` needs the final ack) and `timedOut(now)` → `accept` (5 min) | `ack` (window full with no ack progress for 60 s) | `complete` (5 min).
  - The time is passed in as a parameter, and nothing in the block uses the DOM.
- **App:**
  - The receiver prepares OPFS, then auto-declines (`size`) in RAM mode above 512 MB, or shows an Accept/Decline panel built with `createElement` and `textContent`.
  - A `meta` that arrives while either direction is busy gets `decline busy`. An invalid `meta` gets `abort integrity`.
  - Receive state and the meta validation now live in `RxStateMachine`. A disk write or close failure is reported as `abort disk`.
  - The sender offers the file, waits for accept, sends DATA through `TxWindow` credit plus `bufferedAmount`, then `done`, then logs "Sent, awaiting confirmation" and "Delivered" only on `complete`. A 1 s watchdog applies `timedOut`.
  - All outgoing frames go through one ordered queue (`sendQueued`), so seq order is preserved even though encryption is async.
  - `rxQueue` is bounded: more than 256 unprocessed DATA frames (or 32 control frames) means the peer broke the window, so the frame is dropped and the receive is aborted (`integrity`) once.
  - A "Cancel transfer" button sends `abort user` from either side.
  - When the transport goes away, `abortAllTransfersLocally()` ends both directions without notifying the peer. It is called from `cleanupPeer`, DataChannel close, WS close, `failHandshake` and relay-limit.
  - The single `droppedTransferHex` slot is replaced by `endedTransferIds` (capped at 16, oldest first), covering both directions. A `rejected` frame (one from an unknown transfer) no longer kills an unrelated active receive.
- **Tests (`test/protocol.test.mjs`, 19):**
  - Required cases: out-of-order seq (skip, duplicate, replay, 0), size overflow plus extra and empty chunks, duplicate/early/misnumbered/miscounted `done`, stalled ack window (fires only when the window is full; progress resets it; a repeated ack does not), mid-stream abort from either side (the forward abort seq matches the receiver's expected seq while offered, mid-stream and after `done`), and pre-data decline on both machines.
  - Also: the `parseMeta` matrix, the 512 MB auto-decline, both happy paths, reply ordering and ack validation, accept and complete timeouts, and a deterministic lockstep simulation of 2000 chunks showing an honest receiver never has more than 256 chunks queued (the maximum observed was > 192, so the window is exercised).
- **Mutation check:** each of these was applied and reverted, with the file restored byte-identical:
  - no seq check in `onData`: out-of-order test fails;
  - no size check: overflow test fails;
  - `done` allowed twice: duplicate-done test fails;
  - credit window ignored: window and lockstep tests fail;
  - data allowed before accept: pre-data decline and abort tests fail;
  - abort seq unchecked: abort test fails.
- **Gate:** `npm run check` PASS. `npm test` 74/74 PASS (55 previous + 19 protocol), including the CSP-hash baseline.
- **Extra verification (not committed):** a Playwright/Chromium script against a real server passed 44/44 checks with no page errors:
  - direct and forced WS relay transfers of 0, 1, 16000, 16001, 1,024,000 and 10,000,000 bytes, plus one reverse transfer, all byte-identical; the sender log reads Offered → "Sent, awaiting confirmation" → "Delivered";
  - decline reaches the sender, and both sides become ready again;
  - a file name containing HTML is shown as text;
  - with OPFS removed, a 600 MB file is auto-declined and the sender shows "too large for the peer's browser", while a small file still goes through;
  - a mid-stream cancel from the sender and from the receiver on a 200 MB file: the peer logs the `user` abort, both sides become ready, there are no error-log lines, and a new transfer then succeeds;
  - a full 200 MB direct transfer with matching SHA-256 (14.9 s);
  - Step 5 regressions (wrong key, silent peer) still pass.
- **Not exercised in a browser:** the receiver's credit-window overflow abort (it needs a misbehaving sender holding the room key; covered by logic and the lockstep test only), the 60 s ack-stall and 5 min accept/complete timeouts (covered by the `TxWindow` unit tests with injected time), and the `disk` abort.
- **SKIPPED:** none.
- **Open questions:**
  1. Session binding (HKDF over both hello nonces), which was planned for Step 6 in the decisions after Step 5, was not in the Step 6 scope you set, so it is still open. It changes key derivation and the hello, so it should go into Step 7 or its own step. Until then, replay protection only lasts for one page load.
  2. Rule 7 says the wire format must be final at the end of Step 6. Session binding would change it again (new hello contents and key schedule).
  3. Values I chose that the spec did not give: ack-stall timeout 60 s, complete timeout 5 min, decline reasons `user | size | busy`, and the cap of 32 queued control frames.
  4. A sender that cannot reach `complete` (the receiver's download blocked, or the tab closed after saving) shows a `timeout` abort after 5 minutes, even though the file may have been saved.

### Decisions after Step 6 (user, 2026-11-21)
- **Session binding → Step 6.5 (or the start of Step 7):**
  - Per-transfer keys become `HKDF-SHA256(roomKey, transferId ‖ initNonce ‖ respNonce, …)`.
  - This needs a sequential hello → hello-ack handshake, so that both peers hold both nonces before deriving any transfer key.
  - It is kept out of Step 6 so the state-machine diff stays isolated.
- **Rule 7 amended** (`RULES.md`): the wire format changes in Steps 5, 6 and 6.5. It stabilizes once session-nonce binding lands, and must be final and consistent before deployment in Step 7. This resolves Step 6 open questions 1–2.
- **Values kept** (Step 6 open question 3): 60 s ack stall, 5 min wait for `complete`, decline reasons `user | size | busy`, and the 32-frame control queue cap.
- **Missing `complete` after `done` (Step 6 open question 4), implemented:**
  - **Wire addition:** the receiver's acknowledgement of `done` is now `{type:'ack', seq:N, done:true}`. Without the flag it could not be told apart from a chunk ack: when N is a multiple of 64, an `ack {seq:N}` is also sent for the last chunk.
  - **`TxWindow`:**
    - `doneAcked` is set only by a `done:true` ack while awaiting completion, with `seq === chunks`, and only once. Any other `done` value is rejected.
    - `complete` now requires `doneAcked`, not just `acked === chunks`.
    - `timedOut()` returns `unconfirmed` instead of `complete` when `done` was acknowledged.
    - New `endUnconfirmed()` moves to the terminal phase `unconfirmed`. No abort is sent, and `abortSeq` returns null afterwards.
  - **App:**
    - The watchdog's `unconfirmed` logs "Sent — no final confirmation from the peer: NAME".
    - Transport loss after the done-ack (peer closed the tab) logs "Sent — peer closed before final confirmation: NAME".
    - Both are neutral log lines (not `err`), and the sender is torn down cleanly.
    - Before the done-ack, the behaviour is unchanged: "Send interrupted" on transport loss, or a `timeout` abort after 5 min ("the receiver did not acknowledge the end of the file within 5 minutes").
  - **Tests:** 2 new protocol tests.
    - Final-ack validation: rejected before `done`, with the wrong seq, when repeated, or with a non-boolean flag.
    - Unconfirmed vs timeout vs mid-stream.
    - The existing complete test now also checks that a chunk ack for N is not the final ack, and the lockstep simulation sends `done:true`.
  - **README:** the Transfer diagram and the Completion bullet are updated.
- **Gate re-run:** `npm run check` PASS; `npm test` 76/76 PASS.
- **Browser check (not committed):**
  - Receiver patched to swallow `complete`, then its tab closed: the sender logs the neutral "Sent — peer closed before final confirmation", with no `err` class and no "Delivered".
  - Patched to swallow `complete` and the final ack: "Send interrupted" (`err`).
  - That script passed 8 of 9 runs. The first run timed out at a step I did not capture; the 8 runs after it all passed, so the cause is unknown (most likely harness timing).
  - The full Step 6 regression script passed 44/44 twice.

### Step 6.5 — Session binding (wire format v3) (2026-11-21)
- **Scope:** your post-Step-6 decision. No separate spec was given, so the design choices below are mine (open question 4).
  - Per-transfer keys become `HKDF-SHA256(roomKey, transferId ‖ initNonce ‖ respNonce, …)`.
  - A sequential hello → hello-ack handshake makes sure both peers hold both nonces before any transfer key is used.
- **Files changed:** `public/index.html`, `README.md` (Wire Format section), `test/framing.test.mjs`. `server.js` is unchanged.
- **Wire format v3:**
  - Frame types `0x20` HELLO, `0x21` CTRL, `0x22` DATA. The header is unchanged (33 bytes, overhead 49). AAD magic `"DP3"`.
  - `1`, `2`, `0x11` and `0x12` count as legacy, so a v2 peer is reported as "outdated" (not "Key mismatch"). The v3 side sees the v2 hello's `0x11` type at once; the v2 side times out after 5 s.
  - **HELLO key:** `HKDF(roomKey, salt = transferId, info = "darkp2p/v3/hello")`. Payload `{type:'hello', v:3, nonce: base64url(32 bytes)}`.
  - **CTRL/DATA key:** `HKDF(roomKey, salt = transferId ‖ initNonce ‖ respNonce (80 bytes), info = "darkp2p/v3/transfer")`, initiator's nonce first (by `ready.initiator` role).
  - **Hello-ack:** a CTRL frame (new transferId, seq 0) `{type:'hello-ack', v:3}` under the session key.
- **Framing block:** new `deriveHelloKey(roomKey, transferId)` and `deriveTransferKey(roomKey, transferId, {initNonce, respNonce})`. `createTransferKeyCache(roomKey, session, max)` is now one LRU per session. Nonces are checked to be 32 bytes, and `encodeFrame`/`decodeFrame` accept the three v3 types.
- **Handshake (app):**
  - Every transport open (`startHandshake`) makes a fresh `myNonce`, sends the hello and enters `pending`. Every peer hello with a new nonce updates `peerNonce`. A hello repeating the current nonce is ignored.
  - Whenever both nonces are known, `updateSession()` builds the session's key cache and sends a hello-ack. `acceptHelloAck()` (the ack decrypted under the current session) moves to `ok` and enables the drop zone.
  - Our hello and hello-ack go through the ordered `outQueue`, so the hello always leaves first. A hello-ack of a superseded session fails authentication and is ignored silently. A CTRL/DATA frame that arrives before any session exists is dropped (`stale`).
  - A session renewal (our new nonce or the peer's) stops transfers of the old session ("Session renewed — transfers in progress were stopped.").
  - Each transfer captures its session's key function (`state.keys`), and `sendCtrl` captures the key function when the frame is queued. A frame is never encrypted under a session it does not belong to.
  - 5 s timer: no peer hello gives "outdated", as before. A peer hello without a hello-ack logs "Peer did not confirm the session key — both sides reload"; the state stays `pending`, so a late ack still completes the handshake.
- **Behaviour kept:** "Key mismatch" (a hello that fails authentication before `ok`) stays until the next `ready`, all Step 6 protocol behaviour, and the in-session transferId replay set.
- **Tests (`framing.test.mjs`, now 19):**
  - Existing tests adapted to the session key API. The legacy test now covers `1`, `2`, `0x11` and `0x12`.
  - New:
    - v3 type values;
    - a frame from session 1 fails under session 2 with the same room key and transferId, including when only one peer's nonce changed;
    - swapped nonce order fails;
    - hello keys are separate from session keys, a hello under another room key fails, and a HELLO frame re-typed as CTRL fails;
    - nonce length is validated.
- **Mutation check:** each of these was applied and reverted, with the file restored byte-identical:
  - nonces left out of the HKDF salt: the session-1-vs-2 and swapped-order tests fail;
  - nonce order normalized: the swapped-order test fails;
  - the hello key reusing the transfer info label: **not detected**. This is expected: the salts already differ (`transferId` vs `transferId‖nonces`), so the separate label is only defence in depth and not observable on its own.
- **Gate:** `npm run check` PASS; `npm test` 81/81 PASS (76 previous + 5 new framing tests), including the CSP-hash baseline.
- **Extra verification (not committed):**
  - The Step 6.5 Playwright/Chromium script passed 13/13 with no page errors:
    - **Cross-session replay:** a relay-style recording of a full session-1 transfer (meta, chunks, done), replayed into session 2 after the receiver reloads, produces no offer. The frames fail authentication, and a fresh transfer afterwards works. With the nonces removed from the salt, the same replay is accepted ("Incoming offer: secret.bin"), so the check detects the attack.
    - **Direct → WS relay mid-session:** both sides re-verify, and transfers work in both directions with no "did not confirm" warning.
    - **v2 peer:** a peer sending a `0x11` frame is reported "outdated" in 24 ms.
    - **Wrong key:** "Key mismatch" on both sides.
  - The Step 6 regression script passed 44/44.
- **SKIPPED:** none.
- **Open questions:**
  1. **Stale hello replay (DoS only).** A relay can replay a peer hello from an earlier page load; its transferId is not in the new page's seen set. The receiving side then switches to a session with that stale nonce. The real peer never acks it, so the drop zone stays disabled with the "did not confirm" warning until a reload. Confidentiality and integrity are not affected, and a relay can already cause a DoS by dropping frames. Stopping it would need the hello itself to be bound to fresh state (for example, echoing the peer's nonce inside the hello-ack and requiring a challenge), which is a larger protocol change.
  2. **Transfers do not survive a session renewal.** Switching transports mid-transfer already ended transfers in Step 6 (`cleanupPeer`). Surviving it belongs with Step 7's transport resilience, and would need the session to survive a transport change.
  3. **Rule 7:** with session binding in place, I consider wire format v3 final unless Step 7 changes it. Please confirm.
  4. **My design choices (the spec gave only the formula):**
     - type bytes `0x20`/`0x21`/`0x22`, and a separate HELLO type, so the key can be chosen before decryption;
     - the info labels `darkp2p/v3/hello` and `darkp2p/v3/transfer`, and the `"DP3"` AAD magic;
     - the nonce encoded as base64url inside the encrypted hello JSON;
     - a missing hello-ack giving a warning, not a sticky failure;
     - a new nonce on every transport open.

### Decisions after Step 6.5 (user, 2026-11-21)
- **Stale hello replay (DoS only):** accepted as a limitation, with no challenge-response handshake. The README threat model is to note it in Step 10.
- **Mid-transfer connection switching:** stays out of scope here and is handled with connection resilience in Step 7.
- **Wire format v3 is final** under rule 7, unless Step 7 needs a wire-level change. Step 10's README updates must document v3 (the "Wire Format" section already describes v3 as of Step 6.5).
- **All five Step 6.5 design choices are approved:** the separate hello type and type numbers; the `darkp2p/v3/hello` / `darkp2p/v3/transfer` HKDF labels and the `"DP3"` AAD magic; the base64url nonce inside the encrypted hello; a missing `hello-ack` giving a warning, not a hard failure; and a fresh nonce on every connection open.
  - On the condition attached to the hello-ack warning: the drop zone is gated more strictly than "a valid peer hello". It opens only after the peer's `hello-ack` decrypts under the current session, which requires a valid peer hello first. While only the warning is showing, the drop zone stays disabled (`handshake` remains `pending`).

#### Step 10 checklist (carried over)
- [ ] README threat model: a relay replaying a peer hello from an earlier page load can stall the handshake (DoS only; no confidentiality or integrity impact). This is an accepted limitation.
- [ ] README documents wire format v3 throughout. Check that no v2 references remain.
