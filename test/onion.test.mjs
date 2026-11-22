// Onion traffic is identified by listener, never by Host or X-Forwarded-For.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { startServer, freePort } from './helpers/server.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ONION = 'abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion';

// Resolves to 'open' (socket kept open) or the HTTP status of a rejected upgrade.
function tryUpgrade(port, headers, opened) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/`, { origin: 'https://test.local', headers });
    ws.on('open', () => {
      opened.push(ws);
      resolve('open');
    });
    ws.on('unexpected-response', (_req, res) => {
      res.resume();
      ws.terminate();
      resolve(res.statusCode);
    });
    ws.on('error', (err) => reject(err));
  });
}

async function withOnionServer(overrides, fn) {
  const onionPort = await freePort();
  const srv = await startServer({ ONION_HOST: ONION, ONION_PORT: String(onionPort), ...overrides });
  const opened = [];
  try {
    assert.notEqual(srv.port, onionPort);
    await fn({ srv, onionPort, opened });
  } finally {
    for (const ws of opened) ws.terminate();
    await srv.stop();
  }
  return srv;
}

test('onion listener ignores XFF and Host: one shared bucket, 4th conn gets 429', async () => {
  const xffs = ['198.51.100.1', '198.51.100.2', '198.51.100.3', '198.51.100.4'];
  const srv = await withOnionServer({ MAX_ONION_CONNS: '3' }, async ({ onionPort, opened }) => {
    const results = [];
    for (const xff of xffs) {
      results.push(await tryUpgrade(onionPort, { 'X-Forwarded-For': xff, Host: 'test.local' }, opened));
    }
    assert.deepEqual(results, ['open', 'open', 'open', 429]);
  });
  for (const xff of xffs) assert.ok(!srv.logs.all.includes(xff), 'XFF address in logs');
});

test('Host: <onion> on the main listener does not use the onion bucket', async () => {
  await withOnionServer(
    { MAX_CONNS_PER_IP: '2', MAX_ONION_CONNS: '50' },
    async ({ srv, opened }) => {
      const results = [];
      for (let i = 0; i < 3; i++) results.push(await tryUpgrade(srv.port, { Host: ONION }, opened));
      // In the onion bucket (cap 50) all three would open; per-IP cap is 2.
      assert.deepEqual(results, ['open', 'open', 429]);
    }
  );
});

test('Onion-Location is sent only on the main listener', async () => {
  await withOnionServer({}, async ({ srv, onionPort }) => {
    const main = await fetch(`http://127.0.0.1:${srv.port}/`, { method: 'HEAD' });
    assert.equal(main.headers.get('onion-location'), `http://${ONION}/`);
    const onion = await fetch(`http://127.0.0.1:${onionPort}/`, { method: 'HEAD' });
    assert.equal(onion.status, 200);
    assert.equal(onion.headers.get('onion-location'), null);
    // Host spoofing on the main listener does not suppress it either.
    const spoof = await fetch(`http://127.0.0.1:${srv.port}/`, { method: 'HEAD', headers: { Host: ONION } });
    assert.equal(spoof.headers.get('onion-location'), `http://${ONION}/`);
  });
});

function bootExit(env) {
  return spawnSync(process.execPath, ['server.js'], { cwd: ROOT, env, encoding: 'utf8', timeout: 5000 });
}

test('ONION_HOST without ONION_PORT refuses to start', async () => {
  const port = await freePort();
  const r = bootExit({ DOMAIN: 'test.local', PORT: String(port), ONION_HOST: ONION });
  assert.notEqual(r.status, 0);
  assert.equal(r.signal, null, 'process was killed by timeout instead of exiting');
  assert.match(r.stderr, /ONION_PORT/);
});

test('ONION_PORT equal to PORT refuses to start', async () => {
  const port = await freePort();
  const r = bootExit({ DOMAIN: 'test.local', PORT: String(port), ONION_HOST: ONION, ONION_PORT: String(port) });
  assert.notEqual(r.status, 0);
  assert.equal(r.signal, null);
  assert.match(r.stderr, /ONION_PORT must differ from PORT/);
});

test('ONION_PORT without ONION_HOST warns and still isolates', async () => {
  const onionPort = await freePort();
  const srv = await startServer({ ONION_PORT: String(onionPort), MAX_ONION_CONNS: '2' });
  const opened = [];
  try {
    assert.match(srv.logs.all, /ONION_PORT is set without ONION_HOST/);
    assert.match(srv.logs.stdout, new RegExp(`onion :${onionPort}`));
    const results = [];
    for (const xff of ['198.51.100.7', '198.51.100.8', '198.51.100.9']) {
      results.push(await tryUpgrade(onionPort, { 'X-Forwarded-For': xff }, opened));
    }
    assert.deepEqual(results, ['open', 'open', 429]);
  } finally {
    for (const ws of opened) ws.terminate();
    await srv.stop();
  }
});
