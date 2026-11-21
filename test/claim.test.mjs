// Pro token claims survive restarts via deterministic re-minting from BTCPay.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHmac, randomBytes } from 'node:crypto';
import { createServer } from 'node:https';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  startServer,
  TEST_PRO_TOKEN_SECRET,
  TEST_BTCPAY_WEBHOOK_SECRET,
} from './helpers/server.mjs';

const hasOpenssl = spawnSync('openssl', ['version']).status === 0;
const skip = hasOpenssl ? false : 'openssl not available';

const STORE = 'teststore123';
const API_KEY = 'test-btcpay-api-key';
const now = () => Math.floor(Date.now() / 1000);
const newInvoiceId = () => `inv${randomBytes(8).toString('hex')}`;

// invoiceId -> invoice JSON | 'error'
const invoices = new Map();
const hits = new Map();
const hanging = [];
const usedIds = new Set();
let dir;
let mock;
let mockPort;
let srv;
const allLogs = [];

function addInvoice(fields) {
  const id = newInvoiceId();
  usedIds.add(id);
  invoices.set(id, {
    id,
    storeId: STORE,
    status: 'Settled',
    createdTime: now() - 600,
    metadata: { itemCode: 'pro-1d' },
    ...fields,
  });
  return id;
}

async function boot() {
  return startServer({
    BTCPAY_URL: `https://127.0.0.1:${mockPort}`,
    BTCPAY_API_KEY: API_KEY,
    BTCPAY_STORE_ID: STORE,
    NODE_EXTRA_CA_CERTS: join(dir, 'cert.pem'),
  });
}

async function restart() {
  allLogs.push(srv.logs.all);
  await srv.stop();
  srv = await boot();
}

before(async () => {
  if (skip) return;
  dir = mkdtempSync(join(tmpdir(), 'darkp2p-claim-'));
  const gen = spawnSync('openssl', [
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
    '-keyout', join(dir, 'key.pem'), '-out', join(dir, 'cert.pem'),
    '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
  ], { encoding: 'utf8' });
  assert.equal(gen.status, 0, gen.stderr);

  mock = createServer(
    { key: readFileSync(join(dir, 'key.pem')), cert: readFileSync(join(dir, 'cert.pem')) },
    (req, res) => {
      const m = /^\/api\/v1\/stores\/([^/]+)\/invoices\/([^/?]+)$/.exec(req.url);
      if (!m || m[1] !== STORE || req.headers.authorization !== `token ${API_KEY}`) {
        res.writeHead(401).end();
        return;
      }
      const id = decodeURIComponent(m[2]);
      hits.set(id, (hits.get(id) || 0) + 1);
      const inv = invoices.get(id);
      if (inv === 'hang') {
        hanging.push(res); // never answered; closed in after()
        return;
      }
      if (inv === 'error') {
        res.writeHead(500, { 'Content-Type': 'text/plain' }).end(`boom for ${id}`);
      } else if (!inv) {
        res.writeHead(404).end();
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(inv));
      }
    }
  );
  mock.listen(0, '127.0.0.1');
  await once(mock, 'listening');
  mockPort = mock.address().port;
  srv = await boot();
});

after(async () => {
  if (srv) {
    allLogs.push(srv.logs.all);
    await srv.stop();
  }
  for (const res of hanging) res.destroy();
  if (mock) mock.close();
  if (dir) rmSync(dir, { recursive: true, force: true });
});

function webhook(body) {
  const raw = JSON.stringify(body);
  const sig = 'sha256=' + createHmac('sha256', TEST_BTCPAY_WEBHOOK_SECRET).update(raw).digest('hex');
  return fetch(`http://127.0.0.1:${srv.port}/webhook/btcpay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'BTCPay-Sig': sig },
    body: raw,
  });
}

async function claim(invoiceId) {
  const res = await fetch(`http://127.0.0.1:${srv.port}/api/claim?invoiceId=${encodeURIComponent(invoiceId)}`);
  return { status: res.status, retryAfter: res.headers.get('retry-after'), body: await res.json() };
}

function expectedToken(createdTime, days) {
  const exp = String(createdTime + days * 86400 + 6 * 3600);
  return `${exp}.${createHmac('sha256', TEST_PRO_TOKEN_SECRET).update(exp).digest('hex')}`;
}

let settledId;
let tokenT;

test('signed webhook then claim returns a deterministic token T', { skip }, async () => {
  settledId = addInvoice({});
  const res = await webhook({ type: 'InvoiceSettled', invoiceId: settledId, storeId: STORE, timestamp: now() });
  assert.equal(res.status, 200);
  assert.equal(await res.text(), 'ok');

  const c = await claim(settledId);
  assert.equal(c.status, 200);
  assert.equal(c.body.ready, true);
  tokenT = c.body.token;
  assert.equal(tokenT, expectedToken(invoices.get(settledId).createdTime, 1));
});

test('after a restart, claiming again returns exactly T', { skip }, async () => {
  await restart();
  const before = hits.get(settledId);
  const c = await claim(settledId);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, tokenT);
  assert.equal(hits.get(settledId), before + 1, 'token was re-derived from BTCPay');
  // Now cached: no further lookups.
  assert.equal((await claim(settledId)).body.token, tokenT);
  assert.equal(hits.get(settledId), before + 1);
});

test('claim without any webhook mints from BTCPay (restart before delivery)', { skip }, async () => {
  const id = addInvoice({ metadata: { itemCode: 'pro-30d' } });
  const c = await claim(id);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, expectedToken(invoices.get(id).createdTime, 30));
});

test('status Processing returns 404 and is negatively cached', { skip }, async () => {
  const id = addInvoice({ status: 'Processing' });
  assert.equal((await claim(id)).status, 404);
  assert.equal((await claim(id)).status, 404);
  assert.equal(hits.get(id), 1, 'second claim within 30 s must not hit BTCPay');
});

test('unknown itemCode returns 404', { skip }, async () => {
  const id = addInvoice({ metadata: { itemCode: 'pro-forever' } });
  assert.equal((await claim(id)).status, 404);
});

test('other store, missing invoice and long-expired invoice return 404', { skip }, async () => {
  assert.equal((await claim(addInvoice({ storeId: 'someoneelse' }))).status, 404);
  const missing = newInvoiceId();
  usedIds.add(missing);
  assert.equal((await claim(missing)).status, 404);
  assert.equal((await claim(addInvoice({ createdTime: now() - 3 * 86400 }))).status, 404);
});

test('claim window: no minting once createdTime + 6h + CLAIM_TTL has passed', { skip }, async () => {
  // 30-day token still valid, but the 48 h claim window (+6 h grace) is over.
  const late = addInvoice({ createdTime: now() - (54 * 3600 + 120), metadata: { itemCode: 'pro-30d' } });
  assert.equal((await claim(late)).status, 404);
  // Same, just inside the window.
  const inside = addInvoice({ createdTime: now() - (54 * 3600 - 120), metadata: { itemCode: 'pro-30d' } });
  const ok = await claim(inside);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.token, expectedToken(invoices.get(inside).createdTime, 30));
});

test('after a restart, an invoice past its claim window cannot be re-fetched', { skip }, async () => {
  const id = addInvoice({ metadata: { itemCode: 'pro-30d' } });
  const first = await claim(id);
  assert.equal(first.status, 200);
  // Time passes beyond the window (simulated by the invoice's age), then restart.
  invoices.get(id).createdTime = now() - 55 * 3600;
  await restart();
  assert.equal((await claim(id)).status, 404);
});

test('webhook for an invoice past its claim window mints nothing', { skip }, async () => {
  const id = addInvoice({ createdTime: now() - 55 * 3600, metadata: { itemCode: 'pro-30d' } });
  const res = await webhook({ type: 'InvoiceSettled', invoiceId: id, storeId: STORE, timestamp: now() });
  assert.equal(res.status, 200);
  assert.equal(await res.text(), 'ignored');
  assert.equal((await claim(id)).status, 404);
});

test('BTCPay 500 gives 503 with Retry-After, and is not negatively cached', { skip }, async () => {
  const id = newInvoiceId();
  usedIds.add(id);
  invoices.set(id, 'error');
  const c = await claim(id);
  assert.equal(c.status, 503);
  assert.equal(c.body.ready, false);
  assert.equal(c.retryAfter, '30');

  invoices.set(id, { id, storeId: STORE, status: 'Settled', createdTime: now() - 60, metadata: { itemCode: 'pro-1d' } });
  const ok = await claim(id);
  assert.equal(ok.status, 200);
});

test('webhook with a stale timestamp is ignored', { skip }, async () => {
  const id = addInvoice({});
  const before = hits.get(id) || 0;
  const res = await webhook({
    type: 'InvoiceSettled',
    invoiceId: id,
    storeId: STORE,
    timestamp: now() - 31 * 86400,
  });
  assert.equal(res.status, 200);
  assert.equal(await res.text(), 'ignored');
  assert.equal(hits.get(id) || 0, before, 'stale webhook must not trigger a lookup');
});

test('webhook lookup failure returns 502 so BTCPay retries', { skip }, async () => {
  const id = newInvoiceId();
  usedIds.add(id);
  invoices.set(id, 'error');
  const res = await webhook({ type: 'InvoiceSettled', invoiceId: id, storeId: STORE, timestamp: now() });
  assert.equal(res.status, 502);
  await res.arrayBuffer();
});

test('webhook lookup that hangs times out (5 s) and returns 502', { skip }, async () => {
  const id = newInvoiceId();
  usedIds.add(id);
  invoices.set(id, 'hang');
  const t0 = Date.now();
  const res = await webhook({ type: 'InvoiceSettled', invoiceId: id, storeId: STORE, timestamp: now() });
  const elapsed = Date.now() - t0;
  assert.equal(res.status, 502);
  await res.arrayBuffer();
  assert.ok(elapsed >= 4500 && elapsed < 8000, `took ${elapsed} ms`);
  assert.match(srv.logs.all, /BTCPay invoice lookup failed \(name=TimeoutError/);
});

test('logs contain no invoice ID, token or lookup URL', { skip }, async () => {
  await new Promise((r) => setTimeout(r, 100));
  const logs = allLogs.join('') + srv.logs.all;
  assert.match(logs, /BTCPay invoice lookup failed \(name=BtcPayError code=E_BTCPAY_STATUS status=500\)/);
  for (const id of usedIds) assert.ok(!logs.includes(id), 'invoice ID in logs');
  assert.ok(!logs.includes(tokenT), 'token in logs');
  assert.ok(!logs.includes(tokenT.split('.')[1]), 'token signature in logs');
  assert.ok(!logs.includes('/invoices/'), 'lookup URL in logs');
  assert.ok(!logs.includes('127.0.0.1'), 'address in logs');
});
