# DARKP2P

End-to-end encrypted peer-to-peer file transfer directly in the browser. Files stream browser-to-browser over WebRTC `RTCDataChannel` with zero server file storage and zero database. Direct transfers have no server file-size cap. When a direct connection is not possible, the server relays the already-encrypted frames over WebSocket, in RAM, within a per-room byte cap (50 MB free / 500 MB Pro by default).

The server cannot read file contents, names or keys. It does observe connection metadata: client addresses (held only as salted hashes in RAM), room IDs, and relay byte counts and timing.

Live instance: [darkp2p.com](https://darkp2p.com)

---

## Architecture

* **Zero Server Storage:** `server.js` is a stateless Node.js WebSocket signaling broker (`ws` only) that pairs two peers in RAM and relays SDP offers/answers and ICE candidates. Nothing is written to disk. On a direct WebRTC DataChannel, file payloads never touch the server. On the WebSocket relay fallback, the server forwards the end-to-end encrypted frames from one peer to the other through RAM, holding each frame only until it is delivered.
* **WebSocket Relay Fallback:** Used when the browser has no WebRTC (Tor Browser), when *Force WebSocket relay* is ticked, when no DataChannel opens within 12 s, or when the WebRTC connection fails. The same AES-256-GCM frames travel as binary WebSocket messages, so the server sees ciphertext, frame sizes and timing, and the cleartext frame header (type, `transferId`, `seq`, IV), but never the key, the file contents or the file name. Limits apply per room: a byte cap counting both directions until the room empties (`RELAY_CAP_FREE_MB`, default 50 MB; `RELAY_CAP_PRO_MB`, default 500 MB), a per-sender rate of 1 MB/s (4 MB/s in a Pro room), and at most `MAX_RELAY_ROOMS` rooms relaying at once.
* **Client-Side E2EE (Anti-MITM):** Standard WebRTC DTLS protects against passive observers, but a compromised signaling server could swap SDP certificate fingerprints. DARKP2P adds an application-layer **AES-256-GCM** stream using a 256-bit key generated locally in the URL fragment:
  ```text
  https://darkp2p.com/#<32-hex-roomId>:<base64url-256bit-key>
  ```
  Browsers never transmit URL fragments (`#...`) over HTTP or WebSockets. Only `<roomId>` is sent to the signaling server.
* **Low-RAM Disk Streaming:** Incoming chunks stream directly to disk: to a file the receiver picks (`showSaveFilePicker`) where supported, otherwise to the browser's Origin Private File System (OPFS), and only as a last resort to an in-memory `Blob` (capped at 512 MB). Multi-gigabyte direct transfers do not crash browser tabs. The server sets no file-size cap on direct transfers; the practical limits are the receiver's disk space and browser storage quota.
* **Back-Pressure & Chunking:** Files are sliced into `16,000`-byte chunks (leaving headroom for the 49-byte encryption envelope under the 16 KB cross-browser SCTP limit). Flow control uses `bufferedAmount` (capped at 1 MB) plus an end-to-end credit window: at most 256 unacknowledged chunks in flight.
* **Privacy-Preserving Rate Limiting:** Connection and message limits use a per-boot random salt to HMAC-hash client IPs in RAM—raw IP addresses are never stored or logged.
* **Isolated Onion Listener:** Tor hidden-service traffic reaches the server on a dedicated port (`ONION_PORT`, `3001` in Docker Compose). Onion clients are identified by that listener alone, never by the `Host` or `X-Forwarded-For` headers, so they cannot pose as clearnet clients to dodge rate limits.

---

## Wire Format (AES-256-GCM Framing)

Wire format **v3**. Every control message (`hello`, `hello-ack`, `meta`, `accept`, `decline`, `ack`, `done`, `complete`, `abort`) and binary file chunk is a separate frame, encrypted with Web Crypto (`AES-GCM`) and a fresh random 12-byte IV:

```text
+---------+-----------------+--------------+----------+-----------------------------------+
| 1B type | 16B transferId  | 4B seq (BE)  | 12B IV   | AES-256-GCM ciphertext + 16B tag  |
+---------+-----------------+--------------+----------+-----------------------------------+
|<---------------- 33B header ----------------------->|
```

* `type`: `0x20` = hello, `0x21` = JSON control frame, `0x22` = binary file chunk. Frames of the older formats (v1 types `1`/`2`, v2 types `0x11`/`0x12`) are reported as an outdated peer.
* `transferId`: 16 random bytes, new for every file and every `hello`.
* `seq`: big-endian 32-bit counter within a transfer, per direction (see **Transfer** below).
* **Session- and transfer-bound keys:** every control frame and file chunk is encrypted with `HKDF-SHA256(ikm = 32-byte room key, salt = transferId ‖ initNonce ‖ respNonce, info = "darkp2p/v3/transfer")`, an AES-GCM-256 key. `initNonce` and `respNonce` are the two peers' 32-byte session nonces from their hellos, initiator first. Frames from one file never decrypt under another, and frames recorded in an earlier session (or page load) never decrypt in a later one, even with the same link.
* **Hello key:** hello frames are sent before the session exists, so they use `HKDF-SHA256(room key, salt = transferId, info = "darkp2p/v3/hello")`.
* **AAD** = `"DP3" ‖ type ‖ senderRole ‖ transferId ‖ seq`. `senderRole` (`0x01` initiator, `0x02` responder, from the signaling `ready` message) is not sent on the wire. The receiver authenticates with its peer's role, so a frame reflected back to its sender fails.
* Overhead is 49 bytes per frame: a 16,000-byte chunk becomes a 16,049-byte frame, under the 16 KB SCTP limit.

**Handshake (hello → hello-ack).**
1. When a transport opens (DataChannel, or the WebSocket relay), each side picks a new random 32-byte nonce and sends `{type:"hello", v:3, nonce}` as a hello frame (new `transferId`, `seq 0`).
2. When a side knows both nonces, it derives the session and sends `{type:"hello-ack", v:3}` as a control frame under the session key.
3. File selection is enabled only after the peer's hello-ack decrypts. That proves the peer holds the same room key and derived the same session.

If either side sends a new hello (for example after switching to the WebSocket relay), the session is renewed, and transfers of the old session stop. A hello-ack from a superseded session is ignored.

* A hello that fails authentication shows *"Key mismatch — the link was altered"*.
* An older-format frame, a hello with `v` other than 3, or no hello within 5 s shows *"Peer is running an outdated DARKP2P page — both sides reload"*.
* A valid hello with no hello-ack within 5 s logs *"Peer did not confirm the session key — both sides reload"*. A hello-ack that arrives later still completes the handshake.

In each of these failure cases file selection stays disabled.

**Transfer.** All frames of one file use one `transferId`. The sender's frames are numbered by `seq`: `meta` (`{type, name, size, mime, chunks: N}`) at `0`, data chunks `1..N`, `done` (`{type, chunks, size}`) at `N+1`, and an `abort` takes the next `seq`. The receiver's replies go under the same `transferId` and its own role, numbered `1, 2, 3, …`. The sender rejects any reply that is not the next number.

```text
sender                                   receiver
  meta (seq 0)  ───────────────────────►  prompt: Accept / Decline
                ◄───────────────────────  accept | decline {reason}
  data 1..N     ───────────────────────►  (≤ 256 unacknowledged)
                ◄───────────────────────  ack {seq} every 64 chunks
  done (N+1)    ───────────────────────►  verify counts and size
                ◄───────────────────────  ack {seq: N, done: true}, then complete
"Delivered"
```

* **Accept / decline:** the sender waits up to 5 minutes for an answer, then aborts with `timeout`. `decline` reasons are `user`, `size` and `busy`. A receiver without disk streaming (no save picker and no OPFS) declines files over 512 MB automatically (`size`), and a peer that is already receiving a file declines with `busy`. Sending and receiving are independent, so both peers can send at the same time.
* **Credit window:** the receiver acknowledges every 64 chunks and after `done`. The sender keeps at most 256 chunks unacknowledged, in addition to the `bufferedAmount` limit. If more than 256 chunks are waiting to be processed, the receiver aborts. If the window stays full for 60 seconds with no new acknowledgement, the sender aborts with `timeout`.
* **Completion:** the sender shows *"Sent, awaiting confirmation"* after `done`, and *"Delivered"* only once the receiver's `complete` arrives (after it has verified and saved the file). The receiver's acknowledgement of `done` carries `done: true`, so it cannot be mistaken for a chunk ack.
  * If `done` was acknowledged but `complete` never arrives (the peer closed the tab, or 5 minutes pass), the sender ends with a neutral status: *"Sent — peer closed before final confirmation"* or *"Sent — no final confirmation from the peer"*. No abort is sent.
  * If `done` itself was never acknowledged, the transfer ends as an interruption, or as a `timeout` abort after 5 minutes.
* **Abort:** either side can send `{type:"abort", reason}` with a reason of `user`, `integrity`, `disk` or `timeout`. Both sides then release the transfer's state, storage and UI. Late frames of an ended transfer are dropped quietly.

The receiver rejects:
* a data chunk or `done` from any transfer other than the active one;
* any `seq` other than the next expected one;
* a chunk that would take the byte count past `meta.size`;
* a `done` whose chunk or byte counts differ from what was announced and received;
* a `meta` reusing a `transferId` already seen in this page session, so a whole earlier file cannot be replayed within a session. Replay across sessions fails decryption because of the session-bound keys.

---

## Containers

`docker-compose.yml` runs four containers. Every one has `no-new-privileges`.

| Service | Image | Role | Hardening and logging |
|---|---|---|---|
| `caddy` | `caddy:2.11.9-alpine` (tag + digest) | TLS termination and reverse proxy for `DOMAIN` (and the `www.` redirect). Publishes `80/tcp`, `443/tcp` and `443/udp` (HTTP/3). | `cap_drop: ALL` plus `NET_BIND_SERVICE`. Strips the `Server` header, a 10 s header read timeout, a 64 KB body limit on `/webhook/nowpayments`, and keeps proxied WebSockets open for up to 5 min after a Caddy config reload (`stream_close_delay`). No access log. Docker `local` log driver, 3 × 10 MB. |
| `darkp2p` | built from `Dockerfile` (`node:22.23.5-alpine3.24`, tag + digest) | The signaling server: page, WebSocket signaling and relay, `/api/checkout`, `/api/claim`, `/webhook/nowpayments`, `/health`. Listens on `3000` (via Caddy) and the onion-only `3001`. | Runs as `node` with `NODE_ENV=production`, a read-only root, `cap_drop: ALL`, tmpfs `/tmp`, `mem_limit: 384m`, `pids_limit: 128`, and a `/health` healthcheck. `local` logs, 3 × 10 MB. Logs contain no addresses, room IDs, payment/invoice/order IDs or tokens. |
| `tor` | built from `tor/Dockerfile` (`alpine:3.23.8`, `tor=0.4.9.17-r0`) | v3 onion service: `http://<onion>/` forwards to `darkp2p:3001`. No SOCKS port. | Runs as user `tor`. `tor/torrc` is mounted read-only. Keys persist in the `tor_data` volume. `HiddenServiceMaxStreams 256` with `HiddenServiceMaxStreamsCloseCircuit 1`. `local` logs, 3 × 10 MB. |
| `coturn` | `coturn/coturn:4.6.3-r3-alpine` (tag + digest) | STUN on `3478` and TURN with ephemeral credentials for Pro rooms. UDP relay ports `49160-49200`. Host networking. | Runs as `nobody` with a read-only root and tmpfs state. All flags, including the secret, come from a generated `coturn/turnserver.conf` (see below). Docker logging driver `none`. |

---

## Static Pages, Assets and Crawl Files

`server.js` serves a fixed set of files from `public/`, read into memory at boot. Nothing else in `public/` is reachable.

| URL | Source |
|---|---|
| `/` | `public/index.html` (the app) |
| `/how-it-works`, `/security`, `/tor`, `/compare`, `/abuse` | `public/<name>.html`. A trailing slash redirects (301) to the clean URL. |
| `/og-image.png`, `/favicon.svg`, `/apple-touch-icon.png` | Same names in `public/`. |
| `/<key>.txt` | The IndexNow key file, `public/<32 hex>.txt`. |
| `/robots.txt`, `/sitemap.xml` | Generated from `DOMAIN` and the `SITE_PAGES` list in `server.js`. |
| anything else | `public/404.html` with status 404. |

* **Adding or editing a page:** add it to `SITE_PAGES` in `server.js` and bump its `lastmod` when the content changes. Pages are plain HTML with inline CSS and no script; `https://darkp2p.com/` in them is replaced with `https://<DOMAIN>/` at boot.
* **Onion address:** `public/tor.html` shows the block between `<!--onion:on-->` markers when `ONION_HOST` is set (`{{ONION_HOST}}` is the hostname) and the `<!--onion:off-->` block otherwise.
* **Images:** `assets/og-image.svg` is the source of `public/og-image.png`. After editing it, run `node scripts/render-images.mjs` (needs Playwright with Chromium; not a project dependency).
* **IndexNow:** after a deploy that changed page content, run `scripts/indexnow.sh` by hand (`--dry-run` prints the request). Nothing runs it automatically.

---

## Threat Model & Limitations

* **IP Exposure:** Direct WebRTC P2P connections expose each peer's IP address to the other peer during ICE/NAT traversal. Use a VPN if you need IP anonymity.
* **Server-Visible Metadata:** The signaling server sees each client's address (used for rate limiting only as a per-boot salted hash, never stored or logged), room IDs, join and leave timing, the SDP and ICE candidates it forwards, and whether a Pro token was presented. In relay mode it also sees relayed byte counts, frame sizes and timing, which reveal the approximate file size.
* **File-Size Limits:** Direct DataChannel transfers have no server cap. WebSocket relay transfers are limited by the room's byte cap (50 MB free / 500 MB Pro by default), which covers every Tor Browser transfer. A receiver without disk streaming is limited to 512 MB.
* **No Forward Secrecy on the Relay:** Every frame key is derived from the room key in the share link, and the session nonces travel encrypted under that same key. Anyone who records relayed frames (the relay operator is best placed to) and later obtains the share link can decrypt the recording. On a direct connection the frames are additionally wrapped in WebRTC's DTLS. Treat the link as a secret for as long as the file matters, and use a fresh link per transfer.
* **Synchronous Transfer:** Both peers must keep their browser tabs open until the transfer completes.
* **TURN Relay:** Direct STUN works for ~80% of connections. For strict corporate firewalls or CGNAT, the stack includes `coturn` with ephemeral HMAC-SHA1 credentials (issued only to rooms where a peer holds a stateless HMAC-signed Pro token). Relaying is UDP-only (`--no-tcp-relay`; clients can still reach coturn over TCP 3478), and private, loopback, reserved, NAT64/6to4, IPv4-mapped and the server's own public addresses are denied as peers.
* **No TURN Logs:** coturn logs every client and peer address, so its logging is disabled. See **coturn configuration and logging policy** below.

### coturn configuration and logging policy

`docker-compose.yml` contains no TURN secret and no coturn flags. Instead `scripts/gen-turnserver-conf.sh` reads `TURN_HOST`, `TURN_PUBLIC_IP` and `TURN_SECRET` from `.env` and writes `coturn/turnserver.conf`. The file is mode `0600` and owned by coturn's container user (`65534:65533`), so run the script with `sudo`. coturn starts with `-c /etc/coturn/turnserver.conf`, and the file is mounted read-only and gitignored.

```bash
sudo scripts/gen-turnserver-conf.sh          # reads .env, writes coturn/turnserver.conf
docker compose up -d coturn                   # pick up the new file
```

Re-run it whenever `TURN_HOST`, `TURN_PUBLIC_IP` or `TURN_SECRET` changes. The script refuses a missing or malformed value, and the secret must be at least 16 printable characters with no spaces or `#`.

The generated file:
* sets UDP-only relaying (`no-tcp-relay`), per-user and total quotas, bandwidth caps, and no TLS/DTLS listener;
* denies relaying to private, loopback, link-local, CGNAT, reserved, NAT64, 6to4, IPv4-mapped and ULA ranges, and to `TURN_PUBLIC_IP` itself;
* disables logging with `no-stdout-log`, `simple-log` and `log-file=/dev/null`. Without `simple-log`, coturn would append a date to the file name, fail to open `/dev/null_<date>.log`, and fall back to writing a log in `/var/tmp`.

Docker's logging driver for coturn is `none`, so nothing reaches the host disk. To debug temporarily, follow the comment on the `coturn` service in `docker-compose.yml`, then revert and remove the container.

The relay port range (`49160-49200`) is defined in both `scripts/gen-turnserver-conf.sh` and `setup-ufw.sh`. Change them together; a test checks that they match.

---

## Configuration (environment variables)

All configuration lives in `.env`. Compose passes the whole file to `darkp2p` (`env_file`). `docker-compose.yml` itself sets only `PORT=3000` and `ONION_PORT=3001`, and uses `DOMAIN` for Caddy. An integer outside its range logs a warning and falls back to the default. Every other invalid value listed as fatal stops the server at boot.

| Variable | Default | Description |
|---|---|---|
| `DOMAIN` | `darkp2p.com` | Public hostname. It is used by Caddy, the default `ALLOWED_ORIGINS`, and the canonical/OG URLs in the page. Fatal if it is not a hostname. |
| `PORT` | `3000` | Main HTTP/WS listener (1–65535). Set by compose. |
| `ONION_PORT` | `0` (off) | Onion-only listener; compose sets `3001`. Anything arriving here is treated as onion traffic, whatever its headers say. Must differ from `PORT`. |
| `ONION_HOST` | empty | The service's v3 `.onion` hostname. It enables `Onion-Location` and the onion origin. Fatal if it is not a v3 onion, or if it is set without `ONION_PORT`. |
| `ALLOWED_ORIGINS` | `https://${DOMAIN}` | Comma-separated http(s) origins allowed to open the WebSocket. `http://${ONION_HOST}` is added whenever `ONION_HOST` is set. |
| `TRUST_PROXY` | `auto` | Trust `X-Forwarded-For`: `auto` only from a loopback/private peer (the bundled Caddy), `1`/`true` always, `0`/`false` never. Never applied on the onion listener. |
| `TURN_HOST` | empty | coturn hostname. It enables TURN for Pro rooms (with `TURN_SECRET`) and `stun:${TURN_HOST}:3478` as the STUN server. |
| `TURN_SECRET` | empty | coturn shared secret for ephemeral HMAC credentials. It is read by `darkp2p` and by `scripts/gen-turnserver-conf.sh`. Use ≥ 32 characters (`openssl rand -hex 32`). |
| `TURN_PUBLIC_IP` | — (**required for coturn**) | The server's public IPv4. It is read only by `scripts/gen-turnserver-conf.sh`, which denies it as a relay peer and refuses to run without it. |
| `TURN_TTL` | `3600` | Lifetime of TURN credentials in seconds (60–86400). |
| `STUN_URLS` | empty | Comma-separated `stun:`/`stuns:` URLs that override the STUN server. Without it and without `TURN_HOST`, the page falls back to `stun.l.google.com` (with a warning). |
| `PRO_TOKEN_SECRET` | empty | HMAC key for Pro tokens. Without it, Pro is disabled. Required if `NOWPAYMENTS_API_KEY` is set. |
| `PRO_TOKEN_DAYS` | `30` | Lifetime of the `pro-30d` tier, in days (1–3650). `pro-1d` is always 1 day. |
| `CLAIM_TTL_HOURS` | `48` | How long a paid token can be claimed (1–168), counted from the payment's `created_at` + 6 h. See **Pro payments and claims**. |
| `NOWPAYMENTS_API_KEY` | empty | Enables checkout (`POST /api/checkout`), the IPN webhook (`POST /webhook/nowpayments`) and payment lookups. Without it the checkout buttons are hidden. |
| `NOWPAYMENTS_IPN_SECRET` | empty | IPN secret from the NOWPayments dashboard. Verifies `x-nowpayments-sig` (HMAC-SHA512 over the key-sorted JSON). Required with `NOWPAYMENTS_API_KEY`, and fatal without it. |
| `NOWPAYMENTS_API_URL` | `https://api.nowpayments.io` | API base URL (https, no query or credentials), e.g. `https://api-sandbox.nowpayments.io` for testing. |
| `PRO_1D_PRICE_USD` | `2` | Price of the 24-hour pass in USD (positive, ≤ 2 decimals). Shown on the page and charged. |
| `PRO_30D_PRICE_USD` | `5` | Price of the 30-day pass in USD. |
| `TIP_XMR_ADDRESS` | empty | Monero address for the tip button: mainnet standard (`4…`) or subaddress (`8…`), 95 characters, or integrated (106). The button is hidden when unset. |
| `TIP_BTC_ADDRESS` | empty | Bitcoin address for the tip button: mainnet lowercase `bc1q…`/`bc1p…` (Taproot), or legacy `1…`/`3…`. The button is hidden when unset. |
| `MAX_TOTAL_CONNS` | `1000` | Global WebSocket cap (2–100000). |
| `MAX_CONNS_PER_IP` | `6` | Concurrent WebSockets per hashed client address (1–10000). |
| `MAX_ONION_CONNS` | `100` | Concurrent WebSockets on the onion listener, which shares one bucket (2–10000). |
| `RELAY_CAP_FREE_MB` | `50` | WebSocket-relay byte budget per free room, both directions (0 disables the relay). |
| `RELAY_CAP_PRO_MB` | `500` | Relay budget per Pro room. |
| `MAX_RELAY_ROOMS` | `50` | Rooms that may use the relay at once (1–10000). Others get `relay-busy`. |
| `RELAY_STALL_MS` | `60000` | A relay receiver that stops reading for this long is closed with `relay-stalled` (1000–600000). |
| `HEARTBEAT_MS` | `30000` | WebSocket ping interval (1000–300000). |
| `SWEEP_MS` | `300000` | Interval of the cleanup sweep and of the stats line (1000–3600000). |

Every `SWEEP_MS` the server logs one counters-only line:

```text
[darkp2p] stats conns=N rooms=N relayRooms=N relayMB=N.N rejected429=N stalls=N
```

`conns`, `rooms` and `relayRooms` are current values. `relayMB` (relayed binary data), `rejected429` (WebSocket upgrades and `/api/claim` requests refused with 429) and `stalls` (`relay-stalled` closes) count the last interval only.

---

## Pro payments and claims

Payments run on [NOWPayments](https://nowpayments.io). darkp2p creates the invoices itself, so the only dashboard setup is an API key and an IPN secret (`NOWPAYMENTS_API_KEY`, `NOWPAYMENTS_IPN_SECRET`). Nothing about NOWPayments needs to run on this server or share `DOMAIN`.

1. **Checkout.** The buyer picks a tier. The page sends `POST /api/checkout {"tier":"pro-1d"|"pro-30d"}`, which:
   * is rate-limited per client (10 per minute; all onion clients share 60) and capped at 4 KB, and needs `Content-Type: application/json`, so other sites cannot trigger it;
   * calls NOWPayments `POST /v1/invoice` with:
     * `price_amount` (`PRO_1D_PRICE_USD` / `PRO_30D_PRICE_USD`), `price_currency: "usd"`;
     * `order_id` set to `<tier>:<random>`;
     * `ipn_callback_url: https://<DOMAIN>/webhook/nowpayments`;
     * `success_url` and `cancel_url` set to `https://<DOMAIN>/`;
   * returns `{invoiceUrl}`, and the tab navigates to it.
2. **Return.** After paying, NOWPayments sends the buyer back to `https://<DOMAIN>/?NP_id=<payment id>`; `?invoiceId=` is accepted too.
   * The page stores the pending claim in `localStorage` and removes the ID from the address bar, so the claim resumes after a reload or a browser restart.
   * It polls `GET /api/claim?paymentId=…` with back-off (3 s up to 30 s) for up to 6 hours, honours `Retry-After`, and gives up after 48 hours.
3. **IPN.** NOWPayments posts each status change to `/webhook/nowpayments`, signed in `x-nowpayments-sig`. The signature is an HMAC-SHA512 with `NOWPAYMENTS_IPN_SECRET` over the JSON body with its keys sorted recursively, compared in constant time.
   * Only `payment_status: "finished"` mints a token.
   * `waiting`, `confirming`, `confirmed`, `sending`, `partially_paid`, `failed` and `expired` get `200 {"ok":true,"ignored":true}`.

**Deterministic, restart-safe tokens.** A token is minted only for a finished payment whose `order_id` is one of ours (`pro-1d:`/`pro-30d:` + 32 hex), in USD, with `price_amount` at least the tier's price. Its expiry is `created_at + tier days + 6 h`, so the same payment always yields the same token:
* the IPN carries `created_at`, so it mints without an API call; an IPN without it is resolved through `GET /v1/payment/{payment_id}`;
* a claim by payment ID that misses the in-memory record is answered from `GET /v1/payment/{payment_id}`. That covers a claim after a server restart, or one that arrives before the IPN, and returns the same token;
* a claim by `invoiceId` is answered only from the record of a received IPN, because NOWPayments has no lookup by invoice ID with an API key;
* the claim window runs from `created_at` plus the 6 h grace, plus `CLAIM_TTL_HOURS`;
* if NOWPayments is unreachable, a claim gets `503` with `Retry-After: 30`, and an IPN that needed a lookup gets `502` (so NOWPayments retries);
* "not finished yet" answers are cached for 30 s;
* a redelivered IPN never mints twice.

After the window, `/api/claim` returns `404`. Logs never contain payment, invoice or order IDs, tokens, the API key or lookup URLs.

---

## Self-Hosting with Docker

### Prerequisites
* A Linux VPS (1 GB RAM / 1 vCPU is sufficient) with Docker and Docker Compose installed.
* DNS `A` records for `yourdomain.com` and `turn.yourdomain.com` pointing to the server's public IPv4 address.

### 1. Clone & Configure Environment

```bash
git clone https://github.com/YOUR_USERNAME/darkp2p.git
cd darkp2p

cat <<EOF> .env
DOMAIN=yourdomain.com
ONION_HOST=
TURN_HOST=turn.yourdomain.com
TURN_PUBLIC_IP=203.0.113.10
TURN_SECRET=$(openssl rand -hex 32)
TURN_TTL=3600
PRO_TOKEN_SECRET=$(openssl rand -hex 32)
PRO_TOKEN_DAYS=30
CLAIM_TTL_HOURS=48
NOWPAYMENTS_API_KEY=
NOWPAYMENTS_IPN_SECRET=
EOF
chmod 600 .env
```

Replace `203.0.113.10` with the server's public IPv4. `scripts/gen-turnserver-conf.sh` refuses to run without `TURN_PUBLIC_IP`. All configuration lives in `.env`: Compose passes it to the `darkp2p` container as-is (`env_file`), and `docker-compose.yml` itself holds no secrets. See **Configuration** above and `.env.example`.

Then generate coturn's config from `.env`:

```bash
sudo scripts/gen-turnserver-conf.sh
```

### 2. Configure Firewall (Ubuntu/Debian)

Opens only SSH (rate-limited), Caddy (`80/tcp`, `443/tcp`, `443/udp`), and `coturn` (`3478/tcp+udp`, `49160-49200/udp`). It also sets `IPV6=yes` in `/etc/default/ufw` so IPv6 is filtered too. Set `SSH_PORT` if SSH is not on 22:

```bash
sudo SSH_PORT=22 ./setup-ufw.sh
```

Ports published by Docker (Caddy's `80`/`443`) bypass UFW, because Docker inserts its own iptables rules ahead of UFW's. coturn uses host networking, so UFW does govern its ports.

### 3. Launch

`package-lock.json` is committed, so there is nothing to generate.

```bash
docker compose up -d --build
```

Verify that Caddy obtained a TLS certificate and all four containers (`caddy`, `darkp2p`, `tor`, `coturn`) are running. `coturn` has no logs by design.

```bash
docker compose ps
docker compose logs -f caddy
```

### 3b. Enable the Onion Mirror (Optional)

The `tor` container publishes a v3 hidden service that forwards to the server's onion listener (`darkp2p:3001`). Its keys live in the `tor_data` volume, so the address survives restarts and rebuilds. Leave `ONION_HOST` empty on first launch. Once tor has generated the address:

```bash
docker compose exec tor cat /var/lib/tor/hidden_service/hostname
# set ONION_HOST=<that hostname> in .env, then:
docker compose up -d darkp2p
```

Recreating `darkp2p` makes it send `Onion-Location` and allow the onion origin. Onion clients are always identified by the `3001` listener, never by headers.

### 3c. Upgrading an Existing Install

These changes need action on a server deployed before them:

1. **coturn config:** run `sudo scripts/gen-turnserver-conf.sh` before `docker compose up`. coturn no longer reads flags from `docker-compose.yml`, and the script needs `TURN_PUBLIC_IP` in `.env`.
2. **tor image:** tor now runs as the unprivileged `tor` user from a pinned image (`tor/Dockerfile`). Keys created by the old root-run container are root-owned, so hand the volume over **once** before starting the new image, or tor refuses the keys and the `.onion` address is lost:
   ```bash
   docker compose build tor
   docker compose run --rm --no-deps --user root --entrypoint chown tor -R tor:tor /var/lib/tor
   docker compose up -d
   ```
3. **tor package pin:** `tor/Dockerfile` pins `tor=0.4.9.17-r0` from Alpine 3.23. If `docker compose build tor` reports the version as unavailable, Alpine has moved on: bump the pin to the version listed at https://pkgs.alpinelinux.org/packages?name=tor&branch=v3.23.
4. **Wire format v3:** clients of different page versions cannot talk to each other. After deploying, everyone must reload open tabs; a stale tab shows *"Peer is running an outdated DARKP2P page — both sides reload"*.
5. **`ONION_PORT`:** onion traffic must use the dedicated `3001` listener, which compose sets. Outside compose, `ONION_HOST` without `ONION_PORT` refuses to start.

### 4. Minting a Stateless TURN Relay Token (Optional)

Free rooms use STUN only. If either peer in a room provides a valid signed token, both peers receive ephemeral `coturn` TURN credentials:

```bash
docker compose exec darkp2p node -e '
const crypto = require("crypto");
const exp = String(Math.floor(Date.now() / 1000) + 30 * 86400);
const sig = crypto.createHmac("sha256", process.env.PRO_TOKEN_SECRET).update(exp).digest("hex");
console.log(`${exp}.${sig}`);
'
```

To sell tokens automatically with crypto payments, set `NOWPAYMENTS_API_KEY` and `NOWPAYMENTS_IPN_SECRET`. See **Pro payments and claims** above.

With a Pro token, either peer can tick *Hide my IP from peer*. This forces TURN-only ICE (`iceTransportPolicy: "relay"`), so the other side sees coturn's address instead of yours; without TURN the page refuses rather than fall back to a direct connection. *Force WebSocket relay* skips WebRTC entirely and relays the encrypted frames through the server, within the room's relay budget.

---

## License

MIT
