// Baseline behaviour of the current server. Must stay green on unmodified code.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { connect } from 'node:net';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import {
  startServer,
  STATS_LINE_RE,
  TEST_PRO_TOKEN_SECRET,
  TEST_BTCPAY_WEBHOOK_SECRET,
} from './helpers/server.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TURN_ENV = { TURN_HOST: 'turn.test.local', TURN_SECRET: 'test-turn-secret-0123456789abcdefghij' };

// Every sensitive value the tests hand the server; none may appear in its logs.
const secrets = [];
function remember(...values) {
  secrets.push(...values);
  return values[0];
}

let srv;
before(async () => {
  // Short sweep so the periodic stats line is part of the audited logs.
  srv = await startServer({ ...TURN_ENV, SWEEP_MS: '1000' });
});
after(async () => {
  if (srv) await srv.stop();
});

const base = () => `http://127.0.0.1:${srv.port}`;
const roomId = () => remember(randomBytes(16).toString('hex'));

function mintToken(exp, secret = TEST_PRO_TOKEN_SECRET) {
  const e = String(exp);
  return `${e}.${createHmac('sha256', secret).update(e).digest('hex')}`;
}

// Opens a WS with a message queue, so nothing sent before a waiter attaches is lost.
async function openClient(port, origin = 'https://test.local') {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/`, { origin });
  const queue = [];
  const waiters = [];
  ws.on('message', (data, isBinary) => {
    const msg = isBinary ? data : JSON.parse(data.toString('utf8'));
    const w = waiters.shift();
    if (w) w.resolve(msg);
    else queue.push(msg);
  });
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
  return {
    ws,
    send: (obj) => ws.send(JSON.stringify(obj)),
    next(timeoutMs = 3000) {
      if (queue.length) return Promise.resolve(queue.shift());
      return new Promise((resolve, reject) => {
        const entry = { resolve: (m) => { clearTimeout(timer); resolve(m); } };
        const timer = setTimeout(() => {
          const i = waiters.indexOf(entry);
          if (i !== -1) waiters.splice(i, 1);
          reject(new Error('timed out waiting for a WS message'));
        }, timeoutMs);
        waiters.push(entry);
      });
    },
    close: () => ws.close(),
  };
}

async function pair(port, tokens = []) {
  const room = roomId();
  const a = await openClient(port);
  const b = await openClient(port);
  a.send({ type: 'join', room, ...(tokens[0] ? { token: tokens[0] } : {}) });
  b.send({ type: 'join', room, ...(tokens[1] ? { token: tokens[1] } : {}) });
  const [ra, rb] = await Promise.all([a.next(), b.next()]);
  return { room, a, b, ra, rb };
}

test('GET /health returns ok', async () => {
  const res = await fetch(`${base()}/health`);
  assert.equal(res.status, 200);
  assert.equal(await res.text(), 'ok');
});

test('HEAD / returns 200 with no body', async () => {
  const res = await fetch(`${base()}/`, { method: 'HEAD' });
  assert.equal(res.status, 200);
  assert.equal((await res.arrayBuffer()).byteLength, 0);
});

test('CSP on / contains the sha256 of the inline script', async () => {
  const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8');
  const m = /<script>([\s\S]*?)<\/script\s*>/i.exec(html);
  assert.ok(m, 'index.html has a plain inline <script>');
  const hash = createHash('sha256').update(m[1].replace(/\r\n?/g, '\n'), 'utf8').digest('base64');
  const res = await fetch(`${base()}/`);
  await res.arrayBuffer();
  const csp = res.headers.get('content-security-policy');
  assert.ok(csp && csp.includes(`'sha256-${hash}'`), `CSP missing script hash: ${csp}`);
});

test('raw "GET //[ HTTP/1.1" gets 400 and the server survives', async () => {
  const reply = await new Promise((resolve, reject) => {
    const sock = connect(srv.port, '127.0.0.1');
    let buf = '';
    sock.setEncoding('utf8');
    sock.on('data', (d) => { buf += d; });
    sock.on('end', () => resolve(buf));
    sock.on('close', () => resolve(buf));
    sock.on('error', reject);
    sock.write('GET //[ HTTP/1.1\r\nHost: test.local\r\nConnection: close\r\n\r\n');
  });
  assert.match(reply, /^HTTP\/1\.1 400 /);
  assert.ok(srv.isAlive(), 'server process exited');
  const res = await fetch(`${base()}/health`);
  assert.equal(await res.text(), 'ok');
});

test('WS upgrade from a foreign Origin gets 403', async () => {
  const status = await new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${srv.port}/`, { origin: 'https://evil.example' });
    ws.on('unexpected-response', (_req, res) => {
      resolve(res.statusCode);
      res.resume();
      ws.terminate();
    });
    ws.on('open', () => reject(new Error('upgrade unexpectedly accepted')));
    ws.on('error', () => {});
  });
  assert.equal(status, 403);
});

test('two peers get ready with exactly one initiator; a third gets full', async () => {
  const { room, a, b, ra, rb } = await pair(srv.port);
  assert.equal(ra.type, 'ready');
  assert.equal(rb.type, 'ready');
  assert.equal([ra, rb].filter((r) => r.initiator === true).length, 1);

  const c = await openClient(srv.port);
  c.send({ type: 'join', room });
  assert.deepEqual(await c.next(), { type: 'full' });
  a.close();
  b.close();
  c.close();
});

test('offer with extra fields is forwarded as {type, offer:{type, sdp}} only', async () => {
  const { a, b, ra } = await pair(srv.port);
  const [sender, receiver] = ra.initiator ? [a, b] : [b, a];
  const sdp = 'v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\n';
  sender.send({
    type: 'offer',
    offer: { type: 'offer', sdp, extra: 'drop-me', nested: { x: 1 } },
    smuggled: 'drop-me-too',
  });
  assert.deepEqual(await receiver.next(), { type: 'offer', offer: { type: 'offer', sdp } });
  a.close();
  b.close();
});

test('a valid Pro token makes turnEnabled follow TURN_* env', async () => {
  const token = remember(mintToken(Math.floor(Date.now() / 1000) + 3600));

  // TURN configured + Pro token -> TURN on, with a TURN server in iceServers.
  const pro = await pair(srv.port, [token]);
  for (const r of [pro.ra, pro.rb]) {
    assert.equal(r.type, 'ready');
    assert.equal(r.turnEnabled, true);
    assert.ok(r.iceServers.some((s) => [].concat(s.urls).some((u) => u.startsWith('turn:turn.test.local'))));
    for (const s of r.iceServers) if (s.username) remember(s.username, s.credential);
  }
  pro.a.close();
  pro.b.close();

  // TURN configured, no token -> off.
  const free = await pair(srv.port);
  assert.equal(free.ra.turnEnabled, false);
  assert.equal(free.rb.turnEnabled, false);
  free.a.close();
  free.b.close();

  // Bad signature -> off.
  const forged = remember(mintToken(Math.floor(Date.now() / 1000) + 3600, 'wrong-secret'));
  const bad = await pair(srv.port, [forged, forged]);
  assert.equal(bad.ra.turnEnabled, false);
  bad.a.close();
  bad.b.close();

  // Pro token but no TURN env -> off.
  const noTurn = await startServer();
  try {
    const p = await pair(noTurn.port, [token, token]);
    assert.equal(p.ra.turnEnabled, false);
    assert.equal(p.rb.turnEnabled, false);
    p.a.close();
    p.b.close();
  } finally {
    await noTurn.stop();
  }
  for (const s of secrets) assert.ok(!noTurn.logs.all.includes(s), 'secret leaked into logs');
});

function signedWebhook(body, secret = TEST_BTCPAY_WEBHOOK_SECRET) {
  const raw = JSON.stringify(body);
  const sig = 'sha256=' + createHmac('sha256', secret).update(raw).digest('hex');
  return fetch(`${base()}/webhook/btcpay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'BTCPay-Sig': sig },
    body: raw,
  });
}

test('webhook with a bad signature gets 401', async () => {
  const invoiceId = remember(`inv${randomBytes(8).toString('hex')}`);
  const res = await signedWebhook(
    { type: 'InvoiceSettled', invoiceId, metadata: { itemCode: 'pro-1d' } },
    'not-the-webhook-secret'
  );
  assert.equal(res.status, 401);
  await res.arrayBuffer();
});

test('settled pro-1d invoice can be claimed for a ~1 day token', async () => {
  const invoiceId = remember(`inv${randomBytes(8).toString('hex')}`);
  const res = await signedWebhook({ type: 'InvoiceSettled', invoiceId, metadata: { itemCode: 'pro-1d' } });
  assert.equal(res.status, 200);
  await res.arrayBuffer();

  const claim = await fetch(`${base()}/api/claim?invoiceId=${encodeURIComponent(invoiceId)}`);
  assert.equal(claim.status, 200);
  const body = await claim.json();
  assert.equal(body.ready, true);
  assert.equal(typeof body.token, 'string');
  remember(body.token, body.token.split('.')[1]);

  const exp = Number(body.token.split('.')[0]);
  const expected = Math.floor(Date.now() / 1000) + 86400;
  assert.ok(Math.abs(exp - expected) <= 10, `exp ${exp} not within 10s of ${expected}`);
  const sig = createHmac('sha256', TEST_PRO_TOKEN_SECRET).update(String(exp)).digest('hex');
  assert.equal(body.token, `${exp}.${sig}`);
});

test('log hygiene: no addresses, room IDs, invoice IDs or tokens in logs', async () => {
  // Let any in-flight close/cleanup logging flush, and at least one stats
  // line be written after all the baseline traffic.
  const statsLines = () => srv.logs.stdout.split('\n').filter((l) => l.includes(' stats '));
  const before = statsLines().length;
  const deadline = Date.now() + 3000;
  while (statsLines().length <= before && Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 50));
  }
  assert.ok(statsLines().length > before, 'no stats line after the baseline traffic');
  for (const line of statsLines()) assert.match(line, STATS_LINE_RE, 'stats line carries more than counters');
  const logs = srv.logs.all;
  assert.ok(logs.includes('listening on'), 'logs were captured');
  assert.ok(secrets.length > 5, 'tests recorded the values to look for');
  assert.ok(!logs.includes('127.0.0.1'), 'IPv4 loopback address in logs');
  assert.ok(!logs.includes('::1'), 'IPv6 loopback address in logs');
  for (const s of secrets) assert.ok(!logs.includes(s), 'sensitive value found in logs');
});
