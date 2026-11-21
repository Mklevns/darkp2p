# DARKP2P

Zero-knowledge, end-to-end encrypted peer-to-peer file transfer directly in the browser. Files stream browser-to-browser over WebRTC `RTCDataChannel` with zero server storage, zero database, and no file size limits.

Live instance: [darkp2p.com](https://darkp2p.com)

---

## Architecture

* **Zero Server Storage:** `server.js` is a stateless Node.js WebSocket signaling broker (`ws` only) that pairs two peers in RAM and relays SDP offers/answers and ICE candidates. File payloads never touch the server.
* **Client-Side E2EE (Anti-MITM):** Standard WebRTC DTLS protects against passive observers, but a compromised signaling server could swap SDP certificate fingerprints. DARKP2P adds an application-layer **AES-256-GCM** stream using a 256-bit key generated locally in the URL fragment:
  ```text
  [https://darkp2p.com/#](https://darkp2p.com/#)<32-hex-roomId>:<base64url-256bit-key>
  ```
  Browsers never transmit URL fragments (`#...`) over HTTP or WebSockets. Only `<roomId>` is sent to the signaling server.
* **Low-RAM Disk Streaming:** Incoming chunks stream directly to disk via the browser's Origin Private File System (OPFS) when supported (falling back to in-memory `Blob` assembly otherwise), allowing multi-gigabyte transfers without crashing browser tabs.
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

* **Accept / decline:** the sender waits up to 5 minutes for an answer, then aborts with `timeout`. `decline` reasons are `user`, `size` and `busy`. A receiver without disk streaming (OPFS) declines files over 512 MB automatically (`size`), and a peer already in a transfer declines with `busy`.
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

## Threat Model & Limitations

* **IP Exposure:** Direct WebRTC P2P connections expose each peer's IP address to the other peer during ICE/NAT traversal. Use a VPN if you need IP anonymity.
* **Synchronous Transfer:** Both peers must keep their browser tabs open until the transfer completes.
* **TURN Relay:** Direct STUN works for ~80% of connections. For strict corporate firewalls or CGNAT, the stack includes `coturn` with ephemeral HMAC-SHA1 credentials (optionally gated behind stateless HMAC-signed Pro tokens). Relaying is UDP-only (`--no-tcp-relay`; clients can still reach coturn over TCP 3478), and private, loopback, reserved, NAT64/6to4, IPv4-mapped and the server's own public addresses are denied as peers.
* **No TURN Logs:** coturn logs every client and peer address, so its logging is disabled (`--no-stdout-log`, `--log-file=/dev/null`, Docker logging driver `none`). See the comment in `docker-compose.yml` to enable it temporarily for debugging.

---

## Self-Hosting with Docker

### Prerequisites
* A Linux VPS (1 GB RAM / 1 vCPU is sufficient) with Docker and Docker Compose installed.
* DNS `A` records for `yourdomain.com` and `turn.yourdomain.com` pointing to the server's public IPv4 address.

### 1. Clone & Configure Environment

```bash
git clone [https://github.com/YOUR_USERNAME/darkp2p.git](https://github.com/YOUR_USERNAME/darkp2p.git)
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
BTCPAY_WEBHOOK_SECRET=
EOF
chmod 600 .env
```

Replace `203.0.113.10` with the server's public IPv4; `docker compose` refuses to start without `TURN_PUBLIC_IP`. All configuration lives in `.env`: Compose passes it to the `darkp2p` container as-is (`env_file`), and `docker-compose.yml` itself holds no secrets. See `.env.example` for every variable.

### 2. Configure Firewall (Ubuntu/Debian)

Opens only SSH (rate-limited), Caddy (`80/tcp`, `443/tcp`, `443/udp`), and `coturn` (`3478/tcp+udp`, `49160-49200/udp`):

```bash
chmod +x setup-ufw.sh
sudo ./setup-ufw.sh
```

### 3. Generate Lockfile & Launch

```bash
docker run --rm -v "$PWD":/app -w /app node:22-alpine npm install --package-lock-only
docker compose up -d --build
```

Verify that Caddy obtained a TLS certificate and all four containers (`caddy`, `darkp2p`, `tor`, `coturn`) are running. `coturn` has no logs by design.

```bash
docker compose ps
docker compose logs -f caddy
```

### 3b. Enable the Onion Mirror (Optional)

The `tor` container publishes a v3 hidden service that forwards to the server's onion listener (`darkp2p:3001`). Once it has generated its address, add it to `.env` and recreate `darkp2p` so it sends `Onion-Location` and allows the onion origin:

```bash
docker compose exec tor cat /var/lib/tor/hidden_service/hostname
# set ONION_HOST=<that hostname> in .env, then:
docker compose up -d darkp2p
```

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

*(To automate token issuance with crypto payments, point a BTCPay Server `InvoiceSettled` webhook to `https://yourdomain.com/webhook/btcpay` and set `BTCPAY_WEBHOOK_SECRET` in `.env`.)*

After paying, the buyer's browser claims the token from `/api/claim` and keeps retrying (across reloads) until it arrives. The claim window is `CLAIM_TTL_HOURS` (default `48`); once it has passed, the claim returns `404`.

For restart-safe claims, also set `BTCPAY_URL`, `BTCPAY_API_KEY` (a Greenfield API key with invoice view permission) and `BTCPAY_STORE_ID`. The server then verifies every settled invoice against the BTCPay API and derives the token from the invoice deterministically, so an unclaimed token survives a restart. In this mode the window runs from invoice creation plus a 6-hour settlement grace, and holds across restarts. Without lookup, the window runs from when the webhook minted the token, and an unclaimed token is lost on restart.

---

## License

MIT
