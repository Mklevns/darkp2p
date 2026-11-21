// Wire format v2: evaluates the pure framing block from public/index.html in Node.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8');
const m = /\/\/ ==BEGIN darkp2p-framing==\n([\s\S]*?)\/\/ ==END darkp2p-framing==/.exec(html.replace(/\r\n?/g, '\n'));
assert.ok(m, 'index.html has the darkp2p-framing block');
assert.ok(globalThis.crypto?.subtle, 'Node provides globalThis.crypto');

const F = new Function(`${m[1]}
return {
  importRoomKey, deriveTransferKey, createTransferKeyCache, encodeFrame, decodeFrame,
  FRAME_TYPE_CTRL, FRAME_TYPE_DATA, ROLE_INITIATOR, ROLE_RESPONDER, FRAME_HEADER_LEN, FRAME_OVERHEAD,
};`)();

const rand = (n) => crypto.getRandomValues(new Uint8Array(n));
const roomKey = await F.importRoomKey(rand(32));
const keys = F.createTransferKeyCache(roomKey, 8);
const honestLookup = (transferId) => keys(transferId);
const A = F.ROLE_INITIATOR;
const B = F.ROLE_RESPONDER;

async function frame({ type = F.FRAME_TYPE_DATA, role = A, transferId = rand(16), seq = 1, payload = rand(100) } = {}) {
  const key = await keys(transferId);
  return { transferId, payload, bytes: await F.encodeFrame({ type, role, transferId, seq, payload, key }) };
}

test('header layout is 33 bytes and overhead 49', () => {
  assert.equal(F.FRAME_HEADER_LEN, 33);
  assert.equal(F.FRAME_OVERHEAD, 49);
});

test('round-trip for CTRL and DATA frames', async () => {
  for (const type of [F.FRAME_TYPE_CTRL, F.FRAME_TYPE_DATA]) {
    const { transferId, payload, bytes } = await frame({ type, seq: 7 });
    assert.equal(bytes[0], type);
    assert.equal(bytes.length, 49 + payload.length);
    for (const input of [bytes, bytes.slice().buffer]) {
      const out = await F.decodeFrame(input, A, honestLookup);
      assert.equal(out.type, type);
      assert.equal(out.seq, 7);
      assert.deepEqual(out.transferId, transferId);
      assert.deepEqual(out.payload, payload);
    }
  }
});

test('flipping any header bit fails', async () => {
  const { bytes } = await frame({ seq: 3 });
  for (let i = 0; i < F.FRAME_HEADER_LEN; i++) {
    for (let bit = 0; bit < 8; bit++) {
      const t = bytes.slice();
      t[i] ^= 1 << bit;
      await assert.rejects(F.decodeFrame(t, A, honestLookup), `byte ${i} bit ${bit} accepted`);
    }
  }
});

test('flipping a ciphertext or tag byte fails', async () => {
  const { bytes } = await frame();
  for (const i of [F.FRAME_HEADER_LEN, bytes.length - 1]) {
    const t = bytes.slice();
    t[i] ^= 0x01;
    await assert.rejects(F.decodeFrame(t, A, honestLookup), { code: 'auth' });
  }
});

test('reflection: a frame reflected back to its sender fails', async () => {
  // decodeFrame's peerRole is the role the receiver expects of the sender. The
  // sender of a reflected frame decodes it with ITS peer's role, not its own.
  for (const [sender, peer] of [[A, B], [B, A]]) {
    for (const type of [F.FRAME_TYPE_CTRL, F.FRAME_TYPE_DATA]) {
      const { bytes, payload } = await frame({ role: sender, type, seq: 0 });
      // Reflected: the sender (whose peer is `peer`) decodes its own frame.
      await assert.rejects(F.decodeFrame(bytes, peer, honestLookup), { code: 'auth' });
      // Honest delivery: the peer decodes it expecting `sender`.
      assert.deepEqual((await F.decodeFrame(bytes, sender, honestLookup)).payload, payload);
    }
  }
});

test('splice: transfer A DATA frame under transfer B with the same seq fails', async () => {
  const seq = 5;
  const a = await frame({ seq });
  const b = await frame({ seq });
  const keyB = await keys(b.transferId);

  // Receiver's active transfer is B: header still names A, key is B's.
  await assert.rejects(F.decodeFrame(a.bytes, A, async () => keyB), { code: 'auth' });

  // Attacker rewrites A's transferId field to B's.
  const spliced = a.bytes.slice();
  spliced.set(b.transferId, 1);
  await assert.rejects(F.decodeFrame(spliced, A, honestLookup), { code: 'auth' });

  // B's own frame still decodes.
  assert.deepEqual((await F.decodeFrame(b.bytes, A, honestLookup)).payload, b.payload);
});

test('distinct transfer IDs derive distinct keys', async () => {
  const p = rand(32);
  const t1 = rand(16);
  const t2 = rand(16);
  const k1 = await F.deriveTransferKey(roomKey, t1);
  const k2 = await F.deriveTransferKey(roomKey, t2);
  const iv = new Uint8Array(12);
  const c1 = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, k1, p));
  const c2 = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, k2, p));
  assert.notDeepEqual(c1, c2);
});

test('a frame under a different room key fails', async () => {
  const { bytes } = await frame();
  const other = F.createTransferKeyCache(await F.importRoomKey(rand(32)), 8);
  await assert.rejects(F.decodeFrame(bytes, A, (id) => other(id)), { code: 'auth' });
});

test('legacy v1 frame types are reported as legacy', async () => {
  for (const type of [1, 2]) {
    const t = rand(64);
    t[0] = type;
    await assert.rejects(F.decodeFrame(t, A, honestLookup), { code: 'legacy' });
  }
});

test('truncated frames and unknown types are malformed', async () => {
  const { bytes } = await frame({ payload: new Uint8Array(0) });
  assert.equal(bytes.length, 49);
  await F.decodeFrame(bytes, A, honestLookup);
  await assert.rejects(F.decodeFrame(bytes.subarray(0, 48), A, honestLookup), { code: 'malformed' });
  const t = bytes.slice();
  t[0] = 0x13;
  await assert.rejects(F.decodeFrame(t, A, honestLookup), { code: 'malformed' });
});

test('keyLookup can refuse a frame before decryption', async () => {
  const { bytes } = await frame();
  const refuse = () => Promise.reject(Object.assign(new Error('no'), { code: 'rejected' }));
  await assert.rejects(F.decodeFrame(bytes, A, refuse), { code: 'rejected' });
});

test('16000-byte payload gives a 16049-byte frame (<= 16384)', async () => {
  const { bytes } = await frame({ payload: rand(16000) });
  assert.equal(bytes.length, 16049);
  assert.ok(bytes.length <= 16384);
});

test('key cache holds at most 8 transfer keys (LRU)', async () => {
  // A hit returns the same CryptoKey object; a re-derivation returns a new one.
  const cache = F.createTransferKeyCache(roomKey, 8);
  const ids = Array.from({ length: 9 }, () => rand(16));
  const original = [];
  for (let i = 0; i < 8; i++) original.push(await cache(ids[i]));
  assert.equal(await cache(ids[0]), original[0], 'hit returns the cached key');
  await cache(ids[8]); // full: evicts ids[1], the least recently used
  assert.equal(await cache(ids[0]), original[0], 'recently used key survives');
  for (let i = 2; i < 8; i++) assert.equal(await cache(ids[i]), original[i], `key ${i} survives`);
  assert.notEqual(await cache(ids[1]), original[1], 'LRU key was evicted and re-derived');
});

test('encodeFrame validates its inputs', async () => {
  const key = await keys(rand(16));
  const base = { type: F.FRAME_TYPE_DATA, role: A, transferId: rand(16), seq: 1, payload: rand(4), key };
  await assert.rejects(F.encodeFrame({ ...base, type: 1 }));
  await assert.rejects(F.encodeFrame({ ...base, role: 3 }));
  await assert.rejects(F.encodeFrame({ ...base, transferId: rand(15) }));
  await assert.rejects(F.encodeFrame({ ...base, seq: 2 ** 32 }));
  await assert.rejects(F.encodeFrame({ ...base, seq: -1 }));
  await assert.rejects(F.importRoomKey(rand(31)));
});
