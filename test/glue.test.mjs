// Connection/UI glue of public/index.html, run against a real server.js:
// connection recovery, the WebSocket-relay fallback, the relay budget and the
// idle/busy relay slot states. See test/helpers/page.mjs for the harness.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { startServer } from './helpers/server.mjs';
import { loadPage, roomHash, withOtherKey, fakeFile, createFakeRtcNet } from './helpers/page.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const READY = 'DROP FILE HERE OR CLICK TO SELECT';
const READY_WS = 'DROP FILE HERE OR CLICK TO SELECT (WS RELAY)';
const RELAY_ACTIVE = /^E2E-encrypted WebSocket relay active/;

// Starts a server, hands out pages that are closed again afterwards.
async function withPages(env, fn) {
  const srv = await startServer(env);
  const pages = [];
  const raws = [];
  const ctx = {
    port: srv.port,
    page(opts) {
      const p = loadPage({ port: srv.port, ...opts });
      pages.push(p);
      return p;
    },
    // A bare signaling client, for a peer that is not a page.
    async raw(room) {
      const ws = new WebSocket(`ws://127.0.0.1:${srv.port}/`, { origin: 'https://test.local' });
      const c = { ws, msgs: [], binary: 0 };
      ws.on('message', (data, isBinary) => {
        if (isBinary) c.binary += 1;
        else c.msgs.push(JSON.parse(data.toString()));
      });
      ws.on('error', () => {});
      await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
      c.send = (obj) => ws.send(JSON.stringify(obj));
      c.waitFor = async (pred, what = 'message') => {
        const end = Date.now() + 5000;
        while (!c.msgs.some(pred)) {
          if (Date.now() > end) throw new Error(`raw client: timed out waiting for ${what}`);
          await sleep(10);
        }
      };
      if (room) c.send({ type: 'join', room });
      raws.push(c);
      return c;
    },
  };
  try {
    await fn(ctx);
  } finally {
    for (const p of pages) p.close();
    for (const c of raws) c.ws.terminate();
    await srv.stop();
  }
}

const roomOf = (hash) => hash.slice(1).split(':')[0];

async function directPair(ctx) {
  const rtc = createFakeRtcNet();
  const hash = roomHash();
  const a = ctx.page({ hash, rtc });
  await a.waitForLog(/^Connected to signaling broker/);
  const b = ctx.page({ hash, rtc });
  await Promise.all([a.waitForLabel(/^DROP FILE/), b.waitForLabel(/^DROP FILE/)]);
  return { a, b, rtc };
}

async function relayPair(ctx, opts = {}) {
  const hash = roomHash();
  const a = ctx.page({ hash, forceWsRelay: true, ...opts });
  await a.waitForLog(/^Connected to signaling broker/);
  const b = ctx.page({ hash, forceWsRelay: true, ...opts });
  return { a, b, hash };
}

// Sends `file` from one page, accepts it on the other and waits for delivery.
async function transfer(from, to, file) {
  const before = to.downloads.length;
  from.chooseFile(file);
  await to.waitFor(() => to.offerButton('Accept'), 'the incoming offer');
  to.offerButton('Accept').onclick();
  await from.waitForLog(new RegExp(`^Delivered: ${file.name}$`), 15_000);
  await to.waitFor(() => to.downloads.length === before + 1, 'the saved file');
  const got = Buffer.from(await to.downloads[before].arrayBuffer());
  assert.ok(got.equals(file.bytes), `${file.name} arrived altered`);
}

test('direct tunnel: the drop zone opens only after the key handshake', async () => {
  await withPages({}, async (ctx) => {
    const { a, b } = await directPair(ctx);
    for (const p of [a, b]) {
      assert.equal(p.label, READY);
      assert.equal(p.dropEnabled, true);
      const firstEnable = p.events.findIndex((e) => e.type === 'drop' && e.enabled);
      const verified = p.events.findIndex((e) => e.type === 'log' && /^Peer key verified/.test(e.text));
      assert.ok(verified !== -1 && verified < firstEnable, 'the drop zone opened before the key was verified');
      await p.waitFor(() => p.modeBadge === 'TUNNEL: DIRECT P2P (STUN)', 'the direct badge');
      assert.equal(p.ipBadge, 'IP EXPOSED TO PEER');
      assert.equal(p.sockets.length, 1);
    }
  });
});

test('connection recovery: "connected" after "disconnected" re-opens the drop zone', async () => {
  await withPages({}, async (ctx) => {
    const { a, b } = await directPair(ctx);
    const pc = a.pcs.at(-1);

    pc.emitState('disconnected');
    assert.equal(a.dropEnabled, false);
    assert.equal(a.label, 'CONNECTION LOST');
    assert.equal(a.modeBadge, 'TUNNEL: INTERRUPTED');
    assert.ok(a.hasLog(/^WebRTC connection disconnected\.$/));

    // Before the fix this branch called a function that no longer existed and
    // threw, leaving the page on "CONNECTION LOST" for good.
    assert.doesNotThrow(() => pc.emitState('connected'));
    assert.equal(a.dropEnabled, true);
    assert.equal(a.label, READY);
    await a.waitFor(() => a.modeBadge === 'TUNNEL: DIRECT P2P (STUN)', 'the direct badge');

    // The recovered tunnel still carries a file, and the peer never noticed.
    assert.equal(b.label, READY);
    await transfer(a, b, fakeFile('after-recovery.bin', 40_000));
    await a.waitForLabel(/^DROP FILE/);
    assert.ok(!a.hasLog(/Session renewed|Send interrupted/), a.logTexts().join('\n'));
  });
});

test('connection recovery during a send keeps the drop zone closed until the send ends', async () => {
  await withPages({}, async (ctx) => {
    const { a, b } = await directPair(ctx);
    const pc = a.pcs.at(-1);
    const file = fakeFile('mid-send.bin', 100_000);

    a.chooseFile(file);
    await b.waitFor(() => b.offerButton('Accept'), 'the incoming offer');
    assert.equal(a.label, `OFFERING: ${file.name}`);

    pc.emitState('disconnected');
    pc.emitState('connected');
    // enableDropIfReady() must not offer a second file while one is being sent.
    assert.equal(a.dropEnabled, false);

    b.offerButton('Accept').onclick();
    await a.waitForLog(/^Delivered: mid-send\.bin$/, 15_000);
    await a.waitForLabel(/^DROP FILE/);
    assert.equal(a.dropEnabled, true);
    assert.equal(a.label, READY);
    assert.ok(Buffer.from(await b.downloads[0].arrayBuffer()).equals(file.bytes));
  });
});

test('"connected" before the handshake is confirmed does not open the drop zone', async () => {
  await withPages({}, async (ctx) => {
    const rtc = createFakeRtcNet();
    const hash = roomHash();
    const a = ctx.page({ hash, rtc });
    await a.waitForLog(/^Connected to signaling broker/);
    // The peer has a different key, so the handshake can never be confirmed.
    const b = ctx.page({ hash: withOtherKey(hash), rtc });
    await Promise.all([a.waitForLabel(/^Key mismatch/), b.waitForLabel(/^Key mismatch/)]);

    for (const p of [a, b]) {
      const pc = p.pcs.at(-1);
      pc.emitState('disconnected');
      pc.emitState('connected');
      assert.equal(p.dropEnabled, false, 'a recovered connection opened the drop zone without a verified key');
    }
  });
});

test('forced WebSocket relay: one status line, masked badge, drop zone opens after the handshake', async () => {
  await withPages({}, async (ctx) => {
    const { a, b } = await relayPair(ctx);
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);

    for (const p of [a, b]) {
      assert.equal(p.label, READY_WS);
      assert.equal(p.dropEnabled, true);
      assert.equal(p.modeBadge, 'TUNNEL: E2E WS RELAY');
      assert.equal(p.ipBadge, 'IP MASKED FROM PEER');
      // Exactly one status line, and the right one: no IP was ever signaled.
      assert.deepEqual(p.logTexts().filter((t) => RELAY_ACTIVE.test(t)),
        ['E2E-encrypted WebSocket relay active (IP masked from peer).']);
      assert.equal(p.pcs.length, 0, 'a forced relay must not create an RTCPeerConnection');

      // The fallback itself must not open the drop zone: only the hello-ack does.
      const active = p.events.findIndex((e) => e.type === 'log' && RELAY_ACTIVE.test(e.text));
      const verified = p.events.findIndex((e) => e.type === 'log' && /^Peer key verified/.test(e.text));
      const enables = p.events.map((e, i) => (e.type === 'drop' && e.enabled ? i : -1)).filter((i) => i !== -1);
      assert.ok(active !== -1 && verified > active);
      assert.ok(enables.length >= 1 && enables[0] > verified,
        'the drop zone was opened before the relay handshake completed');
    }

    await transfer(a, b, fakeFile('over-relay.bin', 50_000));
    await transfer(b, a, fakeFile('and-back.bin', 20_000));
  });
});

test('WebSocket relay: a key mismatch stays on screen and the drop zone stays closed', async () => {
  await withPages({}, async (ctx) => {
    const hash = roomHash();
    const a = ctx.page({ hash, forceWsRelay: true });
    await a.waitForLog(/^Connected to signaling broker/);
    const b = ctx.page({ hash: withOtherKey(hash), forceWsRelay: true });
    await Promise.all([a.waitForLabel(/^Key mismatch/), b.waitForLabel(/^Key mismatch/)]);
    // Let anything that would overwrite the label (a late fallback message) land.
    await sleep(300);

    for (const p of [a, b]) {
      assert.equal(p.label, 'Key mismatch — the link was altered');
      assert.equal(p.dropEnabled, false);
      assert.ok(!p.events.some((e) => e.type === 'drop' && e.enabled), 'the drop zone was opened at some point');
      assert.equal(p.countLog(RELAY_ACTIVE), 1);
      assert.equal(p.countLog(/^Key mismatch/), 1);
    }

    // A peer's later ws-fallback must not clear it either.
    a.sockets[0].send(JSON.stringify({ type: 'ws-fallback' }));
    await sleep(300);
    assert.equal(b.label, 'Key mismatch — the link was altered');
    assert.equal(b.dropEnabled, false);
  });
});

test('WebRTC failure falls back to the relay and reports an already-signaled IP', async () => {
  await withPages({}, async (ctx) => {
    const rtc = createFakeRtcNet();
    rtc.net.connectOnAnswer = false; // ICE never completes
    const hash = roomHash();
    const a = ctx.page({ hash, rtc });
    await a.waitForLog(/^Connected to signaling broker/);
    const b = ctx.page({ hash, rtc });
    await Promise.all([a.waitFor(() => a.pcs.length === 1, 'a to start WebRTC'),
      b.waitFor(() => b.pcs.length === 1, 'b to start WebRTC')]);
    await sleep(100);

    // a signals a host candidate with its address, then ICE fails on a's side.
    a.pcs[0].emitCandidate({ candidate: 'candidate:1 1 udp 2122260223 192.0.2.7 54321 typ host', sdpMid: '0', sdpMLineIndex: 0 });
    a.pcs[0].emitState('failed');
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);

    assert.deepEqual(a.logTexts().filter((t) => RELAY_ACTIVE.test(t)),
      ['E2E-encrypted WebSocket relay active (IP was already shared during the WebRTC attempt).']);
    assert.equal(a.ipBadge, 'IP EXPOSED TO PEER');
    assert.equal(a.modeBadge, 'TUNNEL: E2E WS RELAY');
    // b followed a's ws-fallback and never signaled a candidate itself.
    assert.deepEqual(b.logTexts().filter((t) => RELAY_ACTIVE.test(t)),
      ['E2E-encrypted WebSocket relay active (IP masked from peer).']);
    assert.equal(b.ipBadge, 'IP MASKED FROM PEER');
    for (const p of [a, b]) {
      assert.equal(p.label, READY_WS);
      assert.equal(p.dropEnabled, true);
      assert.equal(p.pendingTimers(12_000), 0, 'the ICE timeout was left running');
      assert.equal(p.sockets.length, 1);
    }
    await transfer(a, b, fakeFile('after-fallback.bin', 30_000));
  });
});

test('WebRTC handshake timeout switches both peers to the relay', async () => {
  await withPages({}, async (ctx) => {
    const rtc = createFakeRtcNet();
    rtc.net.connectOnAnswer = false;
    const hash = roomHash();
    const a = ctx.page({ hash, rtc });
    await a.waitForLog(/^Connected to signaling broker/);
    const b = ctx.page({ hash, rtc });
    await b.waitFor(() => b.pcs.length === 1 && b.pendingTimers(12_000) === 1, 'b to start WebRTC');
    await sleep(100);

    assert.equal(b.runTimers(12_000), 1);
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);
    assert.ok(b.hasLog(/^WebRTC handshake timed out/));
    for (const p of [a, b]) {
      assert.equal(p.countLog(RELAY_ACTIVE), 1);
      assert.equal(p.ipBadge, 'IP MASKED FROM PEER');
    }
  });
});

test('relay budget: a file that does not fit is refused locally and nothing is sent', async () => {
  await withPages({ RELAY_CAP_FREE_MB: '1' }, async (ctx) => {
    const { a, b } = await relayPair(ctx);
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);

    const sentBefore = a.sockets[0].sentBinary;
    a.chooseFile(fakeFile('too-big.bin', 2 * 1048576));
    await a.waitForLog(/^Blocked: too-big\.bin \(2\.0 MB\) does not fit the WebSocket relay: .* of this room's 1 MB left\./);
    assert.equal(a.el('proModal').classList.contains('open'), true);
    assert.equal(a.dropEnabled, true, 'a refused file must leave the drop zone usable');
    assert.equal(a.label, READY_WS);
    await sleep(100);
    assert.equal(a.sockets[0].sentBinary, sentBefore);
    assert.equal(b.offerButton('Accept'), null);

    // What does fit still goes through, and is charged: the next one is refused.
    await transfer(a, b, fakeFile('fits.bin', 600_000));
    await a.waitForLabel(/WS RELAY/);
    b.chooseFile(fakeFile('second.bin', 600_000));
    await b.waitForLog(/^Blocked: second\.bin/);
    assert.equal(a.offerButton('Accept'), null);
  });
});

test('relay budget used up: both states are shown once, without a reconnect loop', async () => {
  await withPages({ RELAY_CAP_FREE_MB: '1' }, async (ctx) => {
    const hash = roomHash();
    const a = ctx.page({ hash, forceWsRelay: true });
    await a.waitForLog(/^Connected to signaling broker/);
    const peer = await ctx.raw(roomOf(hash));
    await peer.waitFor((m) => m.type === 'ws-fallback', 'ws-fallback from the page');

    // The peer spends the room's budget (DATA-typed junk the page ignores).
    for (let i = 0; i < 20; i++) {
      const junk = randomBytes(64_000);
      junk[0] = 0x22;
      peer.ws.send(junk);
    }
    await a.waitForLabel(/^RELAY BUDGET USED UP/);
    assert.equal(a.label, 'RELAY BUDGET USED UP — OPEN A FRESH LINK');
    assert.equal(a.dropEnabled, false);
    assert.equal(a.modeBadge, 'TUNNEL: CLOSED');
    await peer.waitFor((m) => m.type === 'error' && m.reason === 'relay-limit-exceeded', 'the limit notice');
    // The server drops whole frames, so up to one 64 KB frame of budget is
    // left. Small frames use that up too (those that fit are still relayed).
    for (let i = 0; i < 100; i++) {
      const junk = randomBytes(1024);
      junk[0] = 0x22;
      peer.ws.send(junk);
    }

    // The socket stays open and the page does not reconnect or retry.
    await sleep(1200);
    assert.equal(a.sockets.length, 1, 'the page opened another signaling connection');
    assert.equal(a.sockets[0].readyState, WebSocket.OPEN);
    assert.equal(a.countLog(/relay budget \(1 MB\) is used up/), 1);
    assert.ok(!a.hasLog(/Signaling connection lost/));
    // The peer never answered the hello; on a spent relay that must not turn
    // into "peer is outdated" when the 5 s hello timeout would have fired.
    a.runTimers(5000);
    assert.equal(a.label, 'RELAY BUDGET USED UP — OPEN A FRESH LINK');
    assert.ok(!a.hasLog(/outdated|did not confirm/), a.logTexts().join('\n'));

    // A new peer in the same room: the page asks for the relay but starts no
    // key handshake, because its frames could not cross.
    peer.ws.close();
    await a.waitForLabel(/^PEER DISCONNECTED/);
    const sentBefore = a.sockets[0].sentBinary;
    const next = await ctx.raw(roomOf(hash));
    await next.waitFor((m) => m.type === 'ready', 'ready');
    assert.ok(next.msgs.find((m) => m.type === 'ready').wsRelayRemaining < 4096);
    await a.waitForLabel(/^RELAY BUDGET USED UP/);
    await sleep(300);
    assert.equal(a.sockets[0].sentBinary, sentBefore, 'a hello was sent over a spent relay');
    assert.equal(next.binary, 0);
    assert.equal(a.dropEnabled, false);
    assert.equal(a.label, 'RELAY BUDGET USED UP — OPEN A FRESH LINK');
    assert.equal(a.sockets.length, 1);
  });
});

test('idle relay: both pages pause, one click resumes, and a file then goes through', async () => {
  await withPages({ RELAY_IDLE_MS: '1500', HEARTBEAT_MS: '1000' }, async (ctx) => {
    const { a, b } = await relayPair(ctx);
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);

    await Promise.all([a.waitForLabel(/^RELAY PAUSED/, 6000), b.waitForLabel(/^RELAY PAUSED/, 6000)]);
    for (const p of [a, b]) {
      assert.equal(p.label, 'RELAY PAUSED (IDLE) — CLICK HERE TO RESUME');
      assert.equal(p.dropEnabled, false);
      assert.equal(p.countLog(/relay was paused/), 1);
      // A paused relay must not time out as an outdated or unconfirmed peer.
      p.runTimers(5000);
      assert.equal(p.label, 'RELAY PAUSED (IDLE) — CLICK HERE TO RESUME');
      assert.ok(!p.hasLog(/outdated|did not confirm/), p.logTexts().join('\n'));
      assert.equal(p.sockets.length, 1);
    }

    // A click on the (disabled) drop zone resumes; the peer follows by itself.
    const verifiedBefore = b.countLog(/^Peer key verified/);
    a.clickDropzone();
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);
    for (const p of [a, b]) {
      assert.equal(p.label, READY_WS);
      assert.equal(p.dropEnabled, true);
    }
    assert.equal(b.countLog(/^Peer key verified/), verifiedBefore + 1, 'the resume must run a fresh key handshake');
    assert.equal(a.countLog(RELAY_ACTIVE), 1, 'resuming is not a second fallback');
    await transfer(b, a, fakeFile('after-idle.bin', 30_000));
  });
});

test('idle relay during a send: the send is interrupted, not left hanging', async () => {
  await withPages({ RELAY_IDLE_MS: '1500', HEARTBEAT_MS: '1000' }, async (ctx) => {
    const { a, b } = await relayPair(ctx);
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);

    // Offered but never answered: no frame crosses, so the relay goes idle.
    a.chooseFile(fakeFile('unanswered.bin', 10_000));
    await b.waitFor(() => b.offerButton('Accept'), 'the incoming offer');
    await Promise.all([a.waitForLabel(/^RELAY PAUSED/, 6000), b.waitForLabel(/^RELAY PAUSED/, 6000)]);
    assert.ok(a.hasLog(/^Send interrupted: unanswered\.bin$/));
    assert.ok(b.hasLog(/^Receive interrupted: unanswered\.bin$/));
    assert.equal(b.offerButton('Accept'), null, 'the stale offer is still on screen');
    assert.equal(a.el('cancelBtn').hidden, true);
    assert.equal(a.label, 'RELAY PAUSED (IDLE) — CLICK HERE TO RESUME');
    assert.equal(a.dropEnabled, false);
  });
});

test('busy relay: the page says so, does not call the peer outdated, and connects on retry', async () => {
  await withPages({ MAX_RELAY_ROOMS: '1' }, async (ctx) => {
    // Another room holds the only relay slot.
    const otherRoom = randomBytes(16).toString('hex');
    const x = await ctx.raw(otherRoom);
    const y = await ctx.raw(otherRoom);
    await x.waitFor((m) => m.type === 'ready', 'ready');
    x.send({ type: 'ws-fallback' });
    await y.waitFor((m) => m.type === 'ws-fallback', 'ws-fallback');

    const { a, b } = await relayPair(ctx);
    await Promise.all([a.waitForLabel(/^RELAY BUSY/), b.waitForLabel(/^RELAY BUSY/)]);
    await sleep(300);
    for (const p of [a, b]) {
      assert.equal(p.label, 'RELAY BUSY — RETRYING SHORTLY...');
      assert.equal(p.dropEnabled, false);
      assert.equal(p.countLog(/relay is at capacity/), 1);
      assert.equal(p.pendingTimers(30_000), 1, 'no retry is scheduled');
      // The hello was never carried: its timeout must not blame the peer.
      p.runTimers(5000);
      assert.ok(!/outdated/.test(p.label), p.label);
      assert.ok(!p.hasLog(/outdated/));
    }

    // Still busy on a retry: same label, still one explanation.
    a.runTimers(30_000);
    await sleep(300);
    assert.equal(a.label, 'RELAY BUSY — RETRYING SHORTLY...');
    assert.equal(a.countLog(/relay is at capacity/), 1);

    // The slot frees up; the next retry connects without any click.
    x.ws.close();
    y.ws.close();
    await sleep(200);
    assert.ok(a.runTimers(30_000) + b.runTimers(30_000) >= 1);
    await Promise.all([a.waitForLabel(/WS RELAY/), b.waitForLabel(/WS RELAY/)]);
    for (const p of [a, b]) {
      assert.equal(p.label, READY_WS);
      assert.equal(p.dropEnabled, true);
      assert.equal(p.pendingTimers(30_000), 0, 'a retry is still scheduled after connecting');
    }
    await transfer(a, b, fakeFile('after-busy.bin', 20_000));
  });
});
