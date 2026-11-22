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
  await withServer({ HEARTBEAT_MS: '1000', RELAY_STALL_MS: '2000', SWEEP_MS: '1000' }, async (srv) => {
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

    // The stall is counted in the next stats line.
    const deadline = Date.now() + 3000;
    while (!/ stats .* stalls=1$/m.test(srv.logs.stdout) && Date.now() < deadline) await sleep(50);
    assert.match(srv.logs.stdout, / stats .* stalls=1$/m);
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

// Counts binary frames a client receives.
function countBinary(c) {
  const seen = { frames: 0, bytes: 0 };
  c.ws.on('message', (data, isBinary) => {
    if (!isBinary) return;
    seen.frames += 1;
    seen.bytes += data.length;
  });
  return seen;
}

test('a frame over the room cap is dropped with one notice, and the socket stays open', async () => {
  await withServer({ RELAY_CAP_FREE_MB: '1' }, async (srv) => {
    const { a, b } = await pair(srv.port);
    const got = countBinary(b);
    a.send({ type: 'ws-fallback' });
    await b.waitFor((m) => m.type === 'ws-fallback');

    // 16 x 64000 = 1024000 bytes fit under 1 MiB (1048576); the next three do not.
    for (let i = 0; i < 19; i++) a.ws.send(randomBytes(64000));
    const notice = { type: 'error', reason: 'relay-limit-exceeded' };
    assert.deepEqual(await a.waitFor((m) => m.type === 'error'), notice);
    assert.deepEqual(await b.waitFor((m) => m.type === 'error'), notice);
    // The notice follows the 17th frame, so the 16 that fit were already sent on.
    for (const end = Date.now() + 5000; got.frames < 16 && Date.now() < end;) await sleep(25);
    await sleep(400);
    assert.equal(got.frames, 16, 'only frames under the cap are relayed');
    assert.equal(got.bytes, 16 * 64000);
    assert.equal(a.ws.readyState, WebSocket.OPEN, 'the sender is not closed');
    assert.equal(b.ws.readyState, WebSocket.OPEN);
    assert.equal(a.msgs.filter((m) => m.type === 'error').length, 1, 'one notice per pairing, not one per frame');
    assert.equal(b.msgs.filter((m) => m.type === 'error').length, 1);

    // A frame that still fits (a small handshake frame) is relayed.
    a.ws.send(randomBytes(200));
    for (const end = Date.now() + 5000; got.frames < 17 && Date.now() < end;) await sleep(25);
    assert.equal(got.frames, 17);
    // Control messages still work on the same socket.
    a.send({ type: 'candidate', candidate: { candidate: 'x' } });
    await b.waitFor((m) => m.type === 'candidate');
    a.ws.close();
    b.ws.close();
  });
});

test('with the budget spent, ws-fallback starts no relay: both peers are told and no slot is taken', async () => {
  await withServer({ RELAY_CAP_FREE_MB: '1', MAX_RELAY_ROOMS: '1' }, async (srv) => {
    const { a, b, room } = await pair(srv.port);
    const delivered = countBinary(b);
    a.send({ type: 'ws-fallback' });
    await b.waitFor((m) => m.type === 'ws-fallback');
    // Leave less than the 4096-byte minimum: 16 x 64000 + 1 x 22000 = 1046000 of 1048576.
    for (let i = 0; i < 16; i++) a.ws.send(randomBytes(64000));
    a.ws.send(randomBytes(22000));
    // Paced at 1 MB/s: wait until the server has relayed (and counted) all of it.
    const deadline = Date.now() + 15_000;
    while (delivered.bytes < 1046000 && Date.now() < deadline) await sleep(50);
    assert.equal(delivered.bytes, 1046000);

    // B reconnects (as a page reload would): the pairing is new, the budget is not.
    b.ws.close();
    await b.ws.closed;
    await a.waitFor((m) => m.type === 'peer-left');
    const b2 = await client(srv.port);
    const got = countBinary(b2);
    b2.send({ type: 'join', room });
    const ready = await b2.waitFor((m) => m.type === 'ready');
    assert.ok(ready.wsRelayRemaining < 4096, `remaining ${ready.wsRelayRemaining}`);

    b2.send({ type: 'ws-fallback' });
    const notice = { type: 'error', reason: 'relay-limit-exceeded' };
    assert.deepEqual(await b2.waitFor((m) => m.type === 'error'), notice);
    assert.ok(a.msgs.filter((m) => m.type === 'ws-fallback').length === 1, 'the peer still learns of the request');
    await a.waitFor((m) => m.type === 'error');
    // Nothing is relayed, and the sockets stay open.
    a.ws.send(randomBytes(100));
    await sleep(300);
    assert.equal(got.frames, 0);
    assert.equal(a.ws.readyState, WebSocket.OPEN);
    assert.equal(b2.ws.readyState, WebSocket.OPEN);

    // The spent room holds no relay slot: with MAX_RELAY_ROOMS=1 another room gets the relay.
    const other = await pair(srv.port);
    other.a.send({ type: 'ws-fallback' });
    await other.b.waitFor((m) => m.type === 'ws-fallback');
    assert.ok(!other.a.msgs.some((m) => m.type === 'error'));
    for (const c of [a, b2, other.a, other.b]) c.ws.close();
  });
});

test('a sender that keeps sending far past the cap is closed with 1008', async () => {
  await withServer({ RELAY_CAP_FREE_MB: '1' }, async (srv) => {
    const { a, b } = await pair(srv.port);
    a.send({ type: 'ws-fallback' });
    await b.waitFor((m) => m.type === 'ws-fallback');
    // 1 MiB cap + the 8 MiB over-cap allowance, and a little more.
    const pump = setInterval(() => {
      if (a.ws.readyState === WebSocket.OPEN && a.ws.bufferedAmount < 1024 * 1024) {
        for (let i = 0; i < 8; i++) a.ws.send(randomBytes(64000));
      }
    }, 20);
    try {
      const closed = await Promise.race([a.ws.closed, sleep(25_000).then(() => null)]);
      assert.ok(closed, 'the sender was not closed within 25 s');
      assert.equal(closed.code, 1008);
      assert.equal(closed.reason, 'relay-limit-exceeded');
    } finally {
      clearInterval(pump);
      a.ws.close();
      b.ws.close();
    }
  });
});

test('an idle relay room gives its slot back and can ask for the relay again', async () => {
  await withServer({ MAX_RELAY_ROOMS: '1', RELAY_IDLE_MS: '1500', HEARTBEAT_MS: '1000' }, async (srv) => {
    const first = await pair(srv.port);
    const got = countBinary(first.b);
    first.a.send({ type: 'ws-fallback' });
    await first.b.waitFor((m) => m.type === 'ws-fallback');

    // While frames flow, the room keeps its relay well past RELAY_IDLE_MS.
    for (let i = 0; i < 6; i++) {
      first.a.ws.send(randomBytes(100));
      await sleep(500);
    }
    assert.equal(got.frames, 6);
    assert.ok(!first.a.msgs.some((m) => m.type === 'error'), 'an active relay was expired');

    // Then nothing: both peers are told and the slot is free.
    const idle = { type: 'error', reason: 'relay-idle' };
    assert.deepEqual(await first.a.waitFor((m) => m.type === 'error', 4000), idle);
    assert.deepEqual(await first.b.waitFor((m) => m.type === 'error', 4000), idle);
    first.a.ws.send(randomBytes(100));
    await sleep(300);
    assert.equal(got.frames, 6, 'an expired relay forwards nothing');

    const second = await pair(srv.port);
    second.a.send({ type: 'ws-fallback' });
    await second.b.waitFor((m) => m.type === 'ws-fallback');
    assert.ok(!second.a.msgs.some((m) => m.type === 'error'), 'the idle room still held the slot');

    // The first room asks again: busy while the second holds the slot, granted once it is free.
    first.b.send({ type: 'ws-fallback' });
    assert.deepEqual(await first.b.waitFor((m) => m.reason === 'relay-busy'), { type: 'error', reason: 'relay-busy' });
    second.a.ws.close();
    second.b.ws.close();
    await Promise.all([second.a.ws.closed, second.b.ws.closed]);
    await sleep(100);
    first.b.send({ type: 'ws-fallback' });
    await first.a.waitFor((m) => m.type === 'ws-fallback');
    first.b.ws.send(randomBytes(100));
    first.a.ws.close();
    first.b.ws.close();
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
