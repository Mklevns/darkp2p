// Shared darkp2p/v3 test vectors (test/vectors/v3.json), used by other clients.
// The committed file must be what test/vectors/export.mjs builds from the page
// today, and an implementation that shares no code with the page (node:crypto)
// must agree with every key and frame in it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createDecipheriv, hkdfSync } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
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

// ---------------------------------------------------------------------------
// The other direction: frames produced by the Android client's :protocol module
// (test/vectors/android-v3.json), read by the page's own code.
// ---------------------------------------------------------------------------

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8').replace(/\r\n?/g, '\n');
const block = /\/\/ ==BEGIN darkp2p-framing==\n([\s\S]*?)\/\/ ==END darkp2p-framing==/.exec(html);
const F = new Function(`${block[1]}
return { importRoomKey, deriveHelloKey, createTransferKeyCache, decodeFrame, RxStateMachine, TxWindow,
  FRAME_TYPE_HELLO, FRAME_TYPE_CTRL, FRAME_TYPE_DATA, ROLE_INITIATOR, ROLE_RESPONDER };`)();
const android = JSON.parse(readFileSync(join(ROOT, 'test', 'vectors', 'android-v3.json'), 'utf8'));

async function decodeAndroid() {
  const roomKey = await F.importRoomKey(new Uint8Array(unhex(android.roomKey)));
  const keys = F.createTransferKeyCache(roomKey,
    { initNonce: new Uint8Array(unhex(android.initNonce)), respNonce: new Uint8Array(unhex(android.respNonce)) }, 64);
  const lookup = (transferId, { type }) => (type === F.FRAME_TYPE_HELLO ? F.deriveHelloKey(roomKey, transferId) : keys(transferId));
  const out = [];
  for (const v of android.frames) {
    // decodeFrame's role argument is the sender's.
    const frame = await F.decodeFrame(new Uint8Array(unhex(v.frame)), v.role, lookup);
    assert.equal(frame.type, v.type);
    assert.equal(frame.seq, v.seq);
    assert.equal(Buffer.from(frame.transferId).toString('hex'), v.transferId);
    assert.equal(Buffer.from(frame.payload).toString('hex'), v.payload);
    // The wrong sender role must fail (reflection).
    await assert.rejects(F.decodeFrame(new Uint8Array(unhex(v.frame)), v.role === 1 ? 2 : 1, lookup), { code: 'auth' });
    const ctrl = v.json === undefined ? null : JSON.parse(new TextDecoder().decode(frame.payload));
    if (v.json !== undefined) assert.equal(new TextDecoder().decode(frame.payload), v.json);
    out.push({ ...v, ctrl, length: frame.payload.length });
  }
  return out;
}

test('android-v3.json: the page decrypts every Android frame', async () => {
  assert.equal(android.format, 'darkp2p/v3');
  const frames = await decodeAndroid();
  assert.ok(frames.length >= 90);
  const hellos = frames.filter((f) => f.type === F.FRAME_TYPE_HELLO);
  assert.equal(hellos.length, 2);
  for (const h of hellos) {
    assert.deepEqual(Object.keys(h.ctrl), ['type', 'v', 'nonce']);
    assert.equal(h.ctrl.v, 3);
    assert.equal(Buffer.from(h.ctrl.nonce, 'base64url').toString('hex'), h.role === 1 ? android.initNonce : android.respNonce);
    assert.ok(!/[=+/]/.test(h.ctrl.nonce));
  }
  // An ack carries "done" only when it is the final one, and then it is `true`.
  for (const f of frames.filter((x) => x.ctrl && x.ctrl.type === 'ack')) {
    assert.ok(!('done' in f.ctrl) || f.ctrl.done === true);
  }
  assert.ok(frames.some((f) => f.type === F.FRAME_TYPE_DATA && f.length === 16000 && f.frame.length / 2 === 16049));
});

test('android-v3.json: the page\'s state machines accept the Android transfer', async () => {
  const frames = (await decodeAndroid()).filter((f) => f.transferId === android.transfer.transferId);
  const forward = frames.filter((f) => f.role === F.ROLE_INITIATOR);
  const replies = frames.filter((f) => f.role === F.ROLE_RESPONDER);

  // Receiving side: the page reads Android's meta, chunks and done.
  const meta = F.RxStateMachine.parseMeta(forward[0].ctrl);
  assert.equal(forward[0].seq, 0);
  assert.equal(meta.size, android.transfer.size);
  assert.equal(meta.chunks, android.transfer.chunks);
  assert.equal(meta.mime, 'application/x-test');
  assert.ok(meta.name.includes('文件') && !/[\\/]/.test(meta.name));
  const rx = new F.RxStateMachine(meta);
  rx.accept();
  const acks = [];
  for (const f of forward.slice(1)) {
    if (f.type === F.FRAME_TYPE_DATA) {
      const { ack } = rx.onData(f.seq, f.length);
      if (ack !== null) acks.push({ type: 'ack', seq: ack });
    } else {
      assert.equal(f.ctrl.type, 'done');
      acks.push({ type: 'ack', seq: rx.onDone(f.seq, f.ctrl).ack, done: true });
    }
  }
  rx.complete();
  assert.equal(rx.phase, 'complete');

  // Sending side: the page's sender accepts Android's replies, in Android's numbering.
  const tx = new F.TxWindow({ chunks: android.transfer.chunks, now: 0 });
  assert.deepEqual(replies.map((r) => r.seq), [1, 2, 3, 4]);
  assert.equal(tx.onReply(replies[0].seq, replies[0].ctrl, 1), 'accept');
  for (let i = 0; i < android.transfer.chunks; i++) tx.nextDataSeq(2);
  assert.equal(tx.onReply(replies[1].seq, replies[1].ctrl, 3), 'ack');
  tx.doneSeq(4);
  assert.equal(tx.onReply(replies[2].seq, replies[2].ctrl, 5), 'ack');
  assert.equal(tx.onReply(replies[3].seq, replies[3].ctrl, 6), 'complete');
  assert.equal(tx.phase, 'delivered');
  // The acks Android sent are the ones the page would have sent for the same chunks.
  assert.deepEqual([replies[1].ctrl, replies[2].ctrl], acks);
});

test('android-v3.json: decline and abort frames carry reasons the page knows', async () => {
  const frames = await decodeAndroid();
  const reasons = (type) => frames.filter((f) => f.ctrl && f.ctrl.type === type).map((f) => f.ctrl.reason);
  assert.deepEqual([...new Set(reasons('decline'))].sort(), ['busy', 'size', 'user']);
  assert.deepEqual([...new Set(reasons('abort'))].sort(), ['disk', 'integrity', 'timeout', 'user']);
  for (const f of frames.filter((x) => x.ctrl && x.ctrl.type === 'decline')) {
    const tx = new F.TxWindow({ chunks: 1, now: 0 });
    assert.equal(tx.onReply(f.seq, f.ctrl, 1), 'decline');
    assert.equal(tx.declineReason, f.ctrl.reason);
  }
  for (const f of frames.filter((x) => x.ctrl && x.ctrl.type === 'abort' && x.role === F.ROLE_RESPONDER)) {
    const tx = new F.TxWindow({ chunks: 1, now: 0 });
    assert.equal(tx.onReply(f.seq, f.ctrl, 1), 'abort');
    assert.equal(tx.abortReason, f.ctrl.reason);
  }
});
