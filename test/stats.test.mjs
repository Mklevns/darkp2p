// Periodic stats line (Step 8, Task 8.2).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { startServer, STATS_LINE_RE } from './helpers/server.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function statsLines(logs) {
  return logs.stdout.split('\n').filter((l) => l.includes(' stats '));
}

function parseStats(line) {
  return Object.fromEntries(
    line.replace('[darkp2p] stats ', '').split(' ').map((kv) => kv.split('=')).map(([k, v]) => [k, Number(v)])
  );
}

async function waitForStats(logs, pred, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const hit = statsLines(logs).map(parseStats).find(pred);
    if (hit) return hit;
    if (Date.now() > deadline) throw new Error(`no matching stats line:\n${statsLines(logs).join('\n')}`);
    await sleep(50);
  }
}

function open(port) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/`, { origin: 'https://test.local' });
  return new Promise((resolve) => {
    ws.once('open', () => resolve({ ws, status: 'open' }));
    ws.once('unexpected-response', (_req, res) => {
      res.resume();
      resolve({ ws, status: res.statusCode });
    });
    ws.on('error', () => {});
  });
}

function nextJson(ws, type) {
  return new Promise((resolve) => {
    const onMsg = (data, isBinary) => {
      if (isBinary) return;
      const m = JSON.parse(data.toString());
      if (m.type === type) {
        ws.off('message', onMsg);
        resolve(m);
      }
    };
    ws.on('message', onMsg);
  });
}

test('sweep logs one counters-only stats line and resets the interval counters', async () => {
  const srv = await startServer({ SWEEP_MS: '1000', MAX_CONNS_PER_IP: '2' });
  try {
    // A relayed room: two peers, ws-fallback, then ~1.2 MB of binary frames.
    const room = randomBytes(16).toString('hex');
    const a = await open(srv.port);
    const b = await open(srv.port);
    const readyB = nextJson(b.ws, 'ready');
    a.ws.send(JSON.stringify({ type: 'join', room }));
    b.ws.send(JSON.stringify({ type: 'join', room }));
    await readyB;
    const fallback = nextJson(b.ws, 'ws-fallback');
    a.ws.send(JSON.stringify({ type: 'ws-fallback' }));
    await fallback;
    let received = 0;
    b.ws.on('message', (d, isBinary) => { if (isBinary) received += d.length; });
    const frame = randomBytes(16 * 1024);
    for (let i = 0; i < 75; i++) a.ws.send(frame);
    const deadline = Date.now() + 5000;
    while (received < 75 * frame.length && Date.now() < deadline) await sleep(20);
    assert.equal(received, 75 * frame.length, 'relay delivered every frame');

    // Third connection from the same address: 429.
    const c = await open(srv.port);
    assert.equal(c.status, 429);

    const busy = await waitForStats(srv.logs, (s) => s.rejected429 >= 1 && s.relayMB >= 1.1);
    assert.equal(busy.conns, 2);
    assert.equal(busy.rooms, 1);
    assert.equal(busy.relayRooms, 1);
    assert.equal(busy.stalls, 0);

    // Next interval: the interval counters are back to zero, the gauges are not.
    const seen = statsLines(srv.logs).length;
    const deadline2 = Date.now() + 3000;
    while (statsLines(srv.logs).length <= seen && Date.now() < deadline2) await sleep(50);
    const next = statsLines(srv.logs).slice(seen).map(parseStats);
    assert.ok(next.length >= 1, 'no stats line after the busy interval');
    for (const s of next) {
      assert.equal(s.relayMB, 0);
      assert.equal(s.rejected429, 0);
      assert.equal(s.conns, 2);
      assert.equal(s.relayRooms, 1);
    }

    a.ws.close();
    b.ws.close();
    await waitForStats(srv.logs, (s) => s.conns === 0 && s.rooms === 0 && s.relayRooms === 0);

    for (const line of statsLines(srv.logs)) assert.match(line, STATS_LINE_RE);
    assert.ok(!srv.logs.all.includes(room), 'room ID in logs');
  } finally {
    await srv.stop();
  }
});
