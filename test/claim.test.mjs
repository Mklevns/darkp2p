// NOWPayments checkout, IPN and Pro token claims that survive restarts via
// deterministic re-minting from GET /v1/payment/{id}.
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
  finishedIpn,
  postIpn,
  TEST_PRO_TOKEN_SECRET,
  TEST_NOWPAYMENTS_API_KEY,
} from './helpers/server.mjs';

const hasOpenssl = spawnSync('openssl', ['version']).status === 0;
const skip = hasOpenssl ? false : 'openssl not available';

const now = () => Math.floor(Date.now() / 1000);
const iso = (s) => new Date(s * 1000).toISOString();
let nextId = 6000000000 + Math.floor(Math.random() * 1e8);
const newId = () => String(nextId++);
const orderId = (tier) => `${tier}:${randomBytes(16).toString('hex')}`;

// paymentId -> payment JSON | 'error' | 'hang'
const payments = new Map();
const hits = new Map();
const hanging = [];
const invoicesCreated = []; // POST /v1/invoice bodies
let invoiceMode = 'ok'; // 'ok' | 'error' | 'http-url'
const sensitive = new Set();
let dir;
let mock;
let mockPort;
let srv;
const allLogs = [];

function addPayment(fields = {}) {
  const paymentId = newId();
  const invoiceId = newId();
  const p = {
    payment_id: Number(paymentId),
    invoice_id: Number(invoiceId),
    payment_status: 'finished',
    order_id: orderId('pro-1d'),
    price_amount: 2,
    price_currency: 'usd',
    pay_amount: 0.01,
    actually_paid: 0.01,
    pay_currency: 'xmr',
    created_at: iso(now() - 600),
    updated_at: iso(now()),
    ...fields,
  };
  payments.set(paymentId, p);
  sensitive.add(paymentId).add(invoiceId).add(p.order_id);
  return paymentId;
}

async function boot(extra = {}) {
  return startServer({
    NOWPAYMENTS_API_URL: `https://127.0.0.1:${mockPort}`,
    NODE_EXTRA_CA_CERTS: join(dir, 'cert.pem'),
    ...extra,
  });
}

async function restart() {
  allLogs.push(srv.logs.all);
  await srv.stop();
  srv = await boot();
}

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
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
    async (req, res) => {
      if (req.headers['x-api-key'] !== TEST_NOWPAYMENTS_API_KEY) return json(res, 403, { message: 'bad key' });
      if (req.method === 'POST' && req.url === '/v1/invoice') {
        let raw = '';
        for await (const chunk of req) raw += chunk;
        const body = JSON.parse(raw);
        invoicesCreated.push(body);
        sensitive.add(body.order_id);
        if (invoiceMode === 'error') return json(res, 500, { message: 'boom' });
        const id = newId();
        sensitive.add(id);
        const scheme = invoiceMode === 'http-url' ? 'http' : 'https';
        return json(res, 200, {
          id,
          order_id: body.order_id,
          price_amount: String(body.price_amount),
          price_currency: body.price_currency,
          invoice_url: `${scheme}://nowpayments.test/payment/?iid=${id}`,
          created_at: new Date().toISOString(),
        });
      }
      const m = /^\/v1\/payment\/([^/?]+)$/.exec(req.url);
      if (req.method !== 'GET' || !m) return json(res, 404, { message: 'not found' });
      const id = decodeURIComponent(m[1]);
      hits.set(id, (hits.get(id) || 0) + 1);
      const p = payments.get(id);
      if (p === 'hang') {
        hanging.push(res); // never answered; closed in after()
        return;
      }
      if (p === 'error') return res.writeHead(500, { 'Content-Type': 'text/plain' }).end(`boom for ${id}`);
      if (!p) return json(res, 404, { message: `payment ${id} not found` });
      return json(res, 200, p);
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

async function claim(query) {
  const res = await fetch(`http://127.0.0.1:${srv.port}/api/claim?${query}`);
  return { status: res.status, retryAfter: res.headers.get('retry-after'), body: await res.json() };
}

function checkout(body, headers = { 'Content-Type': 'application/json' }) {
  return fetch(`http://127.0.0.1:${srv.port}/api/checkout`, {
    method: 'POST',
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

function expectedToken(createdS, days) {
  const exp = String(createdS + days * 86400 + 6 * 3600);
  return `${exp}.${createHmac('sha256', TEST_PRO_TOKEN_SECRET).update(exp).digest('hex')}`;
}

const createdS = (paymentId) => Math.floor(Date.parse(payments.get(paymentId).created_at) / 1000);

// --- checkout -------------------------------------------------------------

test('checkout creates a NOWPayments invoice for the tier and returns its URL', { skip }, async () => {
  for (const [tier, price] of [['pro-1d', 2], ['pro-30d', 5]]) {
    const res = await checkout({ tier });
    assert.equal(res.status, 200);
    const { invoiceUrl } = await res.json();
    assert.match(invoiceUrl, /^https:\/\/nowpayments\.test\/payment\/\?iid=\d+$/);
    const sent = invoicesCreated.at(-1);
    assert.equal(sent.price_amount, price);
    assert.equal(sent.price_currency, 'usd');
    assert.match(sent.order_id, new RegExp(`^${tier}:[a-f0-9]{32}$`));
    assert.equal(sent.ipn_callback_url, 'https://test.local/webhook/nowpayments');
    assert.equal(sent.success_url, 'https://test.local/');
  }
  assert.notEqual(invoicesCreated[0].order_id, invoicesCreated[1].order_id);
});

test('checkout rejects bad tiers, non-JSON, oversized bodies and bad upstream answers', { skip }, async () => {
  const before = invoicesCreated.length;
  assert.equal((await checkout({ tier: 'pro-forever' })).status, 400);
  assert.equal((await checkout('not json')).status, 400);
  assert.equal((await checkout({ tier: 'pro-1d' }, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await checkout({ tier: 'pro-1d', pad: 'x'.repeat(5000) })).status, 413);
  assert.equal(invoicesCreated.length, before, 'no invoice for a rejected request');

  invoiceMode = 'error';
  const failed = await checkout({ tier: 'pro-1d' });
  assert.equal(failed.status, 502);
  assert.deepEqual(await failed.json(), { error: 'checkout unavailable' });
  invoiceMode = 'http-url';
  assert.equal((await checkout({ tier: 'pro-1d' })).status, 502, 'a non-https invoice URL is refused');
  invoiceMode = 'ok';
});

test('checkout is rate-limited per client (10 per minute)', { skip }, async () => {
  const fresh = await boot();
  try {
    const codes = [];
    for (let i = 0; i < 11; i++) {
      const res = await fetch(`http://127.0.0.1:${fresh.port}/api/checkout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tier: 'pro-1d' }),
      });
      codes.push(res.status);
      await res.arrayBuffer();
    }
    assert.deepEqual(codes.slice(0, 10), new Array(10).fill(200));
    assert.equal(codes[10], 429);
  } finally {
    allLogs.push(fresh.logs.all);
    await fresh.stop();
  }
});

test('checkout is disabled without NOWPAYMENTS_API_KEY', async () => {
  const off = await startServer({ NOWPAYMENTS_API_KEY: undefined, NOWPAYMENTS_IPN_SECRET: undefined });
  try {
    const res = await fetch(`http://127.0.0.1:${off.port}/api/checkout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{"tier":"pro-1d"}',
    });
    assert.equal(res.status, 404);
    await res.arrayBuffer();
  } finally {
    await off.stop();
  }
});

// --- IPN and claims -------------------------------------------------------

let settledId;
let tokenT;

test('signed finished IPN then claim returns a deterministic token T', { skip }, async () => {
  settledId = addPayment({});
  const res = await postIpn(srv.port, payments.get(settledId));
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
  assert.equal(hits.get(settledId) || 0, 0, 'an IPN with created_at needs no lookup');

  const c = await claim(`paymentId=${settledId}`);
  assert.equal(c.status, 200);
  assert.equal(c.body.ready, true);
  tokenT = c.body.token;
  assert.equal(tokenT, expectedToken(createdS(settledId), 1));
  // The invoice ID is an alias for the same record.
  const byInvoice = await claim(`invoiceId=${payments.get(settledId).invoice_id}`);
  assert.equal(byInvoice.body.token, tokenT);
});

test('the IPN signature covers recursively sorted keys; anything else is 401', { skip }, async () => {
  const p = finishedIpn({ order_id: orderId('pro-1d') });
  sensitive.add(String(p.payment_id)).add(String(p.invoice_id)).add(p.order_id);
  // Tampered amount, wrong secret, missing and malformed headers.
  const forged = { ...p, price_amount: 200 };
  const sigOfOriginal = (await import('./helpers/server.mjs')).signIpn(p);
  assert.equal((await postIpn(srv.port, forged, { sig: sigOfOriginal })).status, 401);
  assert.equal((await postIpn(srv.port, p, { secret: 'wrong-secret' })).status, 401);
  assert.equal((await postIpn(srv.port, p, { sig: 'zz' })).status, 401);
  // A signature over the unsorted serialization (key order as sent) is wrong.
  const unsorted = createHmac('sha512', 'test-nowpayments-ipn-secret-0123456789ab')
    .update(JSON.stringify(p)).digest('hex');
  assert.equal((await postIpn(srv.port, p, { sig: unsorted })).status, 401);
  // The real one, with the nested fee object sorted too, is accepted.
  const ok = await postIpn(srv.port, p);
  assert.equal(ok.status, 200);
  await ok.arrayBuffer();
});

test('after a restart, claiming by payment ID returns exactly T', { skip }, async () => {
  await restart();
  const c = await claim(`paymentId=${settledId}`);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, tokenT);
  assert.equal(hits.get(settledId), 1, 'token was re-derived from GET /v1/payment');
  // Now cached: no further lookups, and the invoice alias is known again.
  assert.equal((await claim(`paymentId=${settledId}`)).body.token, tokenT);
  assert.equal((await claim(`invoiceId=${payments.get(settledId).invoice_id}`)).body.token, tokenT);
  assert.equal(hits.get(settledId), 1);
});

test('after a restart, an unknown invoice ID is 404 (no lookup by invoice)', { skip }, async () => {
  const id = addPayment({});
  await restart();
  assert.equal((await claim(`invoiceId=${payments.get(id).invoice_id}`)).status, 404);
  assert.equal(hits.get(id) || 0, 0);
});

test('claim without any IPN mints from NOWPayments (restart before delivery)', { skip }, async () => {
  const id = addPayment({ order_id: orderId('pro-30d'), price_amount: 5 });
  const c = await claim(`paymentId=${id}`);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, expectedToken(createdS(id), 30));
});

test('non-finished statuses are acknowledged as ignored and mint nothing', { skip }, async () => {
  for (const status of ['waiting', 'confirming', 'confirmed', 'sending', 'partially_paid', 'failed', 'expired']) {
    const id = addPayment({ payment_status: status });
    const res = await postIpn(srv.port, payments.get(id));
    assert.equal(res.status, 200, status);
    assert.deepEqual(await res.json(), { ok: true, ignored: true }, status);
    assert.equal(hits.get(id) || 0, 0, `${status} must not trigger a lookup`);
    // The claim falls through to a lookup, which says it is not finished.
    assert.equal((await claim(`paymentId=${id}`)).status, 404, status);
  }
});

test('a lookup that is not finished yet returns 404 and is negatively cached', { skip }, async () => {
  const id = addPayment({ payment_status: 'confirming' });
  assert.equal((await claim(`paymentId=${id}`)).status, 404);
  assert.equal((await claim(`paymentId=${id}`)).status, 404);
  assert.equal(hits.get(id), 1, 'second claim within 30 s must not hit NOWPayments');
});

test('foreign orders, underpayment and wrong currency mint nothing', { skip }, async () => {
  const cases = [
    addPayment({ order_id: 'shop-order-1234' }),
    addPayment({ order_id: orderId('pro-forever') }),
    addPayment({ order_id: orderId('pro-30d'), price_amount: 2 }), // a 30d order paid at the 1d price
    addPayment({ price_currency: 'eur' }),
  ];
  for (const id of cases) {
    const res = await postIpn(srv.port, payments.get(id));
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, ignored: true });
    assert.equal((await claim(`paymentId=${id}`)).status, 404);
  }
  // Missing payment: 404 from NOWPayments.
  const missing = newId();
  sensitive.add(missing);
  assert.equal((await claim(`paymentId=${missing}`)).status, 404);
  // Malformed IDs never reach NOWPayments.
  for (const bad of ['abc', '0123', '1'.repeat(21), '12;34']) {
    assert.equal((await claim(`paymentId=${encodeURIComponent(bad)}`)).status, 404);
  }
});

test('claim window: no minting once created_at + 6h + CLAIM_TTL has passed', { skip }, async () => {
  // 30-day token still valid, but the 48 h claim window (+6 h grace) is over.
  const late = addPayment({ order_id: orderId('pro-30d'), price_amount: 5, created_at: iso(now() - (54 * 3600 + 120)) });
  assert.equal((await claim(`paymentId=${late}`)).status, 404);
  const inside = addPayment({ order_id: orderId('pro-30d'), price_amount: 5, created_at: iso(now() - (54 * 3600 - 120)) });
  const ok = await claim(`paymentId=${inside}`);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.token, expectedToken(createdS(inside), 30));
  // A 1-day token from 3 days ago has expired.
  assert.equal((await claim(`paymentId=${addPayment({ created_at: iso(now() - 3 * 86400) })}`)).status, 404);
});

test('after a restart, a payment past its claim window cannot be re-fetched', { skip }, async () => {
  const id = addPayment({ order_id: orderId('pro-30d'), price_amount: 5 });
  assert.equal((await claim(`paymentId=${id}`)).status, 200);
  payments.get(id).created_at = iso(now() - 55 * 3600);
  await restart();
  assert.equal((await claim(`paymentId=${id}`)).status, 404);
});

test('IPN for a payment past its claim window mints nothing', { skip }, async () => {
  const id = addPayment({ order_id: orderId('pro-30d'), price_amount: 5, created_at: iso(now() - 55 * 3600) });
  const res = await postIpn(srv.port, payments.get(id));
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, ignored: true });
});

test('NOWPayments 500 on a claim gives 503 with Retry-After, and is not negatively cached', { skip }, async () => {
  const id = newId();
  sensitive.add(id);
  payments.set(id, 'error');
  const c = await claim(`paymentId=${id}`);
  assert.equal(c.status, 503);
  assert.equal(c.body.ready, false);
  assert.equal(c.retryAfter, '30');

  payments.set(id, { payment_id: Number(id), payment_status: 'finished', order_id: orderId('pro-1d'),
    price_amount: 2, price_currency: 'usd', created_at: iso(now() - 60) });
  assert.equal((await claim(`paymentId=${id}`)).status, 200);
});

test('IPN without created_at is resolved by lookup; a failed lookup returns 502', { skip }, async () => {
  const id = addPayment({});
  const ipn = { ...payments.get(id) };
  delete ipn.created_at;
  const res = await postIpn(srv.port, ipn);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
  assert.equal(hits.get(id), 1);
  assert.equal((await claim(`paymentId=${id}`)).body.token, expectedToken(createdS(id), 1));

  const broken = addPayment({});
  const ipn2 = { ...payments.get(broken) };
  delete ipn2.created_at;
  payments.set(broken, 'error');
  const failed = await postIpn(srv.port, ipn2);
  assert.equal(failed.status, 502, 'NOWPayments retries a non-2xx IPN');
  await failed.arrayBuffer();
});

test('a lookup that hangs times out (5 s) and returns 502 to the IPN', { skip }, async () => {
  const id = addPayment({});
  const ipn = { ...payments.get(id) };
  delete ipn.created_at;
  payments.set(id, 'hang');
  const t0 = Date.now();
  const res = await postIpn(srv.port, ipn);
  const elapsed = Date.now() - t0;
  assert.equal(res.status, 502);
  await res.arrayBuffer();
  assert.ok(elapsed >= 4500 && elapsed < 8000, `took ${elapsed} ms`);
  assert.match(srv.logs.all, /NOWPayments payment lookup failed \(name=TimeoutError/);
});

test('logs contain no payment/invoice/order ID, token, API key or lookup URL', { skip }, async () => {
  await new Promise((r) => setTimeout(r, 100));
  const logs = allLogs.join('') + srv.logs.all;
  assert.match(logs, /NOWPayments payment lookup failed \(name=NowPaymentsError code=E_NP_STATUS status=500\)/);
  assert.match(logs, /NOWPayments checkout failed \(name=NowPaymentsError code=E_NP_STATUS status=500\)/);
  assert.ok(sensitive.size > 20, 'tests recorded the IDs to look for');
  for (const id of sensitive) assert.ok(!logs.includes(id), 'payment, invoice or order ID in logs');
  assert.ok(!logs.includes(tokenT), 'token in logs');
  assert.ok(!logs.includes(tokenT.split('.')[1]), 'token signature in logs');
  assert.ok(!logs.includes(TEST_NOWPAYMENTS_API_KEY), 'API key in logs');
  assert.ok(!logs.includes('/v1/payment'), 'lookup URL in logs');
  assert.ok(!logs.includes('nowpayments.test'), 'invoice URL in logs');
  assert.ok(!logs.includes('127.0.0.1'), 'address in logs');
});
