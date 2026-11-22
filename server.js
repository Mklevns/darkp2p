'use strict';

/*
 * DARKP2P signaling node
 *
 *  - Pairs two peers per room in RAM and forwards WebRTC signaling.
 *  - Relays end-to-end encrypted binary frames over the same WebSocket when
 *    WebRTC is unavailable (Tor Browser), with byte caps, a byte-rate limit
 *    and backpressure. The server never holds the AES key.
 *  - Mints stateless HMAC-signed Pro tokens from NOWPayments IPN callbacks.
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
// Conservative address shapes, checked only to keep junk out of the page:
// Monero standard/subaddress (95) or integrated (106) base58; Bitcoin
// lowercase bech32/bech32m or legacy base58.
const XMR_ADDRESS_RE = /^[48][1-9A-HJ-NP-Za-km-z]{94}(?:[1-9A-HJ-NP-Za-km-z]{11})?$/;
const BTC_ADDRESS_RE = /^(?:bc1[02-9ac-hj-np-z]{8,87}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$/;

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
// NOWPayments. Checkout and minting are enabled only with an API key, which
// then also requires the IPN secret.
const NOWPAYMENTS_API_KEY = envStr('NOWPAYMENTS_API_KEY');
const NOWPAYMENTS_IPN_SECRET = envStr('NOWPAYMENTS_IPN_SECRET');
const NOWPAYMENTS_API_URL = (envStr('NOWPAYMENTS_API_URL') || 'https://api.nowpayments.io').replace(/\/+$/, '');
const PRO_1D_PRICE_USD_RAW = envStr('PRO_1D_PRICE_USD') || '2';
const PRO_30D_PRICE_USD_RAW = envStr('PRO_30D_PRICE_USD') || '5';
// auto  = trust X-Forwarded-For only when the direct peer is loopback/private
//         (i.e. a local reverse proxy such as the bundled Caddy container)
// 1 / 0 = always / never trust it
const TRUST_PROXY = envStr('TRUST_PROXY').toLowerCase() || 'auto';
// Public payment details injected into index.html at boot (see pageConfig).
// All optional: the matching tip buttons are hidden when unset.
const TIP_XMR_ADDRESS = envStr('TIP_XMR_ADDRESS');
const TIP_BTC_ADDRESS = envStr('TIP_BTC_ADDRESS');

const MAX_TOTAL_CONNS = envInt('MAX_TOTAL_CONNS', 1000, 2, 100000);
const MAX_CONNS_PER_IP = envInt('MAX_CONNS_PER_IP', 6, 1, 10000);
const MAX_ONION_CONNS = envInt('MAX_ONION_CONNS', 100, 2, 10000);
const CONN_WINDOW_MS = 60_000;
const MAX_CONNS_PER_WINDOW = 20;
const MAX_ONION_CONNS_PER_WINDOW = 120;
const MSG_WINDOW_MS = 10_000;
const MAX_MSGS_PER_WINDOW = 120; // JSON control messages only
const MAX_CANDIDATES_PER_PEER = 200; // ICE candidates per socket, per peer pairing
const CLAIM_WINDOW_MS = 60_000;
const MAX_CLAIMS_PER_WINDOW = 30;
const MAX_ONION_CLAIMS_PER_WINDOW = 120;
const CHECKOUT_WINDOW_MS = 60_000;
const MAX_CHECKOUTS_PER_WINDOW = 10;
const MAX_ONION_CHECKOUTS_PER_WINDOW = 60; // all onion clients share one bucket
const MAX_CHECKOUT_CLIENTS = 10_000; // tracked client buckets; oldest evicted first
const MAX_CHECKOUT_BYTES = 4 * 1024;

const HEARTBEAT_MS = envInt('HEARTBEAT_MS', 30_000, 1000, 300_000);
// A sender paused this long because its receiver is not draining means the
// receiver has stopped reading: its queued data is pinned in our RAM.
const RELAY_STALL_MS = envInt('RELAY_STALL_MS', 60_000, 1000, 600_000);
const SWEEP_MS = envInt('SWEEP_MS', 5 * 60 * 1000, 1000, 3600_000); // also the stats interval
const SHUTDOWN_GRACE_MS = 5_000;
// How long a minted token stays claimable by invoice ID.
const CLAIM_TTL_MS = envInt('CLAIM_TTL_HOURS', 48, 1, 168) * 3600e3;
const INVOICE_DEDUPE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_INVOICES = 20_000; // evicts the earliest forgetAt first
const SETTLEMENT_GRACE_S = 6 * 3600; // added to deterministic token expiry
const CLAIM_MISS_TTL_MS = 30_000; // negative cache for NOWPayments lookups
const MAX_CLAIM_MISSES = 5_000; // evicts the oldest entry first
const CLAIM_RETRY_AFTER_S = 30;
const MAX_WEBHOOK_BYTES = 64 * 1024;
const MAX_WS_PAYLOAD = 64 * 1024;
const NOWPAYMENTS_API_TIMEOUT_MS = 5_000;

// WebSocket relay. The cap is per room, counts both directions, and lasts
// until the room empties. A room is Pro if either peer holds a valid token.
const RELAY_CAP_FREE = envInt('RELAY_CAP_FREE_MB', 50, 0, 1024 * 1024) * MB;
const RELAY_CAP_PRO = envInt('RELAY_CAP_PRO_MB', 500, 0, 1024 * 1024) * MB;
const RELAY_RATE_FREE = 1 * MB; // bytes per second, per sender
const RELAY_RATE_PRO = 4 * MB;
const RELAY_HIGH_WATER = 512 * 1024; // pause the sender above this much queued for the receiver
const RELAY_LOW_WATER = 128 * 1024;
const MAX_RELAY_ROOMS = envInt('MAX_RELAY_ROOMS', 50, 1, 10000); // rooms with relayActive
const MIN_THROTTLE_MS = 25;

const ROOM_ID_RE = /^[a-f0-9]{16,64}$/i;
// NOWPayments payment and invoice IDs are decimal integers.
const NP_ID_RE = /^[1-9][0-9]{0,19}$/;
// Our own order IDs: "<tier>:<32 hex>". The tier prefix decides the token days.
const ORDER_ID_RE = /^(pro-1d|pro-30d):[a-f0-9]{32}$/;

// Ephemeral per-boot salt: client addresses and invoice IDs are only ever
// held as salted HMACs, and the salt dies with the process.
const BOOT_SALT = crypto.randomBytes(32);

// Price in USD: a positive decimal with at most 2 places.
function parsePrice(name, raw) {
  const n = Number(raw);
  if (!/^\d{1,6}(?:\.\d{1,2})?$/.test(raw) || !(n > 0)) {
    fatal(`${name} must be a positive USD amount with at most 2 decimals`);
  }
  return n;
}

// Checkout tier -> token days and price. "pro-30d" lasts PRO_TOKEN_DAYS.
const PRO_TIERS = new Map([
  ['pro-1d', { days: 1, priceUsd: parsePrice('PRO_1D_PRICE_USD', PRO_1D_PRICE_USD_RAW) }],
  ['pro-30d', { days: PRO_TOKEN_DAYS, priceUsd: parsePrice('PRO_30D_PRICE_USD', PRO_30D_PRICE_USD_RAW) }],
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
  if (CLAIM_TTL_MS > INVOICE_DEDUPE_MS) {
    fatal('CLAIM_TTL_HOURS must not exceed the invoice dedupe window');
  }
  if (NOWPAYMENTS_API_KEY && !NOWPAYMENTS_IPN_SECRET) {
    fatal('NOWPAYMENTS_API_KEY is set but NOWPAYMENTS_IPN_SECRET is not');
  }
  if (NOWPAYMENTS_IPN_SECRET && !NOWPAYMENTS_API_KEY) {
    fatal('NOWPAYMENTS_IPN_SECRET is set but NOWPAYMENTS_API_KEY is not');
  }
  if (NOWPAYMENTS_API_KEY && !PRO_TOKEN_SECRET) {
    fatal('NOWPAYMENTS_API_KEY is set but PRO_TOKEN_SECRET is not: paid checkouts could not mint tokens');
  }
  {
    let url;
    try {
      url = new URL(NOWPAYMENTS_API_URL);
    } catch {
      fatal('NOWPAYMENTS_API_URL is not a valid URL');
    }
    if (url.protocol !== 'https:') fatal('NOWPAYMENTS_API_URL must be https');
    if (url.search || url.hash || url.username || url.password) {
      fatal('NOWPAYMENTS_API_URL must not contain a query, fragment or credentials');
    }
  }
  if (TIP_XMR_ADDRESS && !XMR_ADDRESS_RE.test(TIP_XMR_ADDRESS)) {
    fatal('TIP_XMR_ADDRESS is not a valid Monero address');
  }
  if (TIP_BTC_ADDRESS && !BTC_ADDRESS_RE.test(TIP_BTC_ADDRESS)) {
    fatal('TIP_BTC_ADDRESS is not a valid Bitcoin address');
  }
  for (const url of STUN_URLS.split(',').map((s) => s.trim()).filter(Boolean)) {
    if (!/^stuns?:[a-z0-9.\-[\]:]+$/i.test(url)) fatal(`STUN_URLS entry "${url}" is invalid`);
  }

  if (TURN_HOST && !TURN_SECRET) logWarn('TURN_HOST is set without TURN_SECRET: Pro rooms get STUN only');
  if (TURN_SECRET && !TURN_HOST) logWarn('TURN_SECRET is set without TURN_HOST: TURN is disabled');
  if (!PRO_TOKEN_SECRET) logWarn('PRO_TOKEN_SECRET is not set: Pro tokens are disabled');
  if (!NOWPAYMENTS_API_KEY) logInfo('NOWPAYMENTS_API_KEY is not set: checkout and payment webhook are disabled');
  for (const [name, value] of [
    ['TURN_SECRET', TURN_SECRET],
    ['PRO_TOKEN_SECRET', PRO_TOKEN_SECRET],
    ['NOWPAYMENTS_IPN_SECRET', NOWPAYMENTS_IPN_SECRET],
  ]) {
    if (value && value.length < 32) logWarn(`${name} is shorter than 32 characters`);
  }
  if (!STUN_URLS && !TURN_HOST) {
    logWarn('No STUN_URLS or TURN_HOST: falling back to stun.l.google.com, which sees client IPs');
  }
}

validateConfig();

const ALLOWED_ORIGINS = parseOrigins(envStr('ALLOWED_ORIGINS') || `https://${DOMAIN}`);
if (ONION_HOST) ALLOWED_ORIGINS.add(`http://${ONION_HOST}`);

const PAYMENTS_ENABLED = Boolean(NOWPAYMENTS_API_KEY && NOWPAYMENTS_IPN_SECRET && PRO_TOKEN_SECRET);
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
    "img-src 'self'",
    `connect-src ${[...connect].join(' ')}`,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join('; ');
}

// Non-executable data block the page reads its payment details from.
const CONFIG_BLOCK_OPEN = '<script type="application/json" id="darkp2p-config">';
const CONFIG_BLOCK = `${CONFIG_BLOCK_OPEN}{}</script>`;

// Public values only. Every "<" becomes the JSON escape \u003c, so no value
// can close the <script> block or open a comment inside it.
function pageConfigJson() {
  const cfg = {
    checkoutEnabled: PAYMENTS_ENABLED,
    // Shown next to each checkout button, so the page never disagrees with
    // what the checkout charges.
    priceUsd: Object.fromEntries([...PRO_TIERS].map(([tier, t]) => [tier, t.priceUsd])),
    tipXmr: TIP_XMR_ADDRESS || null,
    tipBtc: TIP_BTC_ADDRESS || null,
  };
  return JSON.stringify(cfg).replace(/</g, '\\u003c');
}

// Boot-time substitutions for index.html. The CSP hashes are computed on the
// result, so they always match what is served.
function renderIndex(html) {
  if (html.split(CONFIG_BLOCK).length !== 2) {
    fatal('public/index.html must contain the empty darkp2p-config block exactly once');
  }
  const origin = `https://${DOMAIN}/`;
  const config = `${CONFIG_BLOCK_OPEN}${pageConfigJson()}</script>`;
  // split/join: no "$" replacement patterns in the inserted values.
  return html.split('https://darkp2p.com/').join(origin).split(CONFIG_BLOCK).join(config);
}

function loadPage(file, required, transform) {
  let html;
  try {
    html = fs.readFileSync(path.join(__dirname, 'public', file), 'utf8');
  } catch (err) {
    if (required) fatal(`Cannot read public/${file} (${err.code || err.message})`);
    return null;
  }
  if (transform) html = transform(html);
  return { body: Buffer.from(html, 'utf8'), csp: buildPageCsp(html) };
}

// Boot-time substitutions for the static content pages: the canonical/OG
// origin, and the onion address. A page marks what it shows with an onion
// address as <!--onion:on-->…<!--/onion:on--> (where {{ONION_HOST}} is the
// hostname) and what it shows without one as <!--onion:off-->…<!--/onion:off-->.
function renderStaticPage(html) {
  const drop = ONION_HOST ? 'off' : 'on';
  const dropped = new RegExp(`<!--onion:${drop}-->[\\s\\S]*?<!--/onion:${drop}-->`, 'g');
  return html
    .replace(dropped, '')
    .replace(/<!--\/?onion:(?:on|off)-->\n?/g, '')
    .split('{{ONION_HOST}}').join(ONION_HOST)
    .split('https://darkp2p.com/').join(`https://${DOMAIN}/`);
}

const indexPage = loadPage('index.html', true, renderIndex);
const notFoundPage = loadPage('404.html', false);

// Every indexable page: clean URL -> file in public/. `lastmod` is what
// sitemap.xml reports, so bump it when the page's content changes.
const SITE_PAGES = [
  { path: '/', file: 'index.html', lastmod: '2026-11-22' },
  { path: '/how-it-works', file: 'how-it-works.html', lastmod: '2026-11-22' },
  { path: '/security', file: 'security.html', lastmod: '2026-11-22' },
  { path: '/tor', file: 'tor.html', lastmod: '2026-11-22' },
  { path: '/compare', file: 'compare.html', lastmod: '2026-11-22' },
  { path: '/abuse', file: 'abuse.html', lastmod: '2026-11-22' },
];

// path -> page, for everything except "/" (which index.html serves). A page
// whose file is missing is left out, and its URL answers 404.
const staticPages = new Map();
for (const { path: pagePath, file } of SITE_PAGES) {
  if (pagePath === '/') continue;
  const page = loadPage(file, false, renderStaticPage);
  if (page) staticPages.set(pagePath, page);
  else logWarn(`public/${file} is missing: ${pagePath} is disabled`);
}

const TYPE_TEXT = 'text/plain; charset=utf-8';
const TYPE_HTML = 'text/html; charset=utf-8';
const TYPE_JSON = 'application/json; charset=utf-8';
const TYPE_XML = 'application/xml; charset=utf-8';
const TYPE_PNG = 'image/png';
const TYPE_SVG = 'image/svg+xml';

// Fixed files served from memory: the social card, the icons, and any
// IndexNow key file (public/<32 hex>.txt, whose content is the key). Link
// previews and search engines fetch these from other origins.
const ASSET_FILES = [
  ['og-image.png', TYPE_PNG],
  ['favicon.svg', TYPE_SVG],
  ['apple-touch-icon.png', TYPE_PNG],
];
const INDEXNOW_KEY_FILE_RE = /^[a-f0-9]{32}\.txt$/;
const ASSET_HEADERS = {
  'Cache-Control': 'public, max-age=86400',
  'Cross-Origin-Resource-Policy': 'cross-origin',
};

function loadAssets() {
  const dir = path.join(__dirname, 'public');
  const files = [...ASSET_FILES];
  try {
    for (const name of fs.readdirSync(dir)) {
      if (INDEXNOW_KEY_FILE_RE.test(name)) files.push([name, TYPE_TEXT]);
    }
  } catch {}
  const assets = new Map(); // "/name" -> { body, type }
  for (const [name, type] of files) {
    try {
      assets.set(`/${name}`, { body: fs.readFileSync(path.join(dir, name)), type });
    } catch {
      logWarn(`public/${name} is missing: /${name} is disabled`);
    }
  }
  return assets;
}

const assets = loadAssets();

// "/" also carries the WebSocket endpoint, which cannot be disallowed without
// hiding the page itself.
const robotsTxt =
  'User-agent: *\n' +
  'Allow: /\n' +
  'Disallow: /api/\n' +
  'Disallow: /webhook/\n' +
  'Disallow: /health\n' +
  `Sitemap: https://${DOMAIN}/sitemap.xml\n`;
const sitemapXml =
  '<?xml version="1.0" encoding="UTF-8"?>\n' +
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
  SITE_PAGES
    .filter((p) => p.path === '/' || staticPages.has(p.path))
    .map((p) => `  <url><loc>https://${DOMAIN}${p.path}</loc><lastmod>${p.lastmod}</lastmod></url>\n`)
    .join('') +
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
const checkoutHits = new Map(); // clientKey -> { count, resetAt }; capped at MAX_CHECKOUT_CLIENTS
let totalConns = 0;

// Interval counters for the periodic stats line. Aggregates only: nothing
// here identifies a client, room or invoice. Reset by every sweep.
const stats = { relayBytes: 0, rejected429: 0, stalls: 0 };

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
// Pro tokens, TURN credentials, NOWPayments
// ---------------------------------------------------------------------------

function mintProTokenAt(expUnix) {
  const exp = String(expUnix);
  const sig = crypto.createHmac('sha256', PRO_TOKEN_SECRET).update(exp).digest('hex');
  return `${exp}.${sig}`;
}

function mintProToken(days) {
  return mintProTokenAt(Math.floor(Date.now() / 1000) + days * 86400);
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

// NOWPayments IPN signature: hex HMAC-SHA512 over the JSON body re-serialized
// with every object's keys sorted, recursively (NOWPayments' documented
// scheme). Arrays keep their order; objects inside them are sorted too.
function sortKeysDeep(value) {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (isObject(value)) {
    const out = {};
    for (const key of Object.keys(value).sort()) out[key] = sortKeysDeep(value[key]);
    return out;
  }
  return value;
}

function verifyNowPaymentsSig(payload, sigHeader) {
  if (!NOWPAYMENTS_IPN_SECRET || typeof sigHeader !== 'string') return false;
  const sig = sigHeader.trim().toLowerCase();
  if (!/^[a-f0-9]{128}$/.test(sig)) return false;
  const expected = crypto
    .createHmac('sha512', NOWPAYMENTS_IPN_SECRET)
    .update(JSON.stringify(sortKeysDeep(payload)))
    .digest();
  return crypto.timingSafeEqual(Buffer.from(sig, 'hex'), expected);
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

// paymentKey -> { token: string|null, claimUntil, forgetAt }
// Keyed by salted HMACs of the NOWPayments payment ID and, as an alias, its
// invoice ID. The token can be read until claimUntil. The record itself is
// kept until forgetAt so an IPN redelivery cannot mint a second token.
// Capped at MAX_INVOICES; the entry with the earliest forgetAt is evicted.
const invoices = new Map();

// paymentKey -> expiresAt. Recent NOWPayments lookups that minted nothing, so
// a polling client cannot turn every claim into an API call. Capped at
// MAX_CLAIM_MISSES; Map order is insertion order, so the oldest goes first.
const claimMisses = new Map();

function paymentKey(kind, id) {
  return crypto.createHmac('sha256', BOOT_SALT).update(`${kind}:${id}`).digest('hex');
}

function rememberInvoice(key, token) {
  if (!invoices.has(key) && invoices.size >= MAX_INVOICES) {
    let oldestKey = null;
    let oldestAt = Infinity;
    for (const [k, entry] of invoices) {
      if (entry.forgetAt < oldestAt) {
        oldestAt = entry.forgetAt;
        oldestKey = k;
      }
    }
    if (oldestKey !== null) invoices.delete(oldestKey);
  }
  const now = Date.now();
  invoices.set(key, { token, claimUntil: now + CLAIM_TTL_MS, forgetAt: now + INVOICE_DEDUPE_MS });
}

// Records a minted token under the payment ID and, when known, the invoice ID.
function rememberPayment(payment, token) {
  const keys = [paymentKey('payment', payment.paymentId)];
  if (payment.invoiceId) keys.push(paymentKey('invoice', payment.invoiceId));
  for (const key of keys) {
    if (!invoices.has(key)) rememberInvoice(key, token);
    claimMisses.delete(key);
  }
}

function rememberClaimMiss(key) {
  claimMisses.delete(key);
  while (claimMisses.size >= MAX_CLAIM_MISSES) {
    claimMisses.delete(claimMisses.keys().next().value);
  }
  claimMisses.set(key, Date.now() + CLAIM_MISS_TTL_MS);
}

function isRecentClaimMiss(key) {
  const expiresAt = claimMisses.get(key);
  if (expiresAt === undefined) return false;
  if (expiresAt > Date.now()) return true;
  claimMisses.delete(key);
  return false;
}

function nowPaymentsError(code, status) {
  return Object.assign(new Error('NOWPayments request failed'), { name: 'NowPaymentsError', code, status });
}

// Safe to log: never the message, URL or cause (they can carry payment IDs).
function describeLookupError(err) {
  const parts = [`name=${err && err.name ? err.name : 'Error'}`];
  if (err && typeof err.code === 'string') parts.push(`code=${err.code}`);
  if (err && Number.isInteger(err.status)) parts.push(`status=${err.status}`);
  return parts.join(' ');
}

function npId(value) {
  const str = typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value;
  return typeof str === 'string' && NP_ID_RE.test(str) ? str : null;
}

// Normalizes a NOWPayments payment object (IPN body or GET /v1/payment/{id}).
// Returns null if a field we rely on is missing or malformed. createdS is
// null when created_at is absent, so the caller can fall back to a lookup.
function parsePayment(obj) {
  if (!isObject(obj)) return null;
  const paymentId = npId(obj.payment_id);
  if (!paymentId || typeof obj.payment_status !== 'string') return null;
  if (obj.invoice_id !== undefined && obj.invoice_id !== null && npId(obj.invoice_id) === null) return null;
  const createdMs = typeof obj.created_at === 'string' ? Date.parse(obj.created_at) : NaN;
  return {
    paymentId,
    invoiceId: npId(obj.invoice_id),
    status: obj.payment_status,
    orderId: typeof obj.order_id === 'string' ? obj.order_id : null,
    priceAmount: typeof obj.price_amount === 'number' ? obj.price_amount : Number(obj.price_amount),
    priceCurrency: typeof obj.price_currency === 'string' ? obj.price_currency.toLowerCase() : null,
    createdS: Number.isFinite(createdMs) && createdMs > 0 ? Math.floor(createdMs / 1000) : null,
  };
}

function nowPaymentsFetch(path, init) {
  return fetch(`${NOWPAYMENTS_API_URL}${path}`, {
    ...init,
    headers: { 'x-api-key': NOWPAYMENTS_API_KEY, Accept: 'application/json', ...init?.headers },
    redirect: 'error',
    signal: AbortSignal.timeout(NOWPAYMENTS_API_TIMEOUT_MS),
  });
}

// Canonical payment record from GET /v1/payment/{id}. Returns null when
// NOWPayments says it does not exist; throws when it cannot give an answer.
async function fetchPayment(paymentId) {
  const res = await nowPaymentsFetch(`/v1/payment/${encodeURIComponent(paymentId)}`);
  if (res.status === 404 || res.status === 400) {
    await res.body?.cancel();
    return null;
  }
  if (!res.ok) {
    await res.body?.cancel();
    throw nowPaymentsError('E_NP_STATUS', res.status);
  }
  let body;
  try {
    body = await res.json();
  } catch {
    throw nowPaymentsError('E_NP_BODY');
  }
  const payment = parsePayment(body);
  if (!payment || payment.paymentId !== paymentId || payment.createdS === null) {
    throw nowPaymentsError('E_NP_BODY');
  }
  return payment;
}

// Deterministic token for a payment: the same payment always yields the same
// token (exp = created_at + days + 6 h), so one re-minted after a restart is
// byte-identical. Returns null when the payment does not qualify: not
// finished, not one of our orders, underpaid or not in USD, or past its claim
// window (created_at + settlement grace + CLAIM_TTL), so a restart cannot let
// a leaked payment ID re-fetch the token for its whole lifetime.
function tokenForPayment(payment) {
  if (!payment || payment.status !== 'finished' || payment.createdS === null) return null;
  const m = payment.orderId === null ? null : ORDER_ID_RE.exec(payment.orderId);
  if (!m) return null;
  const tier = PRO_TIERS.get(m[1]);
  if (payment.priceCurrency !== 'usd' || !(payment.priceAmount >= tier.priceUsd)) return null;
  const nowS = Math.floor(Date.now() / 1000);
  if (nowS > payment.createdS + SETTLEMENT_GRACE_S + CLAIM_TTL_MS / 1000) return null;
  const exp = payment.createdS + tier.days * 86400 + SETTLEMENT_GRACE_S;
  if (exp <= nowS) return null;
  return mintProTokenAt(exp);
}

// Every status except "finished" is acknowledged and dropped: waiting,
// confirming, confirmed, sending, partially_paid, failed, expired, refunded.
async function handleNowPaymentsWebhook(req, res) {
  if (!PAYMENTS_ENABLED) return respondJson(req, res, 503, { ok: false });

  let rawBody;
  try {
    rawBody = await readRawBody(req, MAX_WEBHOOK_BYTES);
  } catch (err) {
    const status = err.code === 'E_TOO_LARGE' ? 413 : 400;
    return respond(req, res, status, TYPE_TEXT, http.STATUS_CODES[status], { Connection: 'close' });
  }

  let event;
  try {
    event = JSON.parse(rawBody.toString('utf8'));
  } catch {
    return respondJson(req, res, 400, { ok: false });
  }
  if (!isObject(event)) return respondJson(req, res, 400, { ok: false });
  if (!verifyNowPaymentsSig(event, req.headers['x-nowpayments-sig'])) {
    return respondJson(req, res, 401, { ok: false });
  }

  let payment = parsePayment(event);
  if (!payment) return respondJson(req, res, 400, { ok: false });
  if (payment.status !== 'finished') return respondJson(req, res, 200, { ok: true, ignored: true });

  // Deduplicate on the payment itself: a redelivery of an IPN we never
  // processed (we were down) must still mint, a second one must not.
  if (invoices.has(paymentKey('payment', payment.paymentId))) {
    return respondJson(req, res, 200, { ok: true });
  }

  // The signed IPN normally carries created_at. Without it, ask NOWPayments.
  if (payment.createdS === null) {
    try {
      payment = await fetchPayment(payment.paymentId);
    } catch (err) {
      logError(`NOWPayments payment lookup failed (${describeLookupError(err)})`);
      // Non-2xx makes NOWPayments retry the IPN later.
      return respondJson(req, res, 502, { ok: false });
    }
  }

  const token = tokenForPayment(payment);
  if (!token) {
    logWarn('Finished payment does not qualify: no token minted');
    return respondJson(req, res, 200, { ok: true, ignored: true });
  }
  rememberPayment(payment, token);
  logInfo('Pro token minted');
  return respondJson(req, res, 200, { ok: true });
}

function allowCheckout({ key, isOnion }) {
  const max = isOnion ? MAX_ONION_CHECKOUTS_PER_WINDOW : MAX_CHECKOUTS_PER_WINDOW;
  const now = Date.now();
  const entry = checkoutHits.get(key);
  if (!entry || entry.resetAt <= now) {
    checkoutHits.delete(key);
    while (checkoutHits.size >= MAX_CHECKOUT_CLIENTS) {
      checkoutHits.delete(checkoutHits.keys().next().value);
    }
    checkoutHits.set(key, { count: 1, resetAt: now + CHECKOUT_WINDOW_MS });
    return true;
  }
  if (entry.count >= max) return false;
  entry.count += 1;
  return true;
}

// POST /api/checkout {tier} -> {invoiceUrl}. Creates a NOWPayments invoice
// whose order_id carries the tier, so the IPN alone decides the token days.
async function handleCheckout(req, res) {
  if (!PAYMENTS_ENABLED) return respondJson(req, res, 404, { error: 'checkout disabled' });
  if (!allowCheckout(getClientIdentity(req))) {
    stats.rejected429 += 1;
    return respondJson(req, res, 429, { error: 'rate limited' }, { 'Retry-After': '60' });
  }
  // A JSON content type cannot be sent cross-site without a CORS preflight,
  // which this server never grants.
  const ctype = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
  if (ctype !== 'application/json') {
    return respondJson(req, res, 415, { error: 'expected application/json' }, { Connection: 'close' });
  }

  let rawBody;
  try {
    rawBody = await readRawBody(req, MAX_CHECKOUT_BYTES);
  } catch (err) {
    const status = err.code === 'E_TOO_LARGE' ? 413 : 400;
    return respondJson(req, res, status, { error: http.STATUS_CODES[status] }, { Connection: 'close' });
  }
  let body;
  try {
    body = JSON.parse(rawBody.toString('utf8'));
  } catch {
    return respondJson(req, res, 400, { error: 'invalid JSON' });
  }
  const tierName = isObject(body) && typeof body.tier === 'string' ? body.tier : '';
  const tier = PRO_TIERS.get(tierName);
  if (!tier) return respondJson(req, res, 400, { error: 'unknown tier' });

  const orderId = `${tierName}:${crypto.randomBytes(16).toString('hex')}`;
  let invoice;
  try {
    const npRes = await nowPaymentsFetch('/v1/invoice', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        price_amount: tier.priceUsd,
        price_currency: 'usd',
        order_id: orderId,
        order_description: tierName === 'pro-1d' ? 'DARKP2P Pro 24h' : `DARKP2P Pro ${tier.days}d`,
        ipn_callback_url: `https://${DOMAIN}/webhook/nowpayments`,
        success_url: `https://${DOMAIN}/`,
        cancel_url: `https://${DOMAIN}/`,
      }),
    });
    if (!npRes.ok) {
      await npRes.body?.cancel();
      throw nowPaymentsError('E_NP_STATUS', npRes.status);
    }
    try {
      invoice = await npRes.json();
    } catch {
      throw nowPaymentsError('E_NP_BODY');
    }
  } catch (err) {
    logWarn(`NOWPayments checkout failed (${describeLookupError(err)})`);
    return respondJson(req, res, 502, { error: 'checkout unavailable' });
  }

  let invoiceUrl = null;
  if (isObject(invoice) && typeof invoice.invoice_url === 'string') {
    try {
      const url = new URL(invoice.invoice_url);
      if (url.protocol === 'https:' && !url.username && !url.password) invoiceUrl = url.href;
    } catch {}
  }
  if (!invoiceUrl) {
    logWarn(`NOWPayments checkout failed (${describeLookupError(nowPaymentsError('E_NP_BODY'))})`);
    return respondJson(req, res, 502, { error: 'checkout unavailable' });
  }
  return respondJson(req, res, 200, { invoiceUrl });
}

// GET /api/claim?paymentId=… (or ?invoiceId=…). Only a payment ID can be
// re-derived from NOWPayments after a restart; an invoice ID is answered from
// what the IPN recorded.
async function handleClaim(req, res, url) {
  if (!allowClaim(getClientIdentity(req))) {
    stats.rejected429 += 1;
    return respondJson(req, res, 429, { ready: false }, { 'Retry-After': '60' });
  }

  const paymentId = url.searchParams.get('paymentId');
  const invoiceId = paymentId === null ? url.searchParams.get('invoiceId') : null;
  const kind = paymentId !== null ? 'payment' : 'invoice';
  const id = paymentId !== null ? paymentId : invoiceId || '';
  if (!NP_ID_RE.test(id)) return respondJson(req, res, 404, { ready: false });
  const key = paymentKey(kind, id);
  const entry = invoices.get(key);

  // The token stays readable for the whole claim window, so a response lost
  // in transit does not cost the buyer their token.
  if (entry) {
    if (!entry.token || entry.claimUntil <= Date.now()) {
      return respondJson(req, res, 404, { ready: false });
    }
    return respondJson(req, res, 200, { ready: true, token: entry.token });
  }

  // Cache miss (not finished yet, IPN not delivered, or we restarted): ask
  // NOWPayments for the payment and re-derive the deterministic token.
  if (kind !== 'payment' || !PAYMENTS_ENABLED || isRecentClaimMiss(key)) {
    return respondJson(req, res, 404, { ready: false });
  }

  let payment;
  try {
    payment = await fetchPayment(id);
  } catch (err) {
    logWarn(`NOWPayments payment lookup failed (${describeLookupError(err)})`);
    return respondJson(req, res, 503, { ready: false }, { 'Retry-After': String(CLAIM_RETRY_AFTER_S) });
  }

  const token = tokenForPayment(payment);
  if (!token) {
    rememberClaimMiss(key);
    return respondJson(req, res, 404, { ready: false });
  }
  rememberPayment(payment, token);
  return respondJson(req, res, 200, { ready: true, token });
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

  const page = pathname === '/' ? indexPage : staticPages.get(pathname);
  if (isRead && page) {
    const extra = ONION_HOST && !isOnionSocket(req)
      ? { 'Onion-Location': `http://${ONION_HOST}${pathname}` }
      : undefined;
    return respondPage(req, res, 200, page, extra);
  }
  // One URL per page: "/security/" moves to "/security".
  if (isRead && pathname.length > 1 && pathname.endsWith('/') && staticPages.has(pathname.slice(0, -1))) {
    return respond(req, res, 301, TYPE_TEXT, 'Moved Permanently', {
      Location: `${pathname.slice(0, -1)}${url.search}`,
    });
  }
  const asset = isRead ? assets.get(pathname) : undefined;
  if (asset) return respond(req, res, 200, asset.type, asset.body, ASSET_HEADERS);
  if (isRead && pathname === '/health') {
    return respond(req, res, 200, TYPE_TEXT, 'ok', { 'Cache-Control': 'no-store' });
  }
  if (isRead && pathname === '/robots.txt') return respond(req, res, 200, TYPE_TEXT, robotsTxt);
  if (isRead && pathname === '/sitemap.xml') return respond(req, res, 200, TYPE_XML, sitemapXml);
  if (isRead && pathname === '/api/claim') return await handleClaim(req, res, url);
  if (req.method === 'POST' && pathname === '/api/checkout') return await handleCheckout(req, res);
  if (req.method === 'POST' && pathname === '/webhook/nowpayments') return await handleNowPaymentsWebhook(req, res);

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
  if (!allowConnection(client)) {
    stats.rejected429 += 1;
    return rejectUpgrade(socket, 429);
  }

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

function setBackpressured(ws, value) {
  if (ws.backpressured === value) return;
  ws.backpressured = value;
  ws.backpressuredSince = value ? Date.now() : 0;
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
  stats.relayBytes += data.length;

  peer.send(data, { binary: true }, (err) => {
    if (err) return;
    // Bytes reaching the receiver prove it is alive even when its pongs are
    // stuck behind queued data on a slow link.
    peer.isAlive = true;
    if (ws.backpressured && peer.bufferedAmount <= RELAY_LOW_WATER) {
      setBackpressured(ws, false);
      updatePause(ws);
    }
  });

  if (!ws.backpressured && peer.bufferedAmount > RELAY_HIGH_WATER) {
    setBackpressured(ws, true);
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

  ws.candidateCount = 0;
  if (room.peers.size !== 2) return;

  // New pairing: both peers start a fresh ICE candidate budget.
  for (const peer of room.peers) peer.candidateCount = 0;
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

  if (clean.type === 'candidate') {
    ws.candidateCount += 1;
    if (ws.candidateCount > MAX_CANDIDATES_PER_PEER) {
      ws.close(1008, 'too-many-candidates');
      return;
    }
  }

  if (clean.type === 'ws-fallback' && !room.relayActive) {
    if (countRelayRooms() >= MAX_RELAY_ROOMS) {
      sendJson(ws, { type: 'error', reason: 'relay-busy' });
      return;
    }
    room.relayActive = true;
  }
  sendJson(otherPeer(room, ws), clean);
}

function countRelayRooms() {
  let n = 0;
  for (const room of rooms.values()) if (room.relayActive) n += 1;
  return n;
}

function leaveRoom(ws) {
  const room = ws.room;
  if (!room) return;

  room.peers.delete(ws);
  room.relayActive = false;
  ws.room = null;

  for (const peer of room.peers) {
    // The peer may have been paused waiting on this socket's queue.
    setBackpressured(peer, false);
    peer.candidateCount = 0;
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
  ws.backpressuredSince = 0;
  ws.candidateCount = 0;
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

// The sender is paused on a receiver that has not drained for RELAY_STALL_MS:
// the receiver is alive but not reading, so close it and free its queue.
function checkRelayStall(ws, now) {
  if (!ws.backpressured || now - ws.backpressuredSince <= RELAY_STALL_MS) return;
  const peer = ws.room ? otherPeer(ws.room, ws) : null;
  if (!peer || peer.readyState !== WebSocket.OPEN) return;
  const notice = { type: 'error', reason: 'relay-stalled' };
  stats.stalls += 1;
  sendJson(ws, notice);
  sendJson(peer, notice);
  peer.close(1008, 'relay-stalled');
}

const heartbeat = setInterval(() => {
  const now = Date.now();
  for (const ws of wss.clients) {
    // A paused socket is one we chose not to read; its pongs cannot arrive.
    // It resumes as soon as its peer drains or leaves, then is checked again.
    if (ws.isPaused) {
      checkRelayStall(ws, now);
      continue;
    }

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
  for (const [key, entry] of checkoutHits) {
    if (entry.resetAt <= now) checkoutHits.delete(key);
  }
  for (const [key, expiresAt] of claimMisses) {
    if (expiresAt <= now) claimMisses.delete(key);
  }
  for (const [key, entry] of invoices) {
    if (entry.forgetAt <= now) invoices.delete(key);
    else if (entry.token && entry.claimUntil <= now) entry.token = null;
  }

  // Counters only, no IDs or per-client data. relayMB/rejected429/stalls
  // cover the interval since the previous line; conns/rooms are current.
  logInfo(
    `stats conns=${totalConns} rooms=${rooms.size} relayRooms=${countRelayRooms()} ` +
      `relayMB=${(stats.relayBytes / MB).toFixed(1)} rejected429=${stats.rejected429} stalls=${stats.stalls}`
  );
  stats.relayBytes = 0;
  stats.rejected429 = 0;
  stats.stalls = 0;
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
      `onion=${ONION_HOST ? 'on' : 'off'}, payments=${PAYMENTS_ENABLED ? 'on' : 'off'}, ` +
      `relay cap=${RELAY_CAP_FREE / MB}/${RELAY_CAP_PRO / MB} MB)`
  );
}
server.listen(PORT, onListening);
if (onionServer) onionServer.listen(ONION_PORT, onListening);
