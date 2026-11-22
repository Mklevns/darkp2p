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

// Each claim comes from its own client address (the server trusts
// X-Forwarded-For from loopback), so the per-client limit of 30 a minute does
// not depend on how many claims the tests around it make.
let claimClient = 0;
async function claim(query, server = srv) {
  claimClient += 1;
  const res = await fetch(`http://127.0.0.1:${server.port}/api/claim?${query}`, {
    headers: { 'X-Forwarded-For': `10.77.${claimClient >> 8}.${claimClient & 255}` },
  });
  return { status: res.status, retryAfter: res.headers.get('retry-after'), body: await res.json() };
}

// A claim as the buyer makes it: the payment ID with the order ID from checkout.
const orderOf = (paymentId) => payments.get(paymentId).order_id;
const claimP = (paymentId, order = orderOf(paymentId), server = srv) =>
  claim(`paymentId=${paymentId}&orderId=${encodeURIComponent(order)}`, server);
const claimI = (paymentId, order = orderOf(paymentId)) =>
  claim(`invoiceId=${payments.get(paymentId).invoice_id}&orderId=${encodeURIComponent(order)}`);

function checkout(body, headers = { 'Content-Type': 'application/json' }) {
  return fetch(`http://127.0.0.1:${srv.port}/api/checkout`, {
    method: 'POST',
    headers,
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

// exp = settlement time (updated_at) + tier days.
function expectedToken(anchorS, days) {
  const exp = String(anchorS + days * 86400);
  return `${exp}.${createHmac('sha256', TEST_PRO_TOKEN_SECRET).update(exp).digest('hex')}`;
}

const createdS = (paymentId) => Math.floor(Date.parse(payments.get(paymentId).created_at) / 1000);
const settledS = (paymentId) => Math.floor(Date.parse(payments.get(paymentId).updated_at) / 1000);

// --- checkout -------------------------------------------------------------

test('checkout creates a NOWPayments invoice for the tier and returns its URL', { skip }, async () => {
  for (const [tier, price] of [['pro-1d', 2], ['pro-30d', 5]]) {
    const res = await checkout({ tier });
    assert.equal(res.status, 200);
    const { invoiceUrl, invoiceId, orderId: returnedOrder } = await res.json();
    assert.match(invoiceUrl, /^https:\/\/nowpayments\.test\/payment\/\?iid=\d+$/);
    const sent = invoicesCreated.at(-1);
    // The page keeps both for its pending claim: the invoice ID to poll with
    // before the buyer returns, and the order ID every claim must present.
    assert.equal(invoiceUrl.split('iid=')[1], invoiceId);
    assert.equal(returnedOrder, sent.order_id);
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

  const c = await claimP(settledId);
  assert.equal(c.status, 200);
  assert.equal(c.body.ready, true);
  tokenT = c.body.token;
  assert.equal(tokenT, expectedToken(settledS(settledId), 1));
  // The invoice ID is an alias for the same record.
  assert.equal((await claimI(settledId)).body.token, tokenT);
});

test('a payment or invoice ID without the matching order ID never returns the token', { skip }, async () => {
  const inv = payments.get(settledId).invoice_id;
  const other = orderId('pro-1d');
  for (const query of [
    `paymentId=${settledId}`,
    `invoiceId=${inv}`,
    `paymentId=${settledId}&orderId=`,
    `paymentId=${settledId}&orderId=${encodeURIComponent(other)}`,
    `invoiceId=${inv}&orderId=${encodeURIComponent(other)}`,
    `paymentId=${settledId}&orderId=${encodeURIComponent(orderOf(settledId).toUpperCase())}`,
  ]) {
    const c = await claim(query);
    assert.equal(c.status, 404, query);
    assert.deepEqual(c.body, { ready: false }, query);
  }
  assert.equal(hits.get(settledId) || 0, 0, 'a recorded payment is never looked up');

  // The same holds when the answer comes from a NOWPayments lookup, and a
  // wrong guess does not make the buyer's own claim miss.
  const id = addPayment({});
  assert.equal((await claimP(id, other)).status, 404);
  assert.equal(hits.get(id), 1);
  assert.equal((await claimP(id, other)).status, 404);
  assert.equal(hits.get(id), 1, 'the wrong pair is negatively cached');
  const mine = await claimP(id);
  assert.equal(mine.status, 200);
  assert.equal(mine.body.token, expectedToken(settledS(id), 1));
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
  const c = await claimP(settledId);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, tokenT);
  assert.equal(hits.get(settledId), 1, 'token was re-derived from GET /v1/payment');
  // Now cached: no further lookups, and the invoice alias is known again.
  assert.equal((await claimP(settledId)).body.token, tokenT);
  assert.equal((await claimI(settledId)).body.token, tokenT);
  assert.equal(hits.get(settledId), 1);
});

test('after a restart, an unknown invoice ID is 404 (no lookup by invoice)', { skip }, async () => {
  const id = addPayment({});
  await restart();
  assert.equal((await claimI(id)).status, 404);
  assert.equal(hits.get(id) || 0, 0);
});

test('claim without any IPN mints from NOWPayments (restart before delivery)', { skip }, async () => {
  const id = addPayment({ order_id: orderId('pro-30d'), price_amount: 5 });
  const c = await claimP(id);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, expectedToken(settledS(id), 30));
});

test('non-finished statuses are acknowledged as ignored and mint nothing', { skip }, async () => {
  for (const status of ['waiting', 'confirming', 'confirmed', 'sending', 'failed', 'expired']) {
    const id = addPayment({ payment_status: status });
    const res = await postIpn(srv.port, payments.get(id));
    assert.equal(res.status, 200, status);
    assert.deepEqual(await res.json(), { ok: true, ignored: true }, status);
    assert.equal(hits.get(id) || 0, 0, `${status} must not trigger a lookup`);
    // The claim falls through to a lookup, which says it is not finished.
    const c = await claimP(id);
    assert.equal(c.status, 404, status);
    assert.deepEqual(c.body, { ready: false }, status);
  }
});

test('partially_paid is reported to the buyer, mints nothing, and a later finished IPN still mints', { skip }, async () => {
  const id = addPayment({ payment_status: 'partially_paid' });
  const res = await postIpn(srv.port, payments.get(id));
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, ignored: true });

  // Answered from the IPN record, by either ID, without asking NOWPayments.
  for (const c of [await claimP(id), await claimI(id)]) {
    assert.equal(c.status, 200);
    assert.deepEqual(c.body, { ready: false, status: 'partially_paid' });
  }
  assert.equal(hits.get(id) || 0, 0);
  // Not to someone without the order ID.
  assert.deepEqual((await claimI(id, orderId('pro-1d'))).body, { ready: false });

  // The buyer completes the payment: the finished IPN mints as usual.
  payments.get(id).payment_status = 'finished';
  payments.get(id).updated_at = iso(now());
  const done = await postIpn(srv.port, payments.get(id));
  assert.deepEqual(await done.json(), { ok: true });
  const c = await claimI(id);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, expectedToken(settledS(id), 1));
});

test('partially_paid found by lookup (no IPN) is reported and not looked up again at once', { skip }, async () => {
  const id = addPayment({ payment_status: 'partially_paid' });
  for (let i = 0; i < 2; i++) {
    const c = await claimP(id);
    assert.equal(c.status, 200);
    assert.deepEqual(c.body, { ready: false, status: 'partially_paid' });
  }
  assert.equal(hits.get(id), 1);
  // A partially paid payment of someone else's order says nothing.
  const foreign = addPayment({ payment_status: 'partially_paid' });
  assert.deepEqual((await claimP(foreign, orderId('pro-1d'))).body, { ready: false });
});

test('a lookup that is not finished yet returns 404 and is negatively cached', { skip }, async () => {
  const id = addPayment({ payment_status: 'confirming' });
  assert.equal((await claimP(id)).status, 404);
  assert.equal((await claimP(id)).status, 404);
  assert.equal(hits.get(id), 1, 'second claim within 30 s must not hit NOWPayments');
});

test('foreign orders, underpayment and wrong currency mint nothing', { skip }, async () => {
  const cases = [
    [addPayment({ order_id: 'shop-order-1234' }), orderId('pro-1d')],
    [addPayment({ order_id: orderId('pro-forever') }), orderId('pro-1d')],
    [addPayment({ order_id: orderId('pro-30d'), price_amount: 2 })], // a 30d order paid at the 1d price
    [addPayment({ price_currency: 'eur' })],
  ];
  for (const [id, order] of cases) {
    const res = await postIpn(srv.port, payments.get(id));
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, ignored: true });
    assert.equal((await claimP(id, order)).status, 404);
  }
  // Missing payment: 404 from NOWPayments.
  const missing = newId();
  sensitive.add(missing);
  assert.equal((await claimP(missing, orderId('pro-1d'))).status, 404);
  assert.equal(hits.get(missing), 1);
  // Malformed IDs never reach NOWPayments.
  const before = [...hits.values()].reduce((a, b) => a + b, 0);
  for (const bad of ['abc', '0123', '1'.repeat(21), '12;34']) {
    assert.equal((await claim(`paymentId=${encodeURIComponent(bad)}&orderId=${orderId('pro-1d')}`)).status, 404);
  }
  for (const badOrder of ['pro-1d:xyz', 'pro-9d:' + 'a'.repeat(32), 'pro-1d:' + 'a'.repeat(31), '']) {
    assert.equal((await claim(`paymentId=${missing}&orderId=${encodeURIComponent(badOrder)}`)).status, 404);
  }
  assert.equal([...hits.values()].reduce((a, b) => a + b, 0), before);
});

test('token lifetime runs from settlement (updated_at), not from created_at', { skip }, async () => {
  // A 1-day pass that took 40 h to settle: created_at + 1 d + 6 h is long
  // past, but the buyer still gets a full day from settlement.
  const slow = addPayment({ created_at: iso(now() - 40 * 3600), updated_at: iso(now() - 600) });
  const ipnRes = await postIpn(srv.port, payments.get(slow));
  assert.deepEqual(await ipnRes.json(), { ok: true });
  const c = await claimP(slow);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, expectedToken(settledS(slow), 1));
  assert.ok(Number(c.body.token.split('.')[0]) > now() + 23 * 3600);

  // The same through a lookup, with updated_at as a millisecond number.
  const viaLookup = addPayment({ created_at: iso(now() - 40 * 3600), updated_at: (now() - 300) * 1000 });
  const l = await claimP(viaLookup);
  assert.equal(l.status, 200);
  assert.equal(l.body.token, expectedToken(now() - 300, 1));

  // No updated_at at all: created_at + 6 h stands in for the settlement time.
  const bare = addPayment({ created_at: iso(now() - 3600) });
  delete payments.get(bare).updated_at;
  assert.equal((await claimP(bare)).body.token, expectedToken(createdS(bare) + 6 * 3600, 1));

  // updated_at before created_at counts as created_at.
  const skewed = addPayment({ created_at: iso(now() - 600), updated_at: iso(now() - 3600) });
  assert.equal((await claimP(skewed)).body.token, expectedToken(createdS(skewed), 1));
});

test('settlement time is capped at created_at + 7 d, so a late update cannot extend a token', { skip }, async () => {
  // Updated just now, 8 days after creation: anchored at day 7 (1 day ago).
  const capped = addPayment({ order_id: orderId('pro-30d'), price_amount: 5,
    created_at: iso(now() - 8 * 86400), updated_at: iso(now()) });
  const c = await claimP(capped);
  assert.equal(c.status, 200);
  assert.equal(c.body.token, expectedToken(createdS(capped) + 7 * 86400, 30));
  // 10 days after creation the capped anchor is 3 days old: past the 48 h claim window.
  const tooLate = addPayment({ order_id: orderId('pro-30d'), price_amount: 5,
    created_at: iso(now() - 10 * 86400), updated_at: iso(now()) });
  assert.equal((await claimP(tooLate)).status, 404);
});

test('claim window: no minting once settlement + CLAIM_TTL has passed', { skip }, async () => {
  // 30-day token still valid, but the 48 h claim window is over.
  const late = addPayment({ order_id: orderId('pro-30d'), price_amount: 5,
    created_at: iso(now() - 60 * 3600), updated_at: iso(now() - (48 * 3600 + 120)) });
  assert.equal((await claimP(late)).status, 404);
  const inside = addPayment({ order_id: orderId('pro-30d'), price_amount: 5,
    created_at: iso(now() - 60 * 3600), updated_at: iso(now() - (48 * 3600 - 120)) });
  const ok = await claimP(inside);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.token, expectedToken(settledS(inside), 30));
  // Without updated_at the window runs from created_at + 6 h, as before.
  const lateBare = addPayment({ order_id: orderId('pro-30d'), price_amount: 5, created_at: iso(now() - (54 * 3600 + 120)) });
  delete payments.get(lateBare).updated_at;
  assert.equal((await claimP(lateBare)).status, 404);
  const insideBare = addPayment({ order_id: orderId('pro-30d'), price_amount: 5, created_at: iso(now() - (54 * 3600 - 120)) });
  delete payments.get(insideBare).updated_at;
  assert.equal((await claimP(insideBare)).status, 200);
  // A 1-day token that settled 30 h ago is inside the window but has expired.
  const expired = addPayment({ created_at: iso(now() - 31 * 3600), updated_at: iso(now() - 30 * 3600) });
  assert.equal((await claimP(expired)).status, 404);
});

test('after a restart, a payment past its claim window cannot be re-fetched', { skip }, async () => {
  const id = addPayment({ order_id: orderId('pro-30d'), price_amount: 5 });
  assert.equal((await claimP(id)).status, 200);
  payments.get(id).created_at = iso(now() - 55 * 3600);
  payments.get(id).updated_at = iso(now() - 49 * 3600);
  await restart();
  assert.equal((await claimP(id)).status, 404);
});

test('IPN for a payment past its claim window mints nothing', { skip }, async () => {
  const id = addPayment({ order_id: orderId('pro-30d'), price_amount: 5,
    created_at: iso(now() - 55 * 3600), updated_at: iso(now() - 49 * 3600) });
  const res = await postIpn(srv.port, payments.get(id));
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, ignored: true });
});

test('NOWPayments 500 on a claim gives 503 with Retry-After, and is not negatively cached', { skip }, async () => {
  const id = newId();
  const order = orderId('pro-1d');
  sensitive.add(id).add(order);
  payments.set(id, 'error');
  const c = await claimP(id, order);
  assert.equal(c.status, 503);
  assert.equal(c.body.ready, false);
  assert.equal(c.retryAfter, '30');

  payments.set(id, { payment_id: Number(id), payment_status: 'finished', order_id: order,
    price_amount: 2, price_currency: 'usd', created_at: iso(now() - 60) });
  assert.equal((await claimP(id)).status, 200);
});

test('IPN without created_at is resolved by lookup; a failed lookup returns 502', { skip }, async () => {
  const id = addPayment({});
  const ipn = { ...payments.get(id) };
  delete ipn.created_at;
  const res = await postIpn(srv.port, ipn);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
  assert.equal(hits.get(id), 1);
  assert.equal((await claimP(id)).body.token, expectedToken(settledS(id), 1));

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

// --- global NOWPayments call budget -----------------------------------------

test('claim lookups share one global budget, whatever address asks', { skip }, async () => {
  const fresh = await boot({ NOWPAYMENTS_MAX_LOOKUPS_PER_MIN: '2' });
  try {
    const ids = [addPayment({}), addPayment({}), addPayment({})];
    // Three different payments from three different client addresses.
    assert.equal((await claimP(ids[0], undefined, fresh)).status, 200);
    assert.equal((await claimP(ids[1], undefined, fresh)).status, 200);
    const refused = await claimP(ids[2], undefined, fresh);
    assert.equal(refused.status, 503);
    assert.deepEqual(refused.body, { ready: false });
    assert.ok(Number(refused.retryAfter) >= 1 && Number(refused.retryAfter) <= 60, `Retry-After ${refused.retryAfter}`);
    assert.equal(hits.get(ids[2]) || 0, 0, 'no call left the server');
    // Recorded tokens need no lookup, so they are still served.
    assert.equal((await claimP(ids[0], undefined, fresh)).status, 200);
    // A signed IPN's own lookup is exempt, and its token is then claimable.
    const viaIpn = addPayment({});
    const ipn = { ...payments.get(viaIpn) };
    delete ipn.created_at;
    const res = await postIpn(fresh.port, ipn);
    assert.deepEqual(await res.json(), { ok: true });
    assert.equal(hits.get(viaIpn), 1);
    assert.equal((await claimP(viaIpn, undefined, fresh)).status, 200);
    // The refusal was not cached as "not found".
    assert.equal((await claimP(ids[2], undefined, fresh)).status, 503);
  } finally {
    allLogs.push(fresh.logs.all);
    await fresh.stop();
  }
});

test('checkouts share one global budget', { skip }, async () => {
  const fresh = await boot({ NOWPAYMENTS_MAX_CHECKOUTS_PER_MIN: '2' });
  try {
    const before = invoicesCreated.length;
    const codes = [];
    let last;
    for (let i = 0; i < 3; i++) {
      last = await fetch(`http://127.0.0.1:${fresh.port}/api/checkout`, {
        method: 'POST',
        // A different client each time: the per-client limit is not what stops it.
        headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': `10.88.0.${i + 1}` },
        body: JSON.stringify({ tier: 'pro-1d' }),
      });
      codes.push(last.status);
      if (i < 2) await last.arrayBuffer();
    }
    assert.deepEqual(codes, [200, 200, 503]);
    assert.ok(Number(last.headers.get('retry-after')) >= 1);
    assert.deepEqual(await last.json(), { error: 'checkout unavailable' });
    assert.equal(invoicesCreated.length, before + 2, 'the refused checkout created no invoice');
  } finally {
    allLogs.push(fresh.logs.all);
    await fresh.stop();
  }
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
  assert.ok(!logs.includes('127.0.0.1') && !/10\.(77|88)\.\d/.test(logs), 'address in logs');
});
