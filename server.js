'use strict';

/*
 * DARKP2P signaling node
 *
 *  - Pairs two peers per room in RAM and forwards WebRTC signaling.
 *  - Relays end-to-end encrypted binary frames over the same WebSocket when
 *    WebRTC is unavailable (Tor Browser), with byte caps, a byte-rate limit
 *    and backpressure. The server never holds the AES key.
 *  - Mints stateless HMAC-signed Pro tokens from BTCPay webhooks.
 *
 * Nothing is written to disk and raw IP addresses are never stored or logged.
 */

const http = require('http');
const fs = require('fs');
const net = require('net');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

// ---------------------------------------------------------------------------
// Logging (never include IPs, room IDs, invoice IDs or tokens)
// ---------------------------------------------------------------------------

function logInfo(msg) {
  console.log(`[darkp2p] ${msg}`);
}

function logWarn(msg) {
  console.warn(`[darkp2p] WARN ${msg}`);
}

function logError(msg, err) {
  console.error(`[darkp2p] ERROR ${msg}${err ? `: ${err.stack || err}` : ''}`);
}

function fatal(msg) {
  logError(`${msg} — refusing to start`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

function envStr(name) {
  return (process.env[name] || '').trim();
}

function envInt(name, fallback, min, max) {
  const raw = envStr(name);
  if (raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) {
    logWarn(`${name} must be an integer in [${min}, ${max}]; using ${fallback}`);
    return fallback;
  }
  return n;
}

const MB = 1024 * 1024;
const HOSTNAME_RE =
  /^(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$/;
const ONION_V3_RE = /^[a-z2-7]{56}\.onion$/;

const PORT = envInt('PORT', 3000, 1, 65535);
const DOMAIN = (envStr('DOMAIN') || 'darkp2p.com').toLowerCase();
const ONION_HOST = envStr('ONION_HOST').toLowerCase();
// Dedicated listener for Tor hidden-service traffic. Anything arriving on it
// is onion traffic, whatever its Host header says. 0 disables it.
const ONION_PORT = envInt('ONION_PORT', 0, 0, 65535);
const TURN_HOST = envStr('TURN_HOST').toLowerCase();
const TURN_SECRET = envStr('TURN_SECRET');
const TURN_TTL = envInt('TURN_TTL', 3600, 60, 86400);
const STUN_URLS = envStr('STUN_URLS'); // optional, comma-separated stun: URLs
const PRO_TOKEN_SECRET = envStr('PRO_TOKEN_SECRET');
const PRO_TOKEN_DAYS = envInt('PRO_TOKEN_DAYS', 30, 1, 3650);
const BTCPAY_WEBHOOK_SECRET = envStr('BTCPAY_WEBHOOK_SECRET');
// Optional. Used to bind webhooks to one store, and (with URL + API key) to
// look up the purchased item when the webhook payload carries no metadata.
const BTCPAY_STORE_ID = envStr('BTCPAY_STORE_ID');
const BTCPAY_URL = envStr('BTCPAY_URL').replace(/\/+$/, '');
const BTCPAY_API_KEY = envStr('BTCPAY_API_KEY');
// auto  = trust X-Forwarded-For only when the direct peer is loopback/private
//         (i.e. a local reverse proxy such as the bundled Caddy container)
// 1 / 0 = always / never trust it
const TRUST_PROXY = envStr('TRUST_PROXY').toLowerCase() || 'auto';

const MAX_TOTAL_CONNS = envInt('MAX_TOTAL_CONNS', 1000, 2, 100000);
const MAX_CONNS_PER_IP = envInt('MAX_CONNS_PER_IP', 6, 1, 10000);
const MAX_ONION_CONNS = envInt('MAX_ONION_CONNS', 100, 2, 10000);
const CONN_WINDOW_MS = 60_000;
const MAX_CONNS_PER_WINDOW = 20;
const MAX_ONION_CONNS_PER_WINDOW = 120;
const MSG_WINDOW_MS = 10_000;
const MAX_MSGS_PER_WINDOW = 40; // JSON control messages only
const CLAIM_WINDOW_MS = 60_000;
const MAX_CLAIMS_PER_WINDOW = 30;
const MAX_ONION_CLAIMS_PER_WINDOW = 120;

const HEARTBEAT_MS = 30_000;
const SWEEP_MS = 5 * 60 * 1000;
const SHUTDOWN_GRACE_MS = 5_000;
const CLAIM_TTL_MS = 15 * 60 * 1000;
const INVOICE_DEDUPE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_WEBHOOK_BYTES = 64 * 1024;
const MAX_WS_PAYLOAD = 64 * 1024;
const BTCPAY_API_TIMEOUT_MS = 5_000;

// WebSocket relay. The cap is per room, counts both directions, and lasts
// until the room empties. A room is Pro if either peer holds a valid token.
const RELAY_CAP_FREE = envInt('RELAY_CAP_FREE_MB', 50, 0, 1024 * 1024) * MB;
const RELAY_CAP_PRO = envInt('RELAY_CAP_PRO_MB', 500, 0, 1024 * 1024) * MB;
const RELAY_RATE_FREE = 1 * MB; // bytes per second, per sender
const RELAY_RATE_PRO = 4 * MB;
const RELAY_HIGH_WATER = 1 * MB; // pause the sender above this much queued for the receiver
const RELAY_LOW_WATER = 256 * 1024;
const MIN_THROTTLE_MS = 25;

const ROOM_ID_RE = /^[a-f0-9]{16,64}$/i;
const INVOICE_ID_RE = /^[A-Za-z0-9_-]{8,64}$/;

// Ephemeral per-boot salt: client addresses and invoice IDs are only ever
// held as salted HMACs, and the salt dies with the process.
const BOOT_SALT = crypto.randomBytes(32);

function parseTiers(raw) {
  const tiers = new Map();
  for (const pair of raw.split(',')) {
    const [codeRaw, daysStr] = pair.split(':').map((s) => s.trim());
    const code = (codeRaw || '').toLowerCase();
    const days = Number(daysStr);
    if (!code || !Number.isInteger(days) || days < 1 || days > 3650) {
      fatal(`PRO_TIERS entry "${pair}" is invalid (expected itemCode:days)`);
    }
    tiers.set(code, days);
  }
  return tiers;
}

// BTCPay PoS item code -> token lifetime in days.
const PRO_TIERS = envStr('PRO_TIERS')
  ? parseTiers(envStr('PRO_TIERS'))
  : new Map([
      ['pro-1d', 1],
      ['pro-30d', PRO_TOKEN_DAYS],
    ]);

function parseOrigins(raw) {
  const origins = new Set();
  for (const item of raw.split(',')) {
    const value = item.trim();
    if (!value) continue;
    let url;
    try {
      url = new URL(value);
    } catch {
      fatal(`ALLOWED_ORIGINS entry "${value}" is not a valid origin`);
    }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
      fatal(`ALLOWED_ORIGINS entry "${value}" must be http(s)`);
    }
    origins.add(url.origin.toLowerCase());
  }
  return origins;
}

function validateConfig() {
  if (!HOSTNAME_RE.test(DOMAIN)) fatal('DOMAIN is not a valid hostname');
  if (ONION_HOST && !ONION_V3_RE.test(ONION_HOST)) {
    fatal('ONION_HOST is not a valid v3 .onion hostname');
  }
  if (ONION_HOST && ONION_PORT === 0) {
    fatal('ONION_HOST is set but ONION_PORT is not: onion traffic would share the clearnet listener');
  }
  if (ONION_PORT === PORT) fatal('ONION_PORT must differ from PORT');
  if (ONION_PORT > 0 && !ONION_HOST) {
    logWarn('ONION_PORT is set without ONION_HOST: onion traffic is isolated, but Onion-Location and the onion CSP origin are disabled');
  }
  if (TURN_HOST && !HOSTNAME_RE.test(TURN_HOST)) fatal('TURN_HOST is not a valid hostname');
  if (!['auto', '1', 'true', '0', 'false'].includes(TRUST_PROXY)) {
    fatal('TRUST_PROXY must be auto, 1 or 0');
  }
  if (BTCPAY_WEBHOOK_SECRET && !PRO_TOKEN_SECRET) {
    fatal('BTCPAY_WEBHOOK_SECRET is set but PRO_TOKEN_SECRET is not');
  }
  if (BTCPAY_URL) {
    let url;
    try {
      url = new URL(BTCPAY_URL);
    } catch {
      fatal('BTCPAY_URL is not a valid URL');
    }
    if (url.protocol !== 'https:') fatal('BTCPAY_URL must be https');
  }
  for (const url of STUN_URLS.split(',').map((s) => s.trim()).filter(Boolean)) {
    if (!/^stuns?:[a-z0-9.\-[\]:]+$/i.test(url)) fatal(`STUN_URLS entry "${url}" is invalid`);
  }

  if (TURN_HOST && !TURN_SECRET) logWarn('TURN_HOST is set without TURN_SECRET: Pro rooms get STUN only');
  if (TURN_SECRET && !TURN_HOST) logWarn('TURN_SECRET is set without TURN_HOST: TURN is disabled');
  if (!PRO_TOKEN_SECRET) logWarn('PRO_TOKEN_SECRET is not set: Pro tokens are disabled');
  if (!BTCPAY_WEBHOOK_SECRET) logInfo('BTCPAY_WEBHOOK_SECRET is not set: payment webhook is disabled');
  for (const [name, value] of [
    ['TURN_SECRET', TURN_SECRET],
    ['PRO_TOKEN_SECRET', PRO_TOKEN_SECRET],
    ['BTCPAY_WEBHOOK_SECRET', BTCPAY_WEBHOOK_SECRET],
  ]) {
    if (value && value.length < 32) logWarn(`${name} is shorter than 32 characters`);
  }
  if ((BTCPAY_URL || BTCPAY_API_KEY) && !(BTCPAY_URL && BTCPAY_API_KEY && BTCPAY_STORE_ID)) {
    logWarn('BTCPay invoice lookup needs BTCPAY_URL, BTCPAY_API_KEY and BTCPAY_STORE_ID: lookup disabled');
  }
  if (!STUN_URLS && !TURN_HOST) {
    logWarn('No STUN_URLS or TURN_HOST: falling back to stun.l.google.com, which sees client IPs');
  }
}

validateConfig();

const ALLOWED_ORIGINS = parseOrigins(envStr('ALLOWED_ORIGINS') || `https://${DOMAIN}`);
if (ONION_HOST) ALLOWED_ORIGINS.add(`http://${ONION_HOST}`);

const BTCPAY_LOOKUP_ENABLED = Boolean(BTCPAY_URL && BTCPAY_API_KEY && BTCPAY_STORE_ID);
const TURN_ENABLED = Boolean(TURN_HOST && TURN_SECRET);
const STUN_SERVERS = STUN_URLS
  ? [{ urls: STUN_URLS.split(',').map((s) => s.trim()).filter(Boolean) }]
  : TURN_HOST
    ? [{ urls: `stun:${TURN_HOST}:3478` }]
    : [{ urls: 'stun:stun.l.google.com:19302' }];

// ---------------------------------------------------------------------------
// Static content and response headers
// ---------------------------------------------------------------------------

// Hash every executable inline <script> so the page can run without
// 'unsafe-inline'. JSON-LD and other data blocks are not executed and need no hash.
function inlineScriptHashes(html) {
  const hashes = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  let match;
  while ((match = re.exec(html)) !== null) {
    const attrs = match[1];
    if (/\bsrc\s*=/i.test(attrs)) continue;
    const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs);
    if (type && !/^(module|text\/javascript|application\/javascript)$/i.test(type[1])) continue;
    // The HTML parser normalizes newlines before the browser hashes the script.
    const source = match[2].replace(/\r\n?/g, '\n');
    hashes.push(`'sha256-${crypto.createHash('sha256').update(source, 'utf8').digest('base64')}'`);
  }
  return hashes;
}

function buildPageCsp(html) {
  const hashes = inlineScriptHashes(html);
  const connect = new Set(["'self'"]);
  for (const origin of ALLOWED_ORIGINS) connect.add(origin.replace(/^http/, 'ws'));
  return [
    "default-src 'none'",
    `script-src ${hashes.length ? hashes.join(' ') : "'none'"}`,
    "style-src 'unsafe-inline'",
    `connect-src ${[...connect].join(' ')}`,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join('; ');
}

function loadPage(file, required) {
  try {
    const body = fs.readFileSync(path.join(__dirname, 'public', file));
    return { body, csp: buildPageCsp(body.toString('utf8')) };
  } catch (err) {
    if (required) fatal(`Cannot read public/${file} (${err.code || err.message})`);
    return null;
  }
}

const indexPage = loadPage('index.html', true);
const notFoundPage = loadPage('404.html', false);

const robotsTxt = `User-agent: *\nAllow: /\nSitemap: https://${DOMAIN}/sitemap.xml\n`;
const sitemapXml =
  '<?xml version="1.0" encoding="UTF-8"?>\n' +
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
  `  <url><loc>https://${DOMAIN}/</loc><priority>1.0</priority></url>\n` +
  '</urlset>\n';

const BASE_HEADERS = {
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
};

const TYPE_TEXT = 'text/plain; charset=utf-8';
const TYPE_HTML = 'text/html; charset=utf-8';
const TYPE_JSON = 'application/json; charset=utf-8';
const TYPE_XML = 'application/xml; charset=utf-8';

function respond(req, res, status, contentType, body, extraHeaders) {
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8');
  res.writeHead(status, {
    ...BASE_HEADERS,
    'Content-Type': contentType,
    'Content-Length': buf.length,
    ...extraHeaders,
  });
  if (req.method === 'HEAD') res.end();
  else res.end(buf);
}

function respondPage(req, res, status, page, extraHeaders) {
  respond(req, res, status, TYPE_HTML, page.body, {
    'Content-Security-Policy': page.csp,
    'Cache-Control': 'no-cache',
    ...extraHeaders,
  });
}

function respondJson(req, res, status, obj, extraHeaders) {
  respond(req, res, status, TYPE_JSON, JSON.stringify(obj), {
    'Cache-Control': 'no-store',
    ...extraHeaders,
  });
}

function respondNotFound(req, res) {
  if (notFoundPage) respondPage(req, res, 404, notFoundPage);
  else respond(req, res, 404, TYPE_TEXT, 'Not Found');
}

// ---------------------------------------------------------------------------
// Client identity and rate limiting
// ---------------------------------------------------------------------------

const PRIVATE_NETS = new net.BlockList();
PRIVATE_NETS.addSubnet('127.0.0.0', 8, 'ipv4');
PRIVATE_NETS.addSubnet('10.0.0.0', 8, 'ipv4');
PRIVATE_NETS.addSubnet('172.16.0.0', 12, 'ipv4');
PRIVATE_NETS.addSubnet('192.168.0.0', 16, 'ipv4');
PRIVATE_NETS.addSubnet('169.254.0.0', 16, 'ipv4');
PRIVATE_NETS.addAddress('::1', 'ipv6');
PRIVATE_NETS.addSubnet('fc00::', 7, 'ipv6');
PRIVATE_NETS.addSubnet('fe80::', 10, 'ipv6');

function normalizeIp(raw) {
  if (typeof raw !== 'string') return null;
  let ip = raw.trim();
  const bracketed = /^\[([^\]]+)\](?::\d+)?$/.exec(ip);
  if (bracketed) ip = bracketed[1];
  const zone = ip.indexOf('%');
  if (zone !== -1) ip = ip.slice(0, zone);
  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(ip);
  if (mapped) ip = mapped[1];
  const family = net.isIP(ip);
  if (family === 0) return null;
  return { ip, family: family === 4 ? 'ipv4' : 'ipv6' };
}

// One IPv6 host normally controls a whole /64, so limits apply to the prefix.
function ipv6Prefix64(ip) {
  const [head, tail] = ip.split('::');
  const headGroups = head ? head.split(':') : [];
  let groups = headGroups;
  if (tail !== undefined) {
    const tailGroups = tail ? tail.split(':') : [];
    const hasV4 = tailGroups.length > 0 && tailGroups[tailGroups.length - 1].includes('.');
    const missing = Math.max(0, 8 - headGroups.length - tailGroups.length - (hasV4 ? 1 : 0));
    groups = [...headGroups, ...new Array(missing).fill('0'), ...tailGroups];
  }
  return groups
    .slice(0, 4)
    .map((g) => (parseInt(g, 16) || 0).toString(16))
    .join(':');
}

function shouldTrustProxy(remote) {
  if (TRUST_PROXY === '1' || TRUST_PROXY === 'true') return true;
  if (TRUST_PROXY === '0' || TRUST_PROXY === 'false') return false;
  return remote !== null && PRIVATE_NETS.check(remote.ip, remote.family);
}

// Onion traffic is identified by the listener it arrived on, never by the
// client-controlled Host header.
function isOnionSocket(req) {
  return ONION_PORT > 0 && req.socket.localPort === ONION_PORT;
}

// Returns a salted hash identifying the client's address (or /64), plus whether
// the request arrived via the Tor hidden service. The raw address never leaves
// this function, and X-Forwarded-For is never read on the onion listener because
// Tor forwards raw TCP without appending a trusted proxy hop.
function getClientIdentity(req) {
  if (isOnionSocket(req)) {
    return { key: 'onion', isOnion: true };
  }

  const remote = normalizeIp(req.socket.remoteAddress);
  let addr = remote;

  if (shouldTrustProxy(remote)) {
    const xff = req.headers['x-forwarded-for'];
    if (typeof xff === 'string' && xff) {
      // The rightmost entry is the one our own proxy appended; anything to
      // its left is client-supplied and cannot be trusted.
      const hops = xff.split(',');
      const forwarded = normalizeIp(hops[hops.length - 1]);
      if (forwarded) addr = forwarded;
    }
  }

  const bucket = !addr ? 'unknown' : addr.family === 'ipv4' ? addr.ip : ipv6Prefix64(addr.ip);
  const key = crypto.createHmac('sha256', BOOT_SALT).update(bucket).digest('hex').slice(0, 16);
  return { key, isOnion: false };
}

const ipState = new Map(); // clientKey -> { active, timestamps[] }
const claimHits = new Map(); // clientKey -> { count, resetAt }
let totalConns = 0;

function allowConnection({ key, isOnion }) {
  if (totalConns >= MAX_TOTAL_CONNS) return false;

  const maxActive = isOnion ? MAX_ONION_CONNS : MAX_CONNS_PER_IP;
  const maxWindow = isOnion ? MAX_ONION_CONNS_PER_WINDOW : MAX_CONNS_PER_WINDOW;

  const now = Date.now();
  let state = ipState.get(key);
  if (!state) {
    state = { active: 0, timestamps: [] };
    ipState.set(key, state);
  }

  state.timestamps = state.timestamps.filter((t) => now - t < CONN_WINDOW_MS);
  if (state.active >= maxActive || state.timestamps.length >= maxWindow) {
    return false;
  }

  state.active += 1;
  state.timestamps.push(now);
  totalConns += 1;
  return true;
}

function releaseConnection(clientKey) {
  totalConns = Math.max(0, totalConns - 1);
  const state = ipState.get(clientKey);
  if (!state) return;
  state.active = Math.max(0, state.active - 1);
}

function allowClaim({ key, isOnion }) {
  const maxClaims = isOnion ? MAX_ONION_CLAIMS_PER_WINDOW : MAX_CLAIMS_PER_WINDOW;
  const now = Date.now();
  const entry = claimHits.get(key);
  if (!entry || entry.resetAt <= now) {
    claimHits.set(key, { count: 1, resetAt: now + CLAIM_WINDOW_MS });
    return true;
  }
  if (entry.count >= maxClaims) return false;
  entry.count += 1;
  return true;
}

// ---------------------------------------------------------------------------
// Pro tokens, TURN credentials, BTCPay
// ---------------------------------------------------------------------------

function mintProToken(days) {
  const exp = String(Math.floor(Date.now() / 1000) + days * 86400);
  const sig = crypto.createHmac('sha256', PRO_TOKEN_SECRET).update(exp).digest('hex');
  return `${exp}.${sig}`;
}

function verifyProToken(token) {
  if (!PRO_TOKEN_SECRET || typeof token !== 'string' || token.length > 128) return false;
  const parts = token.trim().split('.');
  if (parts.length !== 2) return false;

  const [expStr, sigHex] = parts;
  if (!/^\d{1,12}$/.test(expStr) || !/^[a-f0-9]{64}$/i.test(sigHex)) return false;
  if (Number(expStr) <= Math.floor(Date.now() / 1000)) return false;

  const expected = crypto.createHmac('sha256', PRO_TOKEN_SECRET).update(expStr).digest();
  return crypto.timingSafeEqual(Buffer.from(sigHex, 'hex'), expected);
}

function getIceServers(withTurn) {
  if (!withTurn) return STUN_SERVERS;

  const expiry = Math.floor(Date.now() / 1000) + TURN_TTL;
  const username = `${expiry}:${crypto.randomBytes(6).toString('hex')}`;
  const credential = crypto.createHmac('sha1', TURN_SECRET).update(username).digest('base64');

  return [
    ...STUN_SERVERS,
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
    'sha256=' + crypto.createHmac('sha256', BTCPAY_WEBHOOK_SECRET).update(rawBody).digest('hex');

  const sigBuf = Buffer.from(sigHeader.trim().toLowerCase(), 'utf8');
  const expBuf = Buffer.from(expected, 'utf8');
  if (sigBuf.length !== expBuf.length) return false;
  return crypto.timingSafeEqual(sigBuf, expBuf);
}

function readRawBody(req, limitBytes) {
  return new Promise((resolve, reject) => {
    const tooLarge = () => Object.assign(new Error('Payload too large'), { code: 'E_TOO_LARGE' });
    if (Number(req.headers['content-length']) > limitBytes) {
      reject(tooLarge());
      return;
    }

    const chunks = [];
    let total = 0;
    let done = false;
    req.on('data', (chunk) => {
      if (done) return;
      total += chunk.length;
      if (total > limitBytes) {
        done = true;
        reject(tooLarge());
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (done) return;
      done = true;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', (err) => {
      if (done) return;
      done = true;
      reject(err);
    });
  });
}

// invoiceKey -> { token: string|null, claimUntil, forgetAt }
// The token can be read until claimUntil. The record itself is kept until
// forgetAt so a webhook redelivery cannot mint a second token.
const invoices = new Map();

function invoiceKey(invoiceId) {
  return crypto.createHmac('sha256', BOOT_SALT).update(`invoice:${invoiceId}`).digest('hex');
}

async function fetchInvoiceItemCode(invoiceId) {
  const url =
    `${BTCPAY_URL}/api/v1/stores/${encodeURIComponent(BTCPAY_STORE_ID)}` +
    `/invoices/${encodeURIComponent(invoiceId)}`;
  const res = await fetch(url, {
    headers: { Authorization: `token ${BTCPAY_API_KEY}`, Accept: 'application/json' },
    redirect: 'error',
    signal: AbortSignal.timeout(BTCPAY_API_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`BTCPay API responded ${res.status}`);
  const invoice = await res.json();
  const code = invoice && invoice.metadata && invoice.metadata.itemCode;
  return typeof code === 'string' ? code.trim().toLowerCase() : null;
}

async function handleBtcPayWebhook(req, res) {
  if (!BTCPAY_WEBHOOK_SECRET || !PRO_TOKEN_SECRET) {
    return respond(req, res, 503, TYPE_TEXT, 'Webhook not configured');
  }

  let rawBody;
  try {
    rawBody = await readRawBody(req, MAX_WEBHOOK_BYTES);
  } catch (err) {
    const status = err.code === 'E_TOO_LARGE' ? 413 : 400;
    return respond(req, res, status, TYPE_TEXT, http.STATUS_CODES[status], { Connection: 'close' });
  }

  if (!verifyBtcPaySig(rawBody, req.headers['btcpay-sig'])) {
    return respond(req, res, 401, TYPE_TEXT, 'Invalid signature');
  }

  let event;
  try {
    event = JSON.parse(rawBody.toString('utf8'));
  } catch {
    return respond(req, res, 400, TYPE_TEXT, 'Invalid JSON');
  }
  if (!event || typeof event !== 'object') {
    return respond(req, res, 400, TYPE_TEXT, 'Invalid event');
  }

  // Anything we do not act on is acknowledged so BTCPay stops retrying it.
  if (event.type !== 'InvoiceSettled') return respond(req, res, 200, TYPE_TEXT, 'ok');
  if (typeof event.invoiceId !== 'string' || !INVOICE_ID_RE.test(event.invoiceId)) {
    return respond(req, res, 400, TYPE_TEXT, 'Invalid invoiceId');
  }
  if (BTCPAY_STORE_ID && event.storeId !== BTCPAY_STORE_ID) {
    logWarn('Webhook for a different store ignored');
    return respond(req, res, 200, TYPE_TEXT, 'ignored');
  }

  // Deduplicate on the invoice itself rather than on isRedelivery: a
  // redelivery of an event we never processed (we were down) must still mint.
  const key = invoiceKey(event.invoiceId);
  if (invoices.has(key)) return respond(req, res, 200, TYPE_TEXT, 'ok');

  const rawCode = event.metadata?.itemCode ?? event.data?.metadata?.itemCode;
  let itemCode = typeof rawCode === 'string' && rawCode.trim() ? rawCode.trim().toLowerCase() : null;
  if (itemCode === null && BTCPAY_LOOKUP_ENABLED) {
    try {
      itemCode = await fetchInvoiceItemCode(event.invoiceId);
    } catch (err) {
      logError('BTCPay invoice lookup failed', err);
      // Non-2xx makes BTCPay retry the delivery later.
      return respond(req, res, 502, TYPE_TEXT, 'Invoice lookup failed');
    }
  }

  const days = itemCode === null ? undefined : PRO_TIERS.get(itemCode);
  if (!days) {
    logWarn(
      itemCode === null
        ? 'Settled invoice carries no itemCode: no token minted'
        : 'Settled invoice has an unknown itemCode: no token minted'
    );
    return respond(req, res, 200, TYPE_TEXT, 'ignored');
  }

  if (!invoices.has(key)) {
    const now = Date.now();
    invoices.set(key, {
      token: mintProToken(days),
      claimUntil: now + CLAIM_TTL_MS,
      forgetAt: now + INVOICE_DEDUPE_MS,
    });
    logInfo(`Pro token minted (${days}d)`);
  }
  return respond(req, res, 200, TYPE_TEXT, 'ok');
}

function handleClaim(req, res, url) {
  if (!allowClaim(getClientIdentity(req))) {
    return respondJson(req, res, 429, { ready: false }, { 'Retry-After': '60' });
  }

  const invoiceId = url.searchParams.get('invoiceId') || '';
  const entry = INVOICE_ID_RE.test(invoiceId) ? invoices.get(invoiceKey(invoiceId)) : undefined;

  // The token stays readable for the whole claim window, so a response lost
  // in transit does not cost the buyer their token.
  if (!entry || !entry.token || entry.claimUntil <= Date.now()) {
    return respondJson(req, res, 404, { ready: false });
  }
  return respondJson(req, res, 200, { ready: true, token: entry.token });
}

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------

async function handleRequest(req, res) {
  let url;
  try {
    url = new URL(req.url || '/', 'http://localhost');
  } catch {
    return respond(req, res, 400, TYPE_TEXT, 'Bad Request', { Connection: 'close' });
  }

  const { pathname } = url;
  const isRead = req.method === 'GET' || req.method === 'HEAD';

  if (isRead && pathname === '/') {
    const extra = ONION_HOST && !isOnionSocket(req)
      ? { 'Onion-Location': `http://${ONION_HOST}/` }
      : undefined;
    return respondPage(req, res, 200, indexPage, extra);
  }
  if (isRead && pathname === '/health') {
    return respond(req, res, 200, TYPE_TEXT, 'ok', { 'Cache-Control': 'no-store' });
  }
  if (isRead && pathname === '/robots.txt') return respond(req, res, 200, TYPE_TEXT, robotsTxt);
  if (isRead && pathname === '/sitemap.xml') return respond(req, res, 200, TYPE_XML, sitemapXml);
  if (isRead && pathname === '/api/claim') return handleClaim(req, res, url);
  if (req.method === 'POST' && pathname === '/webhook/btcpay') return handleBtcPayWebhook(req, res);

  return respondNotFound(req, res);
}

function onRequest(req, res) {
  handleRequest(req, res).catch((err) => {
    logError('Request handler failed', err);
    if (res.headersSent) res.destroy();
    else respond(req, res, 500, TYPE_TEXT, 'Internal Server Error', { Connection: 'close' });
  });
}

function createListener() {
  const srv = http.createServer(onRequest);
  srv.headersTimeout = 15_000;
  srv.requestTimeout = 30_000;
  srv.keepAliveTimeout = 5_000;
  return srv;
}

const server = createListener();
const onionServer = ONION_PORT > 0 ? createListener() : null;
const listeners = onionServer ? [server, onionServer] : [server];

// ---------------------------------------------------------------------------
// WebSocket signaling and relay
// ---------------------------------------------------------------------------

const rooms = new Map(); // roomId -> { peers: Set<WebSocket>, isPro, relayActive, relayBytes }

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_WS_PAYLOAD });

function isOriginAllowed(req) {
  const origin = req.headers.origin;
  if (typeof origin !== 'string') return false;
  const normalized = origin.toLowerCase();
  if (ALLOWED_ORIGINS.has(normalized)) return true;
  // Same-origin page (onion mirror, localhost during development): a browser
  // cannot forge Origin or Host, so a cross-site page never matches.
  const host = req.headers.host;
  if (typeof host !== 'string' || !host) return false;
  try {
    return new URL(normalized).host === host.toLowerCase();
  } catch {
    return false;
  }
}

function rejectUpgrade(socket, status) {
  const reason = http.STATUS_CODES[status];
  socket.once('finish', () => socket.destroy());
  socket.end(
    `HTTP/1.1 ${status} ${reason}\r\n` +
      'Connection: close\r\n' +
      'Content-Type: text/plain\r\n' +
      `Content-Length: ${Buffer.byteLength(reason)}\r\n\r\n${reason}`
  );
}

function onUpgrade(req, socket, head) {
  // Present from the first moment so a reset during rejection cannot crash us.
  socket.on('error', () => {});

  let pathname;
  try {
    pathname = new URL(req.url || '/', 'http://localhost').pathname;
  } catch {
    return rejectUpgrade(socket, 400);
  }
  if (pathname !== '/') return rejectUpgrade(socket, 404);
  if (!isOriginAllowed(req)) return rejectUpgrade(socket, 403);

  const client = getClientIdentity(req);
  if (!allowConnection(client)) return rejectUpgrade(socket, 429);

  // Tied to the TCP socket, so the slot is returned exactly once however the
  // connection ends (failed handshake, clean close, terminate, reset).
  socket.once('close', () => releaseConnection(client.key));

  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
}

for (const srv of listeners) srv.on('upgrade', onUpgrade);

function sendJson(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function otherPeer(room, ws) {
  for (const peer of room.peers) {
    if (peer !== ws) return peer;
  }
  return null;
}

// A sender is paused while it is over its byte rate or while the receiver's
// outbound queue is full. Pausing stops reading its TCP socket, which pushes
// the backpressure all the way to the sending browser.
function updatePause(ws) {
  if (ws.readyState !== WebSocket.OPEN) return;
  const shouldPause = ws.throttleTimer !== null || ws.backpressured;
  if (shouldPause && !ws.isPaused) ws.pause();
  else if (!shouldPause && ws.isPaused) ws.resume();
}

function chargeRelayBudget(ws, bytes, rate) {
  const now = Date.now();
  if (ws.bucketAt === 0) ws.bucket = rate; // first relayed frame: start with a full bucket
  else ws.bucket = Math.min(rate, ws.bucket + ((now - ws.bucketAt) / 1000) * rate);
  ws.bucketAt = now;
  ws.bucket -= bytes;

  if (ws.bucket < 0 && ws.throttleTimer === null) {
    const waitMs = Math.max(MIN_THROTTLE_MS, Math.ceil((-ws.bucket / rate) * 1000));
    ws.throttleTimer = setTimeout(() => {
      ws.throttleTimer = null;
      updatePause(ws);
    }, waitMs);
    updatePause(ws);
  }
}

function countControlMessage(ws) {
  const now = Date.now();
  if (now - ws.msgWindowStart >= MSG_WINDOW_MS) {
    ws.msgWindowStart = now;
    ws.msgCount = 0;
  }
  ws.msgCount += 1;
  if (ws.msgCount > MAX_MSGS_PER_WINDOW) {
    ws.close(1008, 'Message rate limit exceeded');
    return false;
  }
  return true;
}

function handleRelayFrame(ws, data) {
  const room = ws.room;
  if (!room || room.peers.size !== 2 || !room.relayActive) {
    // Not relayable: treat it as a control message so junk cannot be flooded.
    countControlMessage(ws);
    return;
  }

  const peer = otherPeer(room, ws);
  if (!peer || peer.readyState !== WebSocket.OPEN) return;

  const cap = room.isPro ? RELAY_CAP_PRO : RELAY_CAP_FREE;
  if (room.relayBytes + data.length > cap) {
    const notice = { type: 'error', reason: 'relay-limit-exceeded' };
    sendJson(peer, notice);
    sendJson(ws, notice);
    // The sender cannot be told to stop mid-file, so stop reading from it.
    ws.close(1008, 'relay-limit-exceeded');
    return;
  }
  room.relayBytes += data.length;

  peer.send(data, { binary: true }, (err) => {
    if (err) return;
    // Bytes reaching the receiver prove it is alive even when its pongs are
    // stuck behind queued data on a slow link.
    peer.isAlive = true;
    if (ws.backpressured && peer.bufferedAmount <= RELAY_LOW_WATER) {
      ws.backpressured = false;
      updatePause(ws);
    }
  });

  if (!ws.backpressured && peer.bufferedAmount > RELAY_HIGH_WATER) {
    ws.backpressured = true;
    updatePause(ws);
  }
  chargeRelayBudget(ws, data.length, room.isPro ? RELAY_RATE_PRO : RELAY_RATE_FREE);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function optional(value, type) {
  return value === null || value === undefined || typeof value === type;
}

// Rebuild signaling messages from known fields only; anything else a peer
// attaches is dropped rather than forwarded.
function sanitizeSignal(msg) {
  if (msg.type === 'offer' || msg.type === 'answer') {
    const desc = msg[msg.type];
    if (!isObject(desc) || typeof desc.sdp !== 'string') return null;
    return { type: msg.type, [msg.type]: { type: msg.type, sdp: desc.sdp } };
  }

  if (msg.type === 'candidate') {
    const c = msg.candidate;
    if (
      !isObject(c) ||
      typeof c.candidate !== 'string' ||
      !optional(c.sdpMid, 'string') ||
      !optional(c.sdpMLineIndex, 'number') ||
      !optional(c.usernameFragment, 'string')
    ) {
      return null;
    }
    return {
      type: 'candidate',
      candidate: {
        candidate: c.candidate,
        sdpMid: c.sdpMid ?? null,
        sdpMLineIndex: c.sdpMLineIndex ?? null,
        usernameFragment: c.usernameFragment ?? null,
      },
    };
  }

  if (msg.type === 'ws-fallback') return { type: 'ws-fallback' };
  return null;
}

function handleJoin(ws, msg) {
  if (ws.room) return;
  if (typeof msg.room !== 'string' || !ROOM_ID_RE.test(msg.room)) {
    sendJson(ws, { type: 'error', reason: 'invalid-room' });
    return;
  }

  const roomId = msg.room.toLowerCase();
  let room = rooms.get(roomId);
  if (room && room.peers.size >= 2) {
    sendJson(ws, { type: 'full' });
    return;
  }
  if (!room) {
    room = { peers: new Set(), isPro: false, relayActive: false, relayBytes: 0 };
    rooms.set(roomId, room);
  }

  ws.isPro = verifyProToken(msg.token);
  room.peers.add(ws);
  ws.room = room;
  ws.roomId = roomId;

  if (room.peers.size !== 2) return;

  room.isPro = [...room.peers].some((p) => p.isPro);
  room.relayActive = false;
  const turnEnabled = room.isPro && TURN_ENABLED;
  const iceServers = getIceServers(turnEnabled);
  const wsRelayCap = room.isPro ? RELAY_CAP_PRO : RELAY_CAP_FREE;

  for (const peer of room.peers) {
    sendJson(peer, {
      type: 'ready',
      initiator: peer === ws,
      iceServers,
      turnEnabled,
      relayEnabled: turnEnabled, // deprecated alias of turnEnabled for older clients
      wsRelayCap,
      wsRelayRemaining: Math.max(0, wsRelayCap - room.relayBytes),
    });
  }
}

function handleControlMessage(ws, data) {
  if (!countControlMessage(ws)) return;

  let msg;
  try {
    msg = JSON.parse(data.toString('utf8'));
  } catch {
    return;
  }
  if (!isObject(msg) || typeof msg.type !== 'string') return;

  if (msg.type === 'join') {
    handleJoin(ws, msg);
    return;
  }

  const room = ws.room;
  if (!room || room.peers.size !== 2) return;

  const clean = sanitizeSignal(msg);
  if (!clean) return;

  if (clean.type === 'ws-fallback') room.relayActive = true;
  sendJson(otherPeer(room, ws), clean);
}

function leaveRoom(ws) {
  const room = ws.room;
  if (!room) return;

  room.peers.delete(ws);
  room.relayActive = false;
  ws.room = null;

  for (const peer of room.peers) {
    // The peer may have been paused waiting on this socket's queue.
    peer.backpressured = false;
    updatePause(peer);
    sendJson(peer, { type: 'peer-left' });
  }

  if (room.peers.size === 0 && rooms.get(ws.roomId) === room) rooms.delete(ws.roomId);
  ws.roomId = null;
}

function cleanupSocket(ws) {
  if (ws.cleanedUp) return;
  ws.cleanedUp = true;
  if (ws.throttleTimer !== null) {
    clearTimeout(ws.throttleTimer);
    ws.throttleTimer = null;
  }
  leaveRoom(ws);
}

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.room = null;
  ws.roomId = null;
  ws.isPro = false;
  ws.msgCount = 0;
  ws.msgWindowStart = Date.now();
  ws.cleanedUp = false;
  ws.backpressured = false;
  ws.throttleTimer = null;
  ws.bucket = 0;
  ws.bucketAt = 0;

  ws.on('pong', () => {
    ws.isAlive = true;
  });

  ws.on('message', (data, isBinary) => {
    if (ws.readyState !== WebSocket.OPEN) return;
    ws.isAlive = true;
    try {
      if (isBinary) handleRelayFrame(ws, data);
      else handleControlMessage(ws, data);
    } catch (err) {
      logError('Message handler failed', err);
      ws.close(1011, 'Internal error');
    }
  });

  ws.on('close', () => cleanupSocket(ws));
  ws.on('error', () => cleanupSocket(ws));
});

wss.on('error', (err) => logError('WebSocket server error', err));

// ---------------------------------------------------------------------------
// Timers
// ---------------------------------------------------------------------------

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    // A paused socket is one we chose not to read; its pongs cannot arrive.
    // It resumes as soon as its peer drains or leaves, then is checked again.
    if (ws.isPaused) continue;

    if (!ws.isAlive) {
      cleanupSocket(ws);
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    if (ws.readyState === WebSocket.OPEN) ws.ping(() => {});
  }
}, HEARTBEAT_MS);

const sweep = setInterval(() => {
  const now = Date.now();

  for (const [key, state] of ipState) {
    state.timestamps = state.timestamps.filter((t) => now - t < CONN_WINDOW_MS);
    if (state.active === 0 && state.timestamps.length === 0) ipState.delete(key);
  }
  for (const [key, entry] of claimHits) {
    if (entry.resetAt <= now) claimHits.delete(key);
  }
  for (const [key, entry] of invoices) {
    if (entry.forgetAt <= now) invoices.delete(key);
    else if (entry.token && entry.claimUntil <= now) entry.token = null;
  }
}, SWEEP_MS);

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

let shuttingDown = false;

function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  logInfo(`${signal} received, shutting down`);

  clearInterval(heartbeat);
  clearInterval(sweep);

  // Stop accepting, then close every WebSocket: ws v8 does not do this for us
  // and server.close() waits for all sockets, upgraded ones included.
  let open = listeners.length;
  for (const srv of listeners) {
    srv.close(() => {
      open -= 1;
      if (open === 0) process.exit(0);
    });
  }
  for (const ws of wss.clients) {
    if (ws.isPaused) ws.resume();
    ws.close(1001, 'Server shutting down');
  }
  wss.close();
  for (const srv of listeners) srv.closeIdleConnections();

  setTimeout(() => {
    for (const ws of wss.clients) ws.terminate();
    for (const srv of listeners) srv.closeAllConnections();
  }, SHUTDOWN_GRACE_MS / 2).unref();
  setTimeout(() => process.exit(0), SHUTDOWN_GRACE_MS).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

process.on('uncaughtException', (err) => {
  logError('Uncaught exception', err);
  process.exit(1);
});
process.on('unhandledRejection', (reason) => {
  logError('Unhandled rejection', reason);
  process.exit(1);
});

server.on('error', (err) => {
  logError('HTTP server error', err);
  process.exit(1);
});
if (onionServer) {
  onionServer.on('error', (err) => {
    logError('Onion HTTP server error', err);
    process.exit(1);
  });
}

let pendingListeners = listeners.length;
function onListening() {
  pendingListeners -= 1;
  if (pendingListeners > 0) return;
  logInfo(
    `Signaling node listening on :${PORT}` +
      `${onionServer ? ` and onion :${ONION_PORT}` : ''} ` +
      `(turn=${TURN_ENABLED ? 'on' : 'off'}, pro=${PRO_TOKEN_SECRET ? 'on' : 'off'}, ` +
      `onion=${ONION_HOST ? 'on' : 'off'}, webhook=${BTCPAY_WEBHOOK_SECRET ? 'on' : 'off'}, ` +
      `relay cap=${RELAY_CAP_FREE / MB}/${RELAY_CAP_PRO / MB} MB)`
  );
}
server.listen(PORT, onListening);
if (onionServer) onionServer.listen(ONION_PORT, onListening);
