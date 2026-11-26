// Shared darkp2p/v3 test vectors (test/vectors/v3.json), used by other clients.
// The committed file must be what test/vectors/export.mjs builds from the page
// today, and an implementation that shares no code with the page (node:crypto)
// must agree with every key and frame in it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createDecipheriv, hkdfSync } from 'node:crypto';
import { buildVectors, serialize, VECTORS_PATH } from './vectors/export.mjs';

const committedText = readFileSync(VECTORS_PATH, 'utf8');
const committed = JSON.parse(committedText);
const unhex = (str) => Buffer.from(str, 'hex');

test('v3.json is up to date (run `node test/vectors/export.mjs` after a wire change)', async () => {
  const fresh = await buildVectors();
  assert.deepEqual(committed, JSON.parse(JSON.stringify(fresh)));
  assert.equal(committedText, serialize(fresh));
});

test('HKDF vectors match node:crypto', () => {
  assert.ok(committed.hkdf.length >= 9);
  for (const v of committed.hkdf) {
    const okm = Buffer.from(hkdfSync('sha256', unhex(v.ikm), unhex(v.salt), unhex(v.info), 32));
    assert.equal(okm.toString('hex'), v.okm, v.name);
    if (v.kind === 'hello') assert.equal(v.salt, v.transferId);
    if (v.kind === 'transfer') assert.equal(v.salt, v.transferId + v.initNonce + v.respNonce);
  }
  // RFC 5869 A.1: the published OKM starts with these 32 bytes.
  assert.equal(committed.hkdf[0].okm, '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf');
});

// The frame layout, written out independently of the page.
function openFrame(frame, role, key) {
  const bytes = unhex(frame);
  const aad = Buffer.concat([Buffer.from('DP3'), bytes.subarray(0, 1), Buffer.from([role]), bytes.subarray(1, 21)]);
  const decipher = createDecipheriv('aes-256-gcm', unhex(key), bytes.subarray(21, 33));
  decipher.setAAD(aad);
  decipher.setAuthTag(bytes.subarray(bytes.length - 16));
  const payload = Buffer.concat([decipher.update(bytes.subarray(33, bytes.length - 16)), decipher.final()]);
  return { type: bytes[0], transferId: bytes.subarray(1, 17).toString('hex'), seq: bytes.readUInt32BE(17), iv: bytes.subarray(21, 33).toString('hex'), payload };
}

test('frame vectors decrypt with node:crypto under the listed keys', () => {
  const { roomKey, initNonce, respNonce, frames } = committed.frames;
  assert.ok(frames.length >= 20);
  for (const v of frames) {
    const salt = v.type === 0x20 ? v.transferId : v.transferId + initNonce + respNonce;
    const info = v.type === 0x20 ? 'darkp2p/v3/hello' : 'darkp2p/v3/transfer';
    const key = Buffer.from(hkdfSync('sha256', unhex(roomKey), unhex(salt), info, 32)).toString('hex');
    assert.equal(key, v.key, v.name);
    const out = openFrame(v.frame, v.role, v.key);
    assert.deepEqual(
      { type: out.type, transferId: out.transferId, seq: out.seq, iv: out.iv, payload: out.payload.toString('hex') },
      { type: v.type, transferId: v.transferId, seq: v.seq, iv: v.iv, payload: v.payload }, v.name);
    assert.equal(v.frame.length / 2, 49 + v.payload.length / 2, v.name);
    if (v.json !== undefined) assert.equal(out.payload.toString('utf8'), v.json, v.name);
    assert.throws(() => openFrame(v.frame, v.role === 1 ? 2 : 1, v.key), v.name);
  }
});

test('AAD vectors are "DP3" | type | role | transferId | seq', () => {
  for (const v of committed.aad) {
    const seq = Buffer.alloc(4);
    seq.writeUInt32BE(v.seq);
    assert.equal(v.aad, Buffer.concat([Buffer.from('DP3'), Buffer.from([v.type, v.role]), unhex(v.transferId), seq]).toString('hex'));
  }
});

test('refused-frame vectors cover every bit and every truncation of the base frame', () => {
  const { base, bitFlips, truncations, others } = committed.framesBad;
  const length = base.length / 2;
  assert.equal(bitFlips.length, length * 8);
  assert.equal(truncations.length, length);
  for (const [, , code] of bitFlips) assert.ok(['auth', 'malformed', 'legacy'].includes(code));
  // No change to the frame is accepted.
  assert.ok(!bitFlips.some(([, , code]) => code === 'ok'));
  assert.ok(!truncations.some(([, code]) => code === 'ok'));
  assert.ok(!others.some((o) => o.code === 'ok'));
});

test('vector groups are present and non-trivial', () => {
  assert.equal(committed.format, 'darkp2p/v3');
  assert.equal(committed.constants.CHUNK_SIZE, 16000);
  assert.equal(committed.constants.FRAME_OVERHEAD, 49);
  assert.ok(committed.links.some((l) => l.valid) && committed.links.some((l) => !l.valid));
  assert.ok(committed.meta.some((m) => m.error) && committed.meta.some((m) => m.meta));
  for (const group of ['rxTraces', 'txTraces']) {
    assert.ok(committed[group].length >= 20);
    assert.ok(committed[group].some((t) => t.steps.some((s) => s.error)));
  }
  assert.ok(committed.transferKeyCache.every((c) => c.hits.length === c.order.length));
  assert.ok(committed.candidates.some((c) => c.exposesIp) && committed.candidates.some((c) => !c.exposesIp));
});
