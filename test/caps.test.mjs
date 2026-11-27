// The Android client offers optional features in its hello: {"type":"hello",
// "v":3,"nonce":...,"caps":["chat"]}. The page must go on treating such a peer
// as an ordinary v3 peer. Here a scripted peer speaks the Android client's
// side of the wire to the real page over a real server.js.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomBytes } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { startServer } from './helpers/server.mjs';
import { loadPage, roomHash, fakeFile } from './helpers/page.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8').replace(/\r\n?/g, '\n');
const block = /\/\/ ==BEGIN darkp2p-framing==\n([\s\S]*?)\/\/ ==END darkp2p-framing==/.exec(html);
const F = new Function(`${block[1]}
return { importRoomKey, deriveHelloKey, createTransferKeyCache, encodeFrame, decodeFrame,
  FRAME_TYPE_HELLO, FRAME_TYPE_CTRL, FRAME_TYPE_DATA, ROLE_INITIATOR, ROLE_RESPONDER };`)();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const enc = (obj) => new TextEncoder().encode(JSON.stringify(obj));
const dec = (bytes) => JSON.parse(new TextDecoder().decode(bytes));

// A peer on the relay that sends what the Android client sends.
async function appPeer(port, keyBytes, room, helloExtra) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/`, { origin: 'https://test.local' });
  const peer = { ws, signals: [], frames: [], pageHello: null };
  const roomKey = await F.importRoomKey(new Uint8Array(keyBytes));
  let keys = null;
  let role = null;
  let chain = Promise.resolve();
  const myNonce = new Uint8Array(randomBytes(32));

  const lookup = (transferId, { type }) => {
    if (type === F.FRAME_TYPE_HELLO) return F.deriveHelloKey(roomKey, transferId);
    if (!keys) throw new Error('no session yet');
    return keys(transferId);
  };
  peer.sendFrame = async (type, transferId, seq, payload) => {
    const key = await lookup(transferId, { type });
    ws.send(await F.encodeFrame({ type, role, transferId, seq, payload, key }));
  };
  peer.sendCtrl = (transferId, seq, obj) => peer.sendFrame(F.FRAME_TYPE_CTRL, transferId, seq, enc(obj));

  ws.on('message', (data, isBinary) => {
    chain = chain.then(async () => {
      if (!isBinary) {
        const msg = JSON.parse(data.toString());
        peer.signals.push(msg);
        if (msg.type === 'ready') role = msg.initiator ? F.ROLE_INITIATOR : F.ROLE_RESPONDER;
        return;
      }
      const pageRole = role === F.ROLE_INITIATOR ? F.ROLE_RESPONDER : F.ROLE_INITIATOR;
      let frame;
      try {
        frame = await F.decodeFrame(new Uint8Array(data), pageRole, lookup);
      } catch {
        return; // the page's hello-ack can arrive before we know its nonce
      }
      const ctrl = frame.type === F.FRAME_TYPE_DATA ? null : dec(frame.payload);
      peer.frames.push({ ...frame, ctrl });
      if (frame.type === F.FRAME_TYPE_HELLO) {
        peer.pageHello = ctrl;
        const theirs = new Uint8Array(Buffer.from(ctrl.nonce, 'base64url'));
        const session = role === F.ROLE_INITIATOR
          ? { initNonce: myNonce, respNonce: theirs } : { initNonce: theirs, respNonce: myNonce };
        keys = F.createTransferKeyCache(roomKey, session, 8);
        // Our hello, with the extra fields, then the confirmation of the session key.
        await peer.sendFrame(F.FRAME_TYPE_HELLO, new Uint8Array(randomBytes(16)), 0,
          enc({ type: 'hello', v: 3, nonce: Buffer.from(myNonce).toString('base64url'), ...helloExtra }));
        await peer.sendCtrl(new Uint8Array(randomBytes(16)), 0, { type: 'hello-ack', v: 3 });
      }
    }).catch((err) => { peer.error = err; });
  });
  ws.on('error', () => {});
  await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
  ws.send(JSON.stringify({ type: 'join', room }));
  peer.waitFor = async (pred, what) => {
    const end = Date.now() + 5000;
    while (!pred()) {
      if (peer.error) throw peer.error;
      if (Date.now() > end) throw new Error(`app peer: timed out waiting for ${what}`);
      await sleep(10);
    }
  };
  return peer;
}

async function withPageAndPeer(helloExtra, fn) {
  const srv = await startServer();
  const keyBytes = randomBytes(32);
  const hash = roomHash(keyBytes);
  const page = loadPage({ port: srv.port, hash, forceWsRelay: true });
  let peer;
  try {
    await page.waitForLog(/^Connected to signaling broker/);
    peer = await appPeer(srv.port, keyBytes, hash.slice(1).split(':')[0], helloExtra);
    await fn({ page, peer });
  } finally {
    page.close();
    if (peer) peer.ws.terminate();
    await srv.stop();
  }
}

const errors = (page) => page.logs.filter((l) => l.cls === 'err').map((l) => l.text);

for (const [name, extra] of [
  ['caps: ["chat"]', { caps: ['chat'] }],
  ['unknown and odd extra fields', { caps: ['chat', 'future-thing', 7, null], client: 'android', nested: { a: [1, 2] } }],
  ['no extra fields (as the page itself sends)', {}],
]) {
  test(`the page verifies a peer whose hello carries ${name}`, async () => {
    await withPageAndPeer(extra, async ({ page, peer }) => {
      await page.waitForLog(/^Peer key verified, session key confirmed/);
      await page.waitForLabel(/^DROP FILE HERE OR CLICK TO SELECT/);
      assert.deepEqual(errors(page), []);
      // The page's own hello is unchanged: it offers nothing.
      assert.deepEqual(Object.keys(peer.pageHello), ['type', 'v', 'nonce']);
      // And it confirmed the session derived with our nonce.
      await peer.waitFor(() => peer.frames.some((f) => f.ctrl && f.ctrl.type === 'hello-ack'), 'the page\'s hello-ack');
    });
  });
}

test('a file from the page reaches a peer that offered chat', async () => {
  await withPageAndPeer({ caps: ['chat'] }, async ({ page, peer }) => {
    await page.waitForLabel(/^DROP FILE HERE OR CLICK TO SELECT/);
    const file = fakeFile('note.bin', 40_001);
    page.chooseFile(file);
    await peer.waitFor(() => peer.frames.some((f) => f.ctrl && f.ctrl.type === 'meta'), 'the offer');
    const meta = peer.frames.find((f) => f.ctrl && f.ctrl.type === 'meta');
    await peer.sendCtrl(meta.transferId, 1, { type: 'accept' });
    await peer.waitFor(() => peer.frames.some((f) => f.ctrl && f.ctrl.type === 'done'), 'done');
    const data = peer.frames.filter((f) => f.type === F.FRAME_TYPE_DATA);
    assert.ok(Buffer.concat(data.map((f) => Buffer.from(f.payload))).equals(file.bytes));
    await peer.sendCtrl(meta.transferId, 2, { type: 'ack', seq: data.length, done: true });
    await peer.sendCtrl(meta.transferId, 3, { type: 'complete' });
    await page.waitForLog(/^Delivered: note\.bin$/);
    assert.deepEqual(errors(page), []);
  });
});

// Why the app never sends chat to a peer that did not offer it: the page has
// no such message. With no file in flight it is refused and logged; the test
// records that behaviour so a change to it is noticed.
test('a chat frame sent to the page anyway is refused without ending the session', async () => {
  await withPageAndPeer({ caps: ['chat'] }, async ({ page, peer }) => {
    await page.waitForLabel(/^DROP FILE HERE OR CLICK TO SELECT/);
    await peer.sendCtrl(new Uint8Array(randomBytes(16)), 0, { type: 'chat', text: 'hello?' });
    await page.waitForLog(/^Decrypt\/receive error: Unexpected control frame$/);
    assert.equal(page.dropEnabled, true);
    assert.ok(!page.logs.some((l) => /hello\?/.test(l.text)));
  });
});
