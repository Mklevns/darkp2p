const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT) || 3000;
const DOMAIN = process.env.DOMAIN || 'darkp2p.com';
const TURN_HOST = process.env.TURN_HOST || '';
const TURN_SECRET = process.env.TURN_SECRET || '';
const TURN_TTL = Number(process.env.TURN_TTL) || 3600;
const PRO_TOKEN_SECRET = process.env.PRO_TOKEN_SECRET || '';
const PRO_TOKEN_DAYS = Number(process.env.PRO_TOKEN_DAYS) || 30;
const BTCPAY_WEBHOOK_SECRET = process.env.BTCPAY_WEBHOOK_SECRET || '';

const HEARTBEAT_MS = 30_000;
const CLAIM_TTL_MS = 15 * 60 * 1000;
const MAX_WEBHOOK_BYTES = 64 * 1024;
const ROOM_ID_RE = /^[a-f0-9]{16,64}$/i;
const ALLOWED_SIGNAL_TYPES = new Set(['offer', 'answer', 'candidate']);

// Ephemeral per-boot salt for privacy-preserving rate limiting
const BOOT_SALT = crypto.randomBytes(32);
const MAX_TOTAL_CONNS = 1000;
const MAX_CONNS_PER_IP = 6;
const CONN_WINDOW_MS = 60_000;
const MAX_CONNS_PER_WINDOW = 20;
const MSG_WINDOW_MS = 10_000;
const MAX_MSGS_PER_WINDOW = 40;

const indexHtml = fs.readFileSync(path.join(__dirname, 'public', 'index.html'));
const robotsTxt = `User-agent: *\nAllow: /\nSitemap: https://${DOMAIN}/sitemap.xml\n`;
const sitemapXml =
  '<?xml version="1.0" encoding="UTF-8"?>\n' +
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
  `  <url><loc>https://${DOMAIN}/</loc><priority>1.0</priority></url>\n` +
  '</urlset>\n';

const rooms = new Map();         // roomId -> Set<WebSocket>
const ipState = new Map();       // ipHash -> { active: number, timestamps: number[] }
const settledTokens = new Map(); // invoiceId -> { token, expiresAt }

const SECURITY_HEADERS = {
  'Content-Type': 'text/html; charset=utf-8',
  'Content-Security-Policy':
    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self' ws: wss:; base-uri 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
};

function mintProToken(days = PRO_TOKEN_DAYS) {
  const exp = String(Math.floor(Date.now() / 1000) + days * 86400);
  const sig = crypto
    .createHmac('sha256', PRO_TOKEN_SECRET)
    .update(exp)
    .digest('hex');
  return `${exp}.${sig}`;
}

function verifyProToken(token) {
  if (!PRO_TOKEN_SECRET || typeof token !== 'string') return false;
  const parts = token.trim().split('.');
  if (parts.length !== 2) return false;

  const [expStr, sigHex] = parts;
  const exp = Number(expStr);
  if (!Number.isInteger(exp) || exp <= Math.floor(Date.now() / 1000)) return false;

  const expectedHex = crypto
    .createHmac('sha256', PRO_TOKEN_SECRET)
    .update(expStr)
    .digest('hex');

  const sigBuf = Buffer.from(sigHex, 'hex');
  const expBuf = Buffer.from(expectedHex, 'hex');
  if (sigBuf.length !== expBuf.length) return false;

  return crypto.timingSafeEqual(sigBuf, expBuf);
}

function getIceServers(isPro) {
  const stunOnly = TURN_HOST
    ? [{ urls: `stun:${TURN_HOST}:3478` }]
    : [{ urls: 'stun:stun.l.google.com:19302' }];

  if (!isPro || !TURN_HOST || !TURN_SECRET) {
    return stunOnly;
  }

  const expiry = Math.floor(Date.now() / 1000) + TURN_TTL;
  const username = `${expiry}:${crypto.randomBytes(6).toString('hex')}`;
  const credential = crypto
    .createHmac('sha1', TURN_SECRET)
    .update(username)
    .digest('base64');

  return [
    ...stunOnly,
    {
      urls: [
        `turn:${TURN_HOST}:3478?transport=udp`,
        `turn:${TURN_HOST}:3478?transport=tcp`,
      ],
      username,
      credential,
    },
  ];
}

function verifyBtcPaySig(rawBody, sigHeader) {
  if (!BTCPAY_WEBHOOK_SECRET || typeof sigHeader !== 'string') return false;
  const expected =
    'sha256=' +
    crypto.createHmac('sha256', BTCPAY_WEBHOOK_SECRET).update(rawBody).digest('hex');

  const sigBuf = Buffer.from(sigHeader, 'utf8');
  const expBuf = Buffer.from(expected, 'utf8');
  if (sigBuf.length !== expBuf.length) return false;

  return crypto.timingSafeEqual(sigBuf, expBuf);
}

function readRawBody(req, limitBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > limitBytes) {
        req.destroy();
        reject(new Error('Payload too large'));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function getClientHash(req) {
  const xff = req.headers['x-forwarded-for'];
  const rawIp =
    typeof xff === 'string'
      ? xff.split(',')[0].trim()
      : req.socket.remoteAddress || 'unknown';
  return crypto.createHmac('sha256', BOOT_SALT).update(rawIp).digest('hex').slice(0, 16);
}

function allowConnection(ipHash) {
  if (wss.clients.size >= MAX_TOTAL_CONNS) return false;

  const now = Date.now();
  let state = ipState.get(ipHash);
  if (!state) {
    state = { active: 0, timestamps: [] };
    ipState.set(ipHash, state);
  }

  state.timestamps = state.timestamps.filter((t) => now - t < CONN_WINDOW_MS);
  if (state.active >= MAX_CONNS_PER_IP || state.timestamps.length >= MAX_CONNS_PER_WINDOW) {
    return false;
  }

  state.active += 1;
  state.timestamps.push(now);
  return true;
}

function releaseConnection(ipHash) {
  const state = ipState.get(ipHash);
  if (!state) return;
  state.active = Math.max(0, state.active - 1);
  if (state.active === 0 && state.timestamps.length === 0) {
    ipState.delete(ipHash);
  }
}

const server = http.createServer((req, res) => {
  const urlObj = new URL(req.url || '/', 'http://localhost');
  const pathname = urlObj.pathname;

  if (req.method === 'GET' && pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('ok');
  }

  if (req.method === 'GET' && pathname === '/robots.txt') {
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end(robotsTxt);
  }

  if (req.method === 'GET' && pathname === '/sitemap.xml') {
    res.writeHead(200, { 'Content-Type': 'application/xml; charset=utf-8' });
    return res.end(sitemapXml);
  }

  if (req.method === 'GET' && pathname === '/') {
    res.writeHead(200, SECURITY_HEADERS);
    return res.end(indexHtml);
  }

  if (req.method === 'POST' && pathname === '/webhook/btcpay') {
    if (!BTCPAY_WEBHOOK_SECRET || !PRO_TOKEN_SECRET) {
      res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('Webhook not configured');
    }

    readRawBody(req, MAX_WEBHOOK_BYTES)
      .then((rawBody) => {
        const sig = req.headers['btcpay-sig'];
        if (!verifyBtcPaySig(rawBody, sig)) {
          res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8' });
          return res.end('Invalid signature');
        }

        let event;
        try {
          event = JSON.parse(rawBody.toString('utf8'));
        } catch {
          res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
          return res.end('Invalid JSON');
        }

        if (event.type === 'InvoiceSettled' && typeof event.invoiceId === 'string') {
          const token = mintProToken(PRO_TOKEN_DAYS);
          settledTokens.set(event.invoiceId, {
            token,
            expiresAt: Date.now() + CLAIM_TTL_MS,
          });
        }

        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('ok');
      })
      .catch(() => {
        res.writeHead(413, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Payload Too Large');
      });
    return;
  }

  if (req.method === 'GET' && pathname === '/api/claim') {
    const invoiceId = urlObj.searchParams.get('invoiceId') || '';
    const entry = settledTokens.get(invoiceId);

    if (!entry || entry.expiresAt <= Date.now()) {
      settledTokens.delete(invoiceId);
      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ ready: false }));
    }

    settledTokens.delete(invoiceId);
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    return res.end(JSON.stringify({ ready: true, token: entry.token }));
  }

  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not Found');
});

const wss = new WebSocketServer({
  server,
  maxPayload: 64 * 1024,
});

function leaveRoom(ws) {
  const roomId = ws.currentRoom;
  if (!roomId || !rooms.has(roomId)) return;

  const peers = rooms.get(roomId);
  peers.delete(ws);
  ws.currentRoom = null;

  for (const peer of peers) {
    if (peer.readyState === 1) {
      peer.send(JSON.stringify({ type: 'peer-left' }));
    }
  }

  if (peers.size === 0) rooms.delete(roomId);
}

wss.on('connection', (ws, req) => {
  const ipHash = getClientHash(req);
  if (!allowConnection(ipHash)) {
    ws.close(1008, 'Rate limit exceeded');
    return;
  }

  ws.ipHash = ipHash;
  ws.isAlive = true;
  ws.currentRoom = null;
  ws.isPro = false;
  ws.msgCount = 0;
  ws.msgWindowStart = Date.now();
  ws.cleanedUp = false;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (raw) => {
    const now = Date.now();
    if (now - ws.msgWindowStart >= MSG_WINDOW_MS) {
      ws.msgWindowStart = now;
      ws.msgCount = 0;
    }
    ws.msgCount += 1;
    if (ws.msgCount > MAX_MSGS_PER_WINDOW) {
      ws.close(1008, 'Message rate limit exceeded');
      return;
    }

    let msg;
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }

    if (!msg || typeof msg !== 'object') return;

    if (msg.type === 'join') {
      if (ws.currentRoom) return;
      if (typeof msg.room !== 'string' || !ROOM_ID_RE.test(msg.room)) {
        ws.send(JSON.stringify({ type: 'error', reason: 'invalid-room' }));
        return;
      }

      const roomId = msg.room.toLowerCase();
      if (!rooms.has(roomId)) rooms.set(roomId, new Set());
      const peers = rooms.get(roomId);

      if (peers.size >= 2) {
        ws.send(JSON.stringify({ type: 'full' }));
        return;
      }

      ws.isPro = verifyProToken(msg.token);
      peers.add(ws);
      ws.currentRoom = roomId;

      if (peers.size === 2) {
        const roomIsPro = [...peers].some((p) => p.isPro);
        const iceServers = getIceServers(roomIsPro);
        for (const peer of peers) {
          if (peer.readyState === 1) {
            peer.send(
              JSON.stringify({
                type: 'ready',
                initiator: peer === ws,
                iceServers,
                relayEnabled: roomIsPro,
              })
            );
          }
        }
      }
      return;
    }

    if (!ALLOWED_SIGNAL_TYPES.has(msg.type)) return;

    if (ws.currentRoom && rooms.has(ws.currentRoom)) {
      const payload = JSON.stringify(msg);
      for (const peer of rooms.get(ws.currentRoom)) {
        if (peer !== ws && peer.readyState === 1) {
          peer.send(payload);
        }
      }
    }
  });

  const onDisconnect = () => {
    if (ws.cleanedUp) return;
    ws.cleanedUp = true;
    leaveRoom(ws);
    releaseConnection(ws.ipHash);
  };

  ws.on('close', onDisconnect);
  ws.on('error', onDisconnect);
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      if (!ws.cleanedUp) {
        ws.cleanedUp = true;
        leaveRoom(ws);
        releaseConnection(ws.ipHash);
      }
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_MS);

const claimSweep = setInterval(() => {
  const now = Date.now();
  for (const [id, entry] of settledTokens) {
    if (entry.expiresAt <= now) settledTokens.delete(id);
  }
}, 5 * 60 * 1000);

const rateSweep = setInterval(() => {
  const now = Date.now();
  for (const [hash, state] of ipState) {
    state.timestamps = state.timestamps.filter((t) => now - t < CONN_WINDOW_MS);
    if (state.active === 0 && state.timestamps.length === 0) {
      ipState.delete(hash);
    }
  }
}, 5 * 60 * 1000);

function shutdown() {
  clearInterval(heartbeat);
  clearInterval(claimSweep);
  clearInterval(rateSweep);
  wss.close(() => {
    server.close(() => process.exit(0));
  });
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

server.listen(PORT, () => {
  console.log(`[darkp2p] Signaling node listening on :${PORT}`);
});
