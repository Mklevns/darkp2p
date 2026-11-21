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
* **Back-Pressure & Chunking:** Files are sliced into `16,000`-byte chunks (leaving headroom for the 33-byte encryption envelope under the 16 KB cross-browser SCTP limit) with `bufferedAmount` flow control capped at 1 MB.
* **Privacy-Preserving Rate Limiting:** Connection and message limits use a per-boot random salt to HMAC-hash client IPs in RAM—raw IP addresses are never stored or logged.
* **Isolated Onion Listener:** Tor hidden-service traffic reaches the server on a dedicated port (`ONION_PORT`, `3001` in Docker Compose). Onion clients are identified by that listener alone, never by the `Host` or `X-Forwarded-For` headers, so they cannot pose as clearnet clients to dodge rate limits.

---

## Wire Format (AES-256-GCM Framing)

Every control message (`meta`, `done`) and binary file chunk is encrypted independently using Web Crypto (`AES-GCM`) with a fresh 12-byte random IV:

```text
+---------------+------------------+------------+-----------------------------------+
| 1B frameType  | 4B uint32 seq    | 12B IV     | AES-256-GCM ciphertext + 16B tag  |
+---------------+------------------+------------+-----------------------------------+
|<--- 5B AAD (authenticated) ----->|
```

* `frameType`: `1` = JSON control frame (`meta` / `done`), `2` = binary file chunk.
* `seq`: Big-endian 32-bit sequence counter (`0` for `meta`, `1..N` for data chunks, `N+1` for `done`).
* Binding `[frameType || seq]` as Additional Authenticated Data (AAD) prevents chunk reordering, injection, or truncation. The receiver verifies both total byte count and chunk count before saving.

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

---

## License

MIT
