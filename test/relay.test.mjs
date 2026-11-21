// Relay stall detection, relay room cap and control-message budgets.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { startServer, TEST_PRO_TOKEN_SECRET } from './helpers/server.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function proToken() {
  const exp = String(Math.floor(Date.now() / 1000) + 3600);
  return `${exp}.${createHmac('sha256', TEST_PRO_TOKEN_SECRET).update(exp).digest('hex')}`;
}

async function client(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/`, { origin: 'https://test.local' });
  const msgs = [];
  const waiters = [];
  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    const msg = JSON.parse(data.toString());
    msgs.push(msg);
    for (const w of [...waiters]) {
      if (w.pred(msg)) {
        waiters.splice(waiters.indexOf(w), 1);
        w.resolve(msg);
      }
    }
  });
  ws.closed = new Promise((resolve) => ws.on('close', (code, reason) => resolve({ code, reason: reason.toString() })));
  await new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
  return {
    ws,
    msgs,
    send: (obj) => ws.send(JSON.stringify(obj)),
    waitFor(pred, timeoutMs = 3000) {
      const found = msgs.find(pred);
      if (found) return Promise.resolve(found);
      return new Promise((resolve, reject) => {
        const w = { pred, resolve: (m) => { clearTimeout(t); resolve(m); } };
        const t = setTimeout(() => {
          waiters.splice(waiters.indexOf(w), 1);
          reject(new Error('timed out waiting for message'));
        }, timeoutMs);
        waiters.push(w);
      });
    },
  };
}

async function pair(port, token) {
  const room = randomBytes(16).toString('hex');
  const a = await client(port);
  const b = await client(port);
  a.send({ type: 'join', room, token });
  b.send({ type: 'join', room, token });
  await Promise.all([a.waitFor((m) => m.type === 'ready'), b.waitFor((m) => m.type === 'ready')]);
  return { a, b, room };
}

async function withServer(env, fn) {
  const srv = await startServer(env);
  try {
    await fn(srv);
  } finally {
    await srv.stop();
  }
  return srv;
}

test('a receiver that stops reading is closed with 1008 relay-stalled', async (t) => {
  await withServer({ HEARTBEAT_MS: '1000', RELAY_STALL_MS: '2000' }, async (srv) => {
    // Pro: 4 MB/s, so the kernel socket buffers fill quickly.
    const { a: sender, b: receiver } = await pair(srv.port, proToken());
    sender.send({ type: 'ws-fallback' });
    await receiver.waitFor((m) => m.type === 'ws-fallback');

    // The attack: stop reading, but keep sending a control message so the
    // heartbeat still sees the receiver as alive. (Writes work while paused.)
    receiver.ws._socket.pause();
    const keepalive = setInterval(() => receiver.send({ type: 'noop' }), 500);
    const chunk = randomBytes(60 * 1024);
    let streaming = true;
    const pump = async () => {
      while (streaming && sender.ws.readyState === WebSocket.OPEN) {
        if (sender.ws.bufferedAmount < 1024 * 1024) sender.ws.send(chunk);
        else await sleep(5);
        await new Promise((r) => setImmediate(r));
      }
    };
    const t0 = Date.now();
    const pumping = pump();

    let stalled;
    let elapsed;
    try {
      stalled = await sender.waitFor((m) => m.type === 'error' && m.reason === 'relay-stalled', 15000);
      elapsed = Date.now() - t0;
    } finally {
      streaming = false;
      clearInterval(keepalive);
      await pumping;
    }
    assert.deepEqual(stalled, { type: 'error', reason: 'relay-stalled' });
    assert.ok(elapsed < 10000, `stall detected after ${elapsed} ms`);
    t.diagnostic(`relay-stalled after ${elapsed} ms`);

    // Once the receiver reads again it sees its queued data, the notice and 1008.
    receiver.ws._socket.resume();
    const closed = await receiver.ws.closed;
    assert.equal(closed.code, 1008);
    assert.equal(closed.reason, 'relay-stalled');
    await sender.waitFor((m) => m.type === 'peer-left', 5000);
    sender.ws.close();
  });
});

test('with MAX_RELAY_ROOMS=1 a second relay room gets relay-busy', async () => {
  await withServer({ MAX_RELAY_ROOMS: '1' }, async (srv) => {
    const first = await pair(srv.port);
    first.a.send({ type: 'ws-fallback' });
    await first.b.waitFor((m) => m.type === 'ws-fallback');

    const second = await pair(srv.port);
    second.a.send({ type: 'ws-fallback' });
    assert.deepEqual(
      await second.a.waitFor((m) => m.type === 'error'),
      { type: 'error', reason: 'relay-busy' }
    );
    await sleep(300);
    assert.ok(!second.b.msgs.some((m) => m.type === 'ws-fallback'), 'ws-fallback was forwarded');

    // A relay frame in the refused room is not relayed.
    let gotBinary = false;
    second.b.ws.on('message', (_d, isBinary) => { if (isBinary) gotBinary = true; });
    second.a.ws.send(randomBytes(16));
    await sleep(300);
    assert.equal(gotBinary, false);

    // Freeing the first relay room frees the slot.
    first.a.ws.close();
    first.b.ws.close();
    await Promise.all([first.a.ws.closed, first.b.ws.closed]);
    await sleep(100);
    second.a.send({ type: 'ws-fallback' });
    await second.b.waitFor((m) => m.type === 'ws-fallback');
    second.a.ws.close();
    second.b.ws.close();
  });
});

test('121 control messages in 10 s close the socket with 1008', async () => {
  await withServer({}, async (srv) => {
    const c = await client(srv.port);
    for (let i = 0; i < 120; i++) c.send({ type: 'noop' });
    await sleep(200);
    assert.equal(c.ws.readyState, WebSocket.OPEN, '120 messages must be allowed');
    c.send({ type: 'noop' });
    const closed = await c.ws.closed;
    assert.equal(closed.code, 1008);
  });
});

test('201 ICE candidates close the socket with too-many-candidates', async () => {
  await withServer({}, async (srv) => {
    const { a, b } = await pair(srv.port);
    const cand = (i) => ({ type: 'candidate', candidate: { candidate: `candidate:${i} 1 udp 1 192.0.2.1 ${1000 + i} typ host`, sdpMid: '0', sdpMLineIndex: 0 } });
    let received = 0;
    b.ws.on('message', (d) => { if (JSON.parse(d.toString()).type === 'candidate') received += 1; });

    // Stay under the 120-messages-per-10s budget: 110 now, 91 in the next window.
    for (let i = 0; i < 110; i++) a.send(cand(i));
    await sleep(10_200);
    assert.equal(a.ws.readyState, WebSocket.OPEN);
    for (let i = 110; i < 200; i++) a.send(cand(i));
    await sleep(200);
    assert.equal(a.ws.readyState, WebSocket.OPEN, '200 candidates must be allowed');
    a.send(cand(200));
    const closed = await a.ws.closed;
    assert.equal(closed.code, 1008);
    assert.equal(closed.reason, 'too-many-candidates');
    assert.equal(received, 200, 'candidate 201 must not be forwarded');
    b.ws.close();
  });
});

test('candidate budget resets when the peer changes', async () => {
  await withServer({}, async (srv) => {
    const { a, b, room } = await pair(srv.port);
    const cand = (i) => ({ type: 'candidate', candidate: { candidate: `candidate:${i} 1 udp 1 192.0.2.1 ${1000 + i} typ host` } });
    for (let i = 0; i < 100; i++) a.send(cand(i));
    await sleep(10_200);
    for (let i = 100; i < 200; i++) a.send(cand(i));
    await sleep(200);
    b.ws.close();
    await a.waitFor((m) => m.type === 'peer-left');

    // New peer: a gets a fresh budget of 200 (in a new message window).
    const c = await client(srv.port);
    c.send({ type: 'join', room });
    await a.waitFor((m) => m.type === 'ready' && a.msgs.filter((x) => x.type === 'ready').length === 2);
    await sleep(10_200);
    for (let i = 0; i < 50; i++) a.send(cand(i));
    await sleep(200);
    assert.equal(a.ws.readyState, WebSocket.OPEN);
    a.ws.close();
    c.ws.close();
  });
});
