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
- [ ] README covers the Step 7 behaviour:
  - `decline busy` now means "already receiving", not "any transfer";
  - one send and one receive can run at the same time;
  - the relay cap is per room and counts both directions, and the sender checks what is left;
  - signaling reconnects, and a direct tunnel survives a signaling drop;
  - the save picker with its OPFS/RAM fallback, and the 24 h OPFS temp-file cleanup.

### Step 7 — Connection resilience (2026-11-21)
- **Scope:** the deferred list from Step 6, plus mid-transfer switching. Your decisions (2026-11-21), all four the recommended options:
  1. Transfers end cleanly and the page recovers, with no resume. Signaling reconnects automatically, and an open DataChannel survives a `peer-left`.
  2. One send and one receive at the same time.
  3. The relay cap comes from the server's `ready`.
  4. The save picker when available, else OPFS, else RAM.
- **Files changed:** `public/index.html`; new `test/resilience.test.mjs`. **`server.js` is unchanged:** its `ready` already carried `wsRelayCap` and `wsRelayRemaining`, which the client simply ignored until now. The wire format is unchanged (v3 stays final per rule 7). The README is left for Step 10 (checklist above).
- **Pure helpers (marked block):**
  - `reconnectDelay(attempt, rand)`: 1 s doubling to a 30 s cap, ±20 % jitter, safe on bad input.
  - `RelayBudget {cap, remaining}`: `charge`, `fits`, and `wireBytes(size, chunk)`, which covers chunks, frame overhead, acks and control frames.
  - `isStaleTempName(name, now)`: matches only `darkp2p-<ms>-<base36>` names older than 24 h.
- **Signaling:**
  - `ws` is now a reconnecting socket (`connectSignaling`). Stale sockets are ignored by identity, and the client rejoins with the same room and token.
  - After a drop, a `full` reply is retried for 90 s, because the server notices a dead socket only within two heartbeats.
  - Every inbound message, binary included, goes through one `sigQueue`, so an async handler (`ready` → `initPeer`, `offer` → answer) finishes before the next message is handled. Binary frames are only handed to `rxQueue` in order; they are not processed there.
  - `ws.onclose` in relay mode ends both transfers and the session ("Send interrupted" / "Receive interrupted"). In direct mode it logs "direct tunnel still up" and leaves the tunnel alone.
- **DataChannel survival:**
  - When a verified DataChannel is open (`isDirectTunnelUp`), `peer-left` only logs and sets `peerAway`.
  - A later `ready` (rejoin) keeps the tunnel, its roles and its session, and only refreshes the relay budget and Pro flag.
  - If that DataChannel then closes while `peerAway` is set, `enterStandby()` runs (the same as `peer-left`).
- **Separate send/receive:**
  - `isTransferring` is removed. Sending depends only on `tx`, and receiving does not disable the drop zone.
  - `decline busy` is sent only while already receiving.
  - The receiver has its own panel (`#rxPanel`: label, `#rxProgress`, `#cancelRxBtn`), and `#cancelBtn` is now "Cancel send".
  - `isChannelOpen` and `waitForBufferDrain` use `instanceof WebSocket`, so a replaced socket is never mistaken for a DataChannel.
- **Relay budget:**
  - Taken from `ready` (falling back to 50/500 MB defaults for an older server) and charged for every binary frame sent or received over the relay.
  - The sender blocks a file that does not fit what the room has left (with the remaining and cap MB in the message). `relay-limit-exceeded` sets the remaining budget to 0.
- **Saving:**
  - With `showSaveFilePicker`, the offer says "save to a file you choose". Accept, a user gesture, opens the picker. Chunks stream to the chosen file, committed by `close()` only after verification, with no blob download.
  - If the picker is cancelled or fails, the receiver uses OPFS, then RAM. A RAM-only receive over 512 MB is then declined with `size` at Accept time.
  - Without a picker, OPFS is prepared at offer time as before.
  - Storage prepared for a transfer that was torn down meanwhile is discarded (`discardStorage`).
- **OPFS cleanup:** on load, the page iterates OPFS (at most 1000 names), removes stale `darkp2p-*` temp files and logs the count.
- **Tests (`resilience.test.mjs`, 6):**
  - the backoff schedule, jitter bounds and bad input;
  - `RelayBudget` validation and charging;
  - `wireBytes` is never below the real relay bytes of a transfer (computed from actual JSON sizes, 0 B … 499 MB, 200-character file name) and never more than 4 KB above;
  - `fits` uses what is left, not the cap (a cap of 0 disables the relay);
  - stale-name matching with boundary and look-alike names.
- **Mutation check:** each was applied and reverted, with the file restored byte-identical:
  - acks not counted in `wireBytes`: the wireBytes test fails;
  - `fits` compared against the cap: the fits test fails;
  - every `darkp2p-` name treated as stale: the cleanup test fails;
  - no backoff cap: both backoff tests fail.
- **Gate:** `npm run check` PASS; `npm test` 87/87 PASS (81 previous + 6 resilience), including the CSP-hash baseline.
- **Extra verification (not committed):**
  - **Step 7 Playwright/Chromium script, 20/20 PASS** with no page errors, against a real `server.js` on a fixed port:
    - Server killed and restarted during a 200 MB direct transfer: the transfer completed with a matching SHA-256, both sides rejoined and kept the tunnel, and a reverse transfer worked afterwards.
    - One side's signaling closed mid-transfer (30 MB): the peer logged "Peer lost its signaling connection — direct tunnel still up", the transfer completed, and the DataChannel was not torn down.
    - Server restarted during a relay transfer: "Send interrupted" and "Receive interrupted" on both sides, signaling restored, the session renegotiated, and a new transfer worked.
    - Concurrent 8 MB and 6 MB transfers in opposite directions: both delivered, the drop zone stayed enabled while receiving, and there were no `busy` declines.
    - **Save picker (stubbed with an OPFS-backed handle):** the file was streamed to the chosen handle, with no blob download, and the log names the chosen file.
    - **Picker cancelled:** the OPFS path plus a download worked.
    - **`RELAY_CAP_FREE_MB=1`:** after a 600 KB file, a second 600 KB file was blocked client-side ("0.4 MB of this room's 1 MB left") with nothing offered to the peer, the Pro upsell was shown, and a 100 KB file still went through.
    - **OPFS cleanup:** only the stale `darkp2p-*` file was removed; fresh and unrelated files were kept.
  - **Step 6 regression, 45/45 PASS:**
    - Selectors were updated for the new receive panel.
    - The RAM-mode scenario now also removes the save picker, since a picker counts as disk streaming.
    - New case: picker cancelled, no OPFS and a file over 512 MB is declined with `size` at Accept.
  - **Step 6.5 regression, 13/13 PASS.**
  - Two behaviour notes from these runs:
    - Headless Chromium exposes `showSaveFilePicker` and rejects it with `AbortError`, so every Accept in these runs used the picker-cancel → OPFS fallback.
    - A sender whose relay socket dies logged "Send aborted: Channel closed" before `onclose` ran. It now logs "Send interrupted: NAME" whenever the channel is closed.
- **Not exercised:** the `full` retry needs an abrupt network loss (a clean close frees the slot at once), so it was not run in a browser.
- **SKIPPED:** none.
- **Open questions:**
  1. **Newcomer while the DataChannel survives.** If a different link holder joins while the DataChannel peer's signaling is down, this side keeps its tunnel and ignores the newcomer's `ready`. If the newcomer then falls back to the relay (`ws-fallback`), this side switches to the relay and the tunnel to the original peer ends. Anyone with the link holds the key, so this is not a confidentiality issue.
  2. **Empty file left by the save picker.** For a new file name, the picker may create an empty file, and an aborted transfer leaves it behind. It is deliberately not deleted: `handle.remove()` would also delete an existing file the user chose to overwrite (whose contents are untouched on abort).
  3. **Data waits behind signaling.** Binary frames now wait behind any in-flight signaling handler. In practice that only happens during negotiation, but it does add latency there.
  4. **Wrong wording on first connect.** A failed first connection logs "Signaling connection lost — reconnecting..." although nothing was ever connected.

### Decisions after Step 7 (user, 2026-11-21)
- **Newcomer falling back to the relay:** kept as-is. Anyone with the room link holds the key, so a newcomer's `ws-fallback` is a valid peer switch.
- **Empty file left by the save picker on abort:** kept as-is. The handle is never deleted, so an existing file the user chose to overwrite is never wiped.
- **Data waiting behind signaling:** kept as-is. Signaling is quiet during a transfer, and one ordered queue avoids concurrency races.
- **First-connect wording:** cosmetic and not changed now. If `connectSignaling()` is touched again, log "Could not connect to signaling — retrying..." when the socket never opened, instead of "Signaling connection lost".

### Step 8 — Config injection + stats line (2026-11-21)
- **Tasks:** 8.1 (H7, I3), 8.2 (I5).
- **Files changed:** `server.js`, `public/index.html`, `.env.example`, `test/baseline.test.mjs`, `test/relay.test.mjs`, `test/helpers/server.mjs`; new `test/config.test.mjs` and `test/stats.test.mjs`. The wire format is unchanged.
- **Task 8.1 — server (`loadPage`, `renderIndex`, `pageConfigJson`):**
  - New optional env vars, all checked in `validateConfig()`, and an invalid value is `fatal`:
    - `BTCPAY_POS_URL` must parse with `new URL`, be `https:`, and have no query, fragment or credentials, because the page appends `?itemCode=…`.
    - `TIP_XMR_ADDRESS` must be base58, start with `4`/`8`, and be 95 or 106 characters.
    - `TIP_BTC_ADDRESS` must be lowercase `bc1` bech32/bech32m (11–90 characters) or legacy base58 starting with `1`/`3`.
  - At boot, `index.html` gets these substitutions:
    - every literal `https://darkp2p.com/` becomes `https://${DOMAIN}/`;
    - `<script type="application/json" id="darkp2p-config">{}</script>` becomes `JSON.stringify({btcpayPosUrl, tipXmr, tipBtc})`, with unset values as `null` and every `<` as `<`;
    - the server refuses to start if that block is not in the file exactly once;
    - the substitutions use split/join, so a `$` in a value cannot act as a replacement pattern.
  - The CSP is built from the substituted HTML. `loadPage` now reads the file as UTF-8 and serves the rendered string. `404.html` has no transform and is served as before.
- **Task 8.1 — client:**
  - The empty config block sits just before the app script.
  - The tier CTAs no longer have an `href`. They carry `data-item-code` and start `hidden`. When `btcpayPosUrl` parses as https, each one gets `${btcpayPosUrl}?itemCode=<code>` and is shown. The client re-checks the https scheme as defence in depth.
  - The tip buttons start `hidden` and have no `data-addr`. Each is shown only when its address is set, and copies it from the config.
  - Explicit `[hidden] { display: none }` rules cover `.tier-cta`, `.tip-jar`, `.tip-btn` and `.modal-note`, since `.tier-cta` and `.tip-jar` set their own `display`.
  - All `YOUR*` placeholders are gone.
- **Task 8.2 — stats line:**
  - `sweep` logs `stats conns=N rooms=N relayRooms=N relayMB=N.N rejected429=N stalls=N`.
    - `conns`, `rooms` and `relayRooms` are current values.
    - `relayMB` counts the binary bytes relayed in the interval.
    - `rejected429` counts WS upgrades and `/api/claim` requests refused with 429 in the interval.
    - `stalls` counts `relay-stalled` events in the interval.
  - The interval counters (`stats.relayBytes`, `rejected429`, `stalls`) reset after each line. They are plain numbers with no keys, so the reset is their eviction rule (rule 6).
  - `SWEEP_MS` is now env-tunable (`envInt('SWEEP_MS', 300000, 1000, 3600000)`, the same precedent as `HEARTBEAT_MS`), so tests can see the line. The default interval is unchanged at 5 min.
- **Tests:**
  - `config.test.mjs`, 12 tests:
    - the default page has no `YOUR`, no `darkp2p.com/` and a `test.local` canonical/OG/JSON-LD;
    - with no env, the config is all `null`;
    - with the env set, the CTAs, tip buttons and config JSON are present;
    - the CSP matches the hashes of the scripts actually served, and the executable script is identical with or without config;
    - a `</script><script>alert(1)</script><!--` in `BTCPAY_POS_URL` leaves no `<` in the block and adds no `<script` tag;
    - 8 boot failures: http, `javascript:`, unparseable, query, credentials, placeholder XMR, XMR with markup, placeholder BTC;
    - legacy BTC and integrated XMR are accepted.
  - `stats.test.mjs`: with `SWEEP_MS=1000`, about 1.2 MB is relayed, then a 429 is triggered. Checks that the line reports `relayMB≥1.1`, `rejected429≥1`, `conns=2`, `rooms=1` and `relayRooms=1`; that the next line has its interval counters back at 0 while the gauges stay; that the gauges drop to 0 after both peers close; and that every line matches the strict `STATS_LINE_RE`.
  - The baseline log-hygiene test now runs with `SWEEP_MS=1000`. It waits for a stats line written after all the baseline traffic, checks every stats line against `STATS_LINE_RE` (in `helpers/server.mjs`), and still checks for no addresses, room IDs, invoice IDs or tokens.
  - The relay stall test now also requires a stats line with `stalls=1`.
- **Mutation check:** each was applied and reverted, with `server.js` restored byte-identical:
  - no `darkp2p.com` swap: 1 failure;
  - no https check: 2 failures;
  - interval counters not reset: 1 failure;
  - stall not counted: 1 failure;
  - room IDs appended to the stats line: 1 failure (hygiene).
  - **Not detected, as expected:**
    - Removing the `<` escape. Validation already blocks every way a `<` could reach the config: WHATWG `URL` percent-encodes `<` in the path, a query or fragment is rejected, a host with `<` does not parse, and the address regexes forbid it. The escape is defence in depth.
    - Hashing the page before substitution. The substitutions never touch the executable script, so its hash is the same either way. The test still checks the hashes against the served page.
- **Gate:** `npm run check` PASS; `npm test` 100/100 PASS (87 previous + 12 config + 1 stats), 0 skipped.
- **Extra verification (not committed):** a Playwright/Chromium script against a real `server.js`, 21/21 PASS, with no page or CSP errors in any configuration:
  - no env: no CTAs, no tip buttons, no tip jar and no payment note, and the app script runs;
  - everything set: both CTAs link to `…/pos?itemCode=pro-1d` / `pro-30d`, both tip buttons show, and XMR copies the configured address to the clipboard;
  - XMR only: one tip button, no CTAs.
- **SKIPPED:** none.
- **Open questions:**
  1. **Beyond the spec, two extra things are hidden:** the whole tip jar when neither address is set (otherwise only the "SUPPORT ZERO-LOG INFRASTRUCTURE:" label would show), and the "After payment settles…" note when there is no POS URL. The Pro modal itself stays reachable, because pasting a token still works.
  2. **An invalid payment value is fatal**, not a warning with the item hidden. This matches `BTCPAY_URL`, but a typo in a tip address now stops the server from starting.
  3. **The address regexes accept mainnet only:** no testnet/regtest, and no all-uppercase bech32. Say if you want them widened.
  4. **`rejected429`** also counts `/api/claim` 429s, not just WS upgrades. A `full` room is not counted.
  5. **`SWEEP_MS`** is a new env var, added for testability. It also sets the stats interval, so it belongs in the Step 10 env table.

### Decisions after Step 8 (user, 2026-11-21)
- **All four Step 8 open questions are approved as implemented:**
  1. Extra hiding: the tip jar is hidden when no address is set, and the "After payment settles…" note when there is no POS URL. The Pro modal stays openable for pasting a token.
  2. An invalid `BTCPAY_POS_URL`, `TIP_XMR_ADDRESS` or `TIP_BTC_ADDRESS` stops the server at boot.
  3. The tip address regexes accept mainnet only.
  4. `rejected429` counts every 429 refusal (WS upgrades and `/api/claim`). Full-room replies are not counted.

#### Step 10 checklist (added)
- [ ] The env var table covers `BTCPAY_POS_URL`, `TIP_XMR_ADDRESS`, `TIP_BTC_ADDRESS` and `SWEEP_MS`, and documents the stats line format.

### Step 9 — Infrastructure hardening (2026-11-21)
- **Tasks:** 9.1, 9.2, 9.3, 9.4, 9.5, 9.6.
- **Files changed:** `Dockerfile`, `docker-compose.yml`, `Caddyfile`, `setup-ufw.sh`, `.gitignore`, `.env.example` (one comment), `test/compose.test.mjs`; new `tor/Dockerfile`, `tor/torrc`, `scripts/gen-turnserver-conf.sh`. `server.js` and `public/` are unchanged.
- **9.1:** `FROM node:22.23.5-alpine3.24` (newest 22.x patch on Docker Hub), `ENV NODE_ENV=production`. `USER node` and the HEALTHCHECK are kept.
- **9.2:**
  - Every service has `no-new-privileges:true`. caddy, darkp2p and tor use the `local` driver with 10m × 3 files; coturn keeps `none`.
  - darkp2p: `mem_limit: 384m`, `pids_limit: 128`, `tmpfs: [/tmp]`.
  - caddy: `cap_drop: [ALL]`, `cap_add: [NET_BIND_SERVICE]`, image `caddy:2.11.9-alpine`. Its Hub digest equals the current `caddy:2-alpine` digest.
- **9.3:**
  - `tor/Dockerfile`: `FROM alpine:3.20.10`, `apk add --no-cache tor=0.4.9.11-r0`, `/var/lib/tor` and `hidden_service` chowned to `tor` with mode 700, and `USER tor`.
  - `tor/torrc` has the five required lines plus `DataDirectory /var/lib/tor`, so the data dir is fixed whatever tor's non-root default is. It is mounted read-only, and the runtime `apk add`/`printf` command is gone.
  - A new `tor_data` volume inherits the image's ownership. A compose comment gives the one-time `chown` for a volume created by the old root-run container.
- **9.4:**
  - `scripts/gen-turnserver-conf.sh [ENV_FILE] [OUT_FILE]` parses `.env` without sourcing it. It validates `TURN_HOST`, `TURN_PUBLIC_IP` (dotted IPv4, each octet ≤255) and `TURN_SECRET` (≥16 printable characters, no space or `#`).
  - It writes every Step 2 flag, including `static-auth-secret`, through a umask 077 temp file, then `chmod 0600`, then `chown 65534:65533`, then an atomic `mv`.
  - That owner is coturn's `nobody:nogroup`; `nogroup` is gid 65533 in this image. `COTURN_OWNER` overrides it, and empty skips the chown. A failed chown exits 1 with "re-run with sudo".
  - The secret is never printed.
  - Compose mounts the file read-only with `command: ["-c", "/etc/coturn/turnserver.conf"]`. The compose file has no `TURN_*` reference and no secret, and `coturn/turnserver.conf` is gitignored. The `TURN_PUBLIC_IP` requirement moved from compose to the script. No `turns:`/TLS was added.
- **9.5:**
  - A global `servers { timeouts { read_header 10s } }`.
  - `header -Server` in both site blocks: the www redirect block also sent `Server: Caddy`.
  - `request_body /webhook/btcpay { max_size 64KB }`, and `reverse_proxy darkp2p:3000 { stream_close_delay 5m }`.
  - No access log.
- **9.6:**
  - `set -euo pipefail` and a root check.
  - `SSH_PORT="${SSH_PORT:-22}"`.
  - `IPV6=yes` is set in `/etc/default/ufw`, with a warning if it can't be confirmed.
  - A header comment says Docker-published ports bypass UFW.
  - The relay range is `TURN_MIN_PORT`/`TURN_MAX_PORT`, cross-referenced with the generator script.
- **Tests (`compose.test.mjs`, 5 → 8):**
  - Three tests were rewritten for the new layout:
    - **tor:** no runtime command, built from `./tor`, torrc read-only and with the required lines, pinned `FROM` and package, `USER tor`.
    - **coturn:** `-c` only, read-only mount, logging `none`, tag+digest pin, no `TURN_SECRET`/`static-auth-secret` in the compose file or render.
    - **TURN_PUBLIC_IP:** now checked in the generator.
  - New:
    - every service has `no-new-privileges`, with the log driver and rotation checked for all but coturn;
    - the darkp2p limits, caddy caps and image pins;
    - the generator writes the Step 2 flags in a 0600 file without printing the secret, and refuses a missing or bad IP or secret without leaving a file;
    - the UFW relay range equals the generator's range.
  - The generator and UFW tests run without docker.
- **Mutation check:** each was applied and reverted, with the files restored byte-identical. Each failed exactly its own test:
  - UFW max port changed;
  - `--static-auth-secret=${TURN_SECRET}` added to the coturn command;
  - no-new-privileges removed from caddy;
  - `HiddenServiceMaxStreamsCloseCircuit` removed from torrc.
- **Gate:**
  - `npm run check`: PASS.
  - `npm test`: 103/103 PASS (100 previous + 3 new compose tests), 0 skipped.
  - `bash -n` on each of `setup-ufw.sh`, `scripts/compose-gate.sh` and `scripts/gen-turnserver-conf.sh`: PASS (run per file, since `bash -n a b` only parses `a`).
  - `shellcheck`: **SKIPPED (tool unavailable)**. Installing it with pip was not permitted in this session.
  - `docker compose --env-file test/fixtures/compose.env config -q` (via `npm run check:compose`): PASS.
  - `caddy validate` inside `caddy:2.11.9-alpine` with `DOMAIN=test.local`: "Valid configuration", exit 0.
    - `caddy adapt` confirms `read_header_timeout` 10 s, `stream_close_delay` 5 m, `max_size` 64000 and `delete: [Server]`.
    - Caddy reads `64KB` as SI, so the limit is 64,000 bytes.
  - `docker build -t darkp2p:audit .`: PASS. The sandbox proxy re-signs TLS (npm got `SELF_SIGNED_CERT_IN_CHAIN`), so the unchanged Dockerfile was built with `--build-context node:22.23.5-alpine3.24=docker-image://<the same image plus the sandbox CA>`.
  - `docker build -t darkp2p-tor:audit tor/`: **FAILED (network policy)**. The session proxy denies `dl-cdn.alpinelinux.org` (403), so `apk` sees no `tor` package. This is an environment block, not a Dockerfile error, but the build and the pinned version are unverified. The version came from `community/tor/APKBUILD` on the aports `3.20-stable` branch (GitHub mirror).
- **Extra verification (not committed):**
  - `darkp2p:audit` run with the compose hardening (`--read-only`, `cap-drop ALL`, no-new-privileges, 384m, pids 128, tmpfs `/tmp`, local logs): healthy, uid 1000 `node`, `NODE_ENV=production`, node v22.23.5.
  - The pinned coturn image ran from a generated conf (fixture env, mode 0600, owner 65534:65533), read-only, with tmpfs and no-new-privileges. It started and ran as `nobody` with `-c /etc/coturn/turnserver.conf`.
- **SKIPPED:** shellcheck (tool unavailable). The tor image build was blocked by network policy; see above.
- **Open questions:**
  1. **coturn still writes a log file, a Step 2 bug that is not fixed here.**
     - With `log-file=/dev/null` and no `simple-log`, coturn appends a date, fails to open `/dev/null_<date>.log`, and falls back to `/var/tmp/turn_1_<date>.log` on the tmpfs.
     - It held 9 listener/address lines at startup and would also record client and peer addresses. It is in RAM only, never on the host disk, but it is a log.
     - Adding `simple-log` to the generated conf was verified to leave only `turnserver.pid` in `/var/tmp`.
     - Apply it? It is one line in `gen-turnserver-conf.sh` plus a test assertion.
  2. **Tor image unverified.** Allow `dl-cdn.alpinelinux.org` in this environment so `tor/` can be built and the `tor=0.4.9.11-r0` pin confirmed, or verify it on the deploy host. Alpine 3.20 is past end of support; moving tor to a supported Alpine branch would need a new pin. Your call.
  3. **Existing `tor_data` volume:** the onion keys are root-owned from the old container, so tor (now uid `tor`) will refuse them until the one-time `chown` in the compose comment is run. Should Step 10's deploy docs include it?
  4. **Pin by digest?** node and caddy are pinned by tag only, as specified. coturn is pinned by tag and digest. Should these match coturn?
  5. **`header -Server`** was also added to the www redirect block. Say if you want it only on the main site.
  6. **`IPV6=yes`** is written into `/etc/default/ufw` rather than only checked. The warning fires only if that fails.

### Decisions after Step 9 (user, 2026-11-21) and follow-up
- **coturn `simple-log`: applied.**
  - `gen-turnserver-conf.sh` now emits `simple-log` before `log-file=/dev/null`, with a comment saying why. The compose debug comment names it too.
  - The generator test requires the line. A mutation removing it fails that test.
  - The pinned coturn image ran from a regenerated conf with only `turnserver.pid` in `/var/tmp`, so no fallback log is written.
- **Tor base moved off EOL Alpine 3.20:**
  - `tor/Dockerfile` is now `FROM alpine:3.23.8` with `apk add --no-cache tor=0.4.9.17-r0`. That version is from `community/tor/APKBUILD` on the aports `3.23-stable` branch (GitHub mirror).
  - **The exact `tor=` pin will be verified on the deploy host.** `dl-cdn.alpinelinux.org` stays blocked in this sandbox by your decision (HTTP 403), so the image build fails at `apk add` here.
  - The compose test now requires Alpine 3.22 or newer for tor.
- **node and caddy pinned by tag and digest**, like coturn, each with a comment on refreshing the digest:
  - `node:22.23.5-alpine3.24@sha256:1314fb30…481a`
  - `caddy:2.11.9-alpine@sha256:003a9780…36f0`
  - Both are the top-level multi-arch index digests from `docker buildx imagetools inspect`, and match the RepoDigests of the images pulled here. The tests require the `@sha256:` form.
- **`header -Server`:** kept on both site blocks.
- **Gate re-run:**
  - `npm run check`: PASS.
  - `npm test`: 103/103 PASS.
  - `bash -n` on each script: PASS.
  - `shellcheck`: SKIPPED (tool unavailable).
  - `npm run check:compose`: PASS.
  - `caddy validate` in the digest-pinned caddy image: "Valid configuration".
  - `docker build darkp2p:audit`: PASS, using the same sandbox-CA base override, keyed on the digest-pinned reference. The image ran healthy as `node` with `NODE_ENV=production` under the compose limits.
  - `docker build darkp2p-tor:audit`: FAILED (network policy: `dl-cdn.alpinelinux.org` 403). Deferred to the deploy host.

#### Step 10 checklist (added)
- [ ] README deploy steps: run `sudo scripts/gen-turnserver-conf.sh` before `docker compose up`, and after any change to `TURN_HOST`, `TURN_PUBLIC_IP` or `TURN_SECRET`. Update the "No TURN Logs" line, which still names the Step 2 command-line flags (now `no-stdout-log`, `simple-log` and `log-file=/dev/null` in the generated conf).
- [ ] README upgrade/deploy notes: before starting the new tor image on an existing install, run the one-time `tor_data` chown so the `.onion` keys survive:
  `docker compose run --rm --no-deps --user root --entrypoint chown tor -R tor:tor /var/lib/tor`
- [ ] On the deploy host: `docker compose build tor` to confirm the `tor=0.4.9.17-r0` pin on Alpine 3.23.8, and bump the pin if the repository has moved on.

### Step 10 — README and final verification (2026-11-21)
- **Tasks:** 10.1, 10.2.
- **Files changed:** `README.md` only, plus this log.
- **10.1 — README:**
  - The two broken `[https://…](https://…)` links (the room-link example and `git clone`) are plain URLs now.
  - New **Containers** table covers all four services: image and pin, role, hardening, logging.
  - New **coturn configuration and logging policy** section: `gen-turnserver-conf.sh`, mode 0600 and owner, when to re-run, the deny list, and `no-stdout-log` + `simple-log` + `log-file=/dev/null` with why `simple-log` is needed. It notes the logging driver `none`, the debug procedure, and that the port range is shared with `setup-ufw.sh`. The "No TURN Logs" line now points there.
  - New **Configuration** table covers all 30 `envStr`/`envInt` keys in `server.js` plus `TURN_PUBLIC_IP`, with defaults, ranges and fatal conditions, and the stats line format.
  - New **Pro payments and claims** section: the client's pending claim and back-off, the webhook, behaviour with and without lookup, deterministic expiry, 503/502, the negative cache and the 404 after the window.
  - The wire format section was already v3. Two descriptions there are updated to match Step 7: the save picker → OPFS → RAM order in the storage bullet, and `busy` only while already receiving.
  - The deploy steps add `sudo scripts/gen-turnserver-conf.sh`, `SSH_PORT`, `IPV6=yes`, and the note that Docker-published ports bypass UFW.
  - The lockfile step is removed: `package-lock.json` is committed.
  - Onion bootstrap: `exec tor cat …/hostname`, then set `ONION_HOST`, then `docker compose up -d darkp2p`.
  - New **3c Upgrading an Existing Install**: the coturn conf script, the one-time `tor_data` chown, verifying or bumping the `tor=` pin, wire format v3 (everyone reloads), and `ONION_PORT`.
  - The Hide IP (TURN-only) and Force WebSocket relay toggles are described.
  - This completes the Step 8 and Step 9 checklists.
- **10.2 — Final verification:**
  - `npm run check`: PASS. `npm test`: 103/103 PASS, 0 skipped, 0 todo.
    - The only skips in the code are conditional, each with a printed reason: `openssl not available` (claim tests) and `docker compose not available` (compose tests). Neither fired here.
    - There is no `.skip`, `.only` or `.todo`.
  - **Docker stack:**
    - `docker compose --env-file test/fixtures/compose.env up -d` was run for `caddy`, `darkp2p` and `coturn`, with a temporary `.env` and `coturn/turnserver.conf` generated from the fixture.
    - **tor was not started:** its image cannot build here (`dl-cdn.alpinelinux.org` returns 403, deferred to the deploy host per the Step 9 decision).
    - **darkp2p was pre-built** with the sandbox-CA base override (as in Step 9) and started with `--no-build`.
    - darkp2p became healthy, and caddy and coturn were running.
  - **Through Caddy:**
    - `curl -sI http://127.0.0.1/` with `Host: example.test` gives `308` to https.
    - HTTPS cannot get a certificate here: ACME is blocked and `example.test` is not a real domain.
    - A throwaway caddy with the same Caddyfile plus `tls internal` ran on the compose network:
      - `/` gives `HTTP/2 200` with the CSP, `Onion-Location` and HSTS, and **no `Server` header**;
      - `/health` gives `ok`;
      - a 100 KB POST to `/webhook/btcpay` gives `413`;
      - `www.` gives `301` to the apex.
  - **Log grep (IPv4/IPv6 literals), 42 lines from darkp2p and caddy:**
    - darkp2p: none.
    - caddy: 11 lines, **none a client address**. They are Caddy's admin-endpoint origin list (`localhost:2019`, `[::1]:2019`, with `127.0.0.1`) and Docker's embedded DNS resolver `127.0.0.11` inside ACME "lookup failed" errors, which exist only because the sandbox blocks ACME.
    - The failed-TLS curl attempts logged no peer address.
  - `docker compose down -v`: done. The temporary `.env`, `coturn/turnserver.conf` and images were removed, and no containers or volumes are left.
- **Regression map:** no audit document with IDs C1, C2, H1–H7 and M1–M11 is in the repository or git history. Only H7, I3 and I5 appear, in Step 8. IDs are not guessed. The map below is by remediation task. Each task's audit ID should be filled in from your audit list.

| Area (step.task) | Audit ID | Covered by (test file → test) |
|---|---|---|
| Compose env merge (1.A) | ? | `compose.test.mjs` → "darkp2p env has no merged list items…", "…interpolates nothing, only fixed non-secret values" |
| Onion isolation (1.B) | ? | `onion.test.mjs` → all 6 (shared bucket and XFF ignored, Host spoof, Onion-Location only on main, ONION_HOST without ONION_PORT, ONION_PORT = PORT, ONION_PORT without HOST) |
| coturn logging and relay hardening (2.A–C, 9.4) | ? | `compose.test.mjs` → "coturn: config file only…", "gen-turnserver-conf: hardened coturn flags…", "…refuses a missing or bad…", "setup-ufw.sh opens the same relay range…" |
| Durable claims (3.A/B) | ? | `claim.test.mjs` → all 14; `baseline.test.mjs` → "settled pro-1d invoice can be claimed…", "webhook with a bad signature gets 401" |
| Relay stall, relay room cap, control budget (4) | ? | `relay.test.mjs` → all 5 |
| Wire format, per-transfer keys, AAD (5) | ? | `framing.test.mjs` → header/overhead, round-trip, bit flips, reflection, splice, distinct keys, wrong room key, legacy types, malformed, ≤16384 |
| Accept/decline, credit window, completion, abort (6) | ? | `protocol.test.mjs` → all 21 |
| Session-nonce binding, v3 (6.5) | ? | `framing.test.mjs` → "session binding: …" ×2, "hello keys are separate…", "session nonces must be 32 bytes", "frame types are v3…" |
| Reconnect, relay budget, OPFS cleanup (7) | ? | `resilience.test.mjs` → all 6; tunnel survival and restart are manual (below) |
| Config injection (8.1) | H7, I3 | `config.test.mjs` → all 12 |
| Stats line (8.2) | I5 | `stats.test.mjs`; `baseline.test.mjs` → "log hygiene…" |
| Log hygiene (rule 4) | ? | `baseline.test.mjs` → "log hygiene…"; `claim.test.mjs` → "logs contain no invoice ID…" |
| CSP and request parsing (baseline) | ? | `baseline.test.mjs` → CSP hash, `GET //[` → 400, foreign Origin → 403, SDP field stripping |
| Infra hardening (9.1–9.3, 9.5, 9.6) | ? | `compose.test.mjs` → "every service: no-new-privileges…", "caddy keeps DOMAIN; tor…"; **manual:** `caddy validate`, `docker build tor/` on the deploy host, `sudo ./setup-ufw.sh` then `ufw status verbose` |

- **Manual browser checklist:** printed in the Step 10 checkpoint.
- **SKIPPED / deferred:**
  - `shellcheck`: tool unavailable.
  - tor image build and pin verification: deploy host, network policy.
  - HTTPS with a real certificate and a WebSocket through Caddy over TLS: needs a real domain.
  - All browser scenarios: manual checklist.
- **Open questions:**
  1. **Audit IDs:** please share the C1–M11 list, or confirm the task-based map, so the "?" column can be filled in.
  2. **`Server: Caddy` on port 80:** it is still sent on the automatic HTTP→HTTPS redirect. That listener is generated by Caddy, not our site blocks, so `header -Server` does not reach it. A fix is an explicit `http://{$DOMAIN}, http://www.{$DOMAIN} { header -Server … redir … }` block. Apply?
  3. **Caddyfile formatting:** Caddy logs "Caddyfile input is not formatted". This is cosmetic and was left as-is.
  4. **`docs/remediation/`:** keep it as a changelog, or delete it?

### Decisions after Step 10 (user, 2026-11-21)
- **Regression map:** the task-based map is final. The C1–M11 audit IDs are not back-filled, and the "?" cells stay as they are.
- **`Server: Caddy` on port 80:** kept as-is. Caddy's built-in automatic HTTP→HTTPS redirect stays untouched, because a hand-written `http://` block to strip one header on a 308 is riskier for ACME HTTP-01 renewals than the header is worth.
- **Caddyfile formatting warning:** no decision was requested, and it stays cosmetic and unchanged.
- **`docs/remediation/`:** kept in the repository as the historical changelog and audit trail. It gives future sessions the context behind `simple-log`, `ONION_PORT`, wire format v3 and the other remediation decisions.
- **Status:** the remediation is complete (Steps 0–10). No further changes are planned. Two Step 10 items remain for the deploy host and the browser: the tor image build and pin check, and the manual browser checklist.

### Post-remediation — NOWPayments replaces BTCPay (2026-11-21)
- **Files changed:** `server.js`, `public/index.html`, `Caddyfile`, `.env.example`, `README.md`, `test/helpers/server.mjs`, `test/claim.test.mjs` (rewritten), `test/baseline.test.mjs`, `test/config.test.mjs`, `test/compose.test.mjs`, `test/fixtures/compose.env`. The local `.env` holds the tip addresses and is gitignored.
- **Server:**
  - `NOWPAYMENTS_API_KEY`, `NOWPAYMENTS_IPN_SECRET`, `NOWPAYMENTS_API_URL` (https, default `https://api.nowpayments.io`), `PRO_1D_PRICE_USD` (2) and `PRO_30D_PRICE_USD` (5) replace every `BTCPAY_*` var and `PRO_TIERS`.
  - `POST /api/checkout`: per-client rate limit 10/min, onion clients share 60/min, 10 000 tracked clients with the oldest evicted. Body cap 4 KB, JSON content type only. It creates a NOWPayments invoice with `order_id = <tier>:<32 hex>` and returns `{invoiceUrl}`.
  - `POST /webhook/nowpayments`: `x-nowpayments-sig` is checked as HMAC-SHA512 over the recursively key-sorted JSON, with `timingSafeEqual`. Only `finished` mints; other statuses get `200 {ok:true, ignored:true}`.
  - Tokens are deterministic: `exp = created_at + days·86400 + 6 h`, with the Step 3 claim window, negative cache, 503/502 handling and log hygiene.
  - `/api/claim` takes `paymentId` (restart-safe through `GET /v1/payment/{id}`) or `invoiceId` (from the recorded IPN only).
- **Client:** the config is `{checkoutEnabled, priceUsd, tipXmr, tipBtc}`. The buttons post to `/api/checkout` and follow the invoice URL. `?NP_id=` and `?invoiceId=` are captured, persisted, stripped from the URL and polled as in Step 3. Old pending claims are discarded.
- **Gate:** `npm run check` PASS; `npm test` 122/122 PASS.
  - The real tip addresses were boot-tested out of tree: exact config JSON, CSP 1/1, not in the logs.
  - A Playwright/Chromium run against a mock NOWPayments passed 12/12.
  - Mutation checks (unsorted signature, minting on `confirmed`, no price check) were each caught.
- **Decisions (user, 2026-11-21):** all 8 design calls are approved as implemented:
  1. The signed IPN mints from its own `created_at`, and a lookup is used only for restart or pre-IPN claims and for an IPN without `created_at`.
  2. Restart-safe claims need the payment ID; there is no lookup by invoice ID.
  3. `NP_id` is treated as the payment ID. Verify this once in the sandbox.
  4. Before minting, the order prefix, USD and `price_amount ≥` the tier price are required.
  5. `priceUsd` is added to the page config.
  6. Checkout requires a JSON content type and redirects in the same tab.
  7. `PRO_TIERS` is removed.
  8. Boot is fatal on mismatched NOWPayments, IPN and Pro secrets; an IPN secret under 32 characters only warns.
  - **Tests keep synthetic look-alike addresses.** The personal tip addresses stay out of tracked files.

### Post-audit Phase 1 — coturn deny list, relay check, `isTransferring` (2026-11-22)
- **Source:** the 2026-11-22 read-only audit, findings High #1, #2 and #3.
- **Files changed:** `scripts/gen-turnserver-conf.sh`, `public/index.html`, `test/compose.test.mjs`, `README.md`, `.env.example`; new `scripts/check-turn-relay.sh`. `server.js` is unchanged.
- **High #1:** `denied-peer-ip=::-::` is removed. In the pinned coturn image that rule alone answers every IPv4 peer with `403 Forbidden IP`, so TURN relayed nothing.
- **High #2:** `denied-peer-ip=$TURN_PUBLIC_IP` is removed, so two peers who both relay can reach each other's relay address. Relaying stays UDP-only. `TURN_PUBLIC_IP` is still required and validated by the generator, and is now also the address the relay check tests.
- **Relay check:** `scripts/check-turn-relay.sh [ENV_FILE]` runs `turnutils_peer` and `turnutils_uclient` from the coturn image named in `docker-compose.yml`, on the host network, against the running coturn: client → relay → echo peer, and relay-to-relay (`-y`). It exits 1 with a reason on failure. The README deploy step documents it.
- **High #3:** the `connected` branch of `pc.onconnectionstatechange` called the removed `isTransferring`. It now calls `enableDropIfReady()`.
- **Tests:** the generator test no longer requires the two rules and now fails if either returns; one new test checks that the relay check script parses, covers both paths and takes its image from `docker-compose.yml`.
- **Gate:** `npm run check` PASS; `npm test` 134/134 PASS, 0 skipped.
- **Verified with the pinned coturn image (local dockerd, not committed):** against a server started from the regenerated config, the check passes both paths. It fails, with the matching reason, when `::-::` is added back, when the own-address deny is added back, with a wrong secret, and with coturn stopped.
- **SKIPPED:** `shellcheck` (tool unavailable). The `connected`-after-`disconnected` path was not exercised in a browser.
- **Open questions:**
  1. `TURN_PUBLIC_IP` no longer affects the generated config, yet the generator still refuses to run without it. **Decided (user, 2026-11-22): keep it required for now.**
  2. While a send is in progress, a recovered connection leaves the drop label on "CONNECTION LOST" until the send ends, because `enableDropIfReady()` does nothing during a send.
