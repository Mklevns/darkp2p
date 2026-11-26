// Exports darkp2p/v3 test vectors for other clients (the Android :protocol module).
//
// Every vector is produced by the page's own code: the darkp2p-framing block and
// the link parser are cut out of public/index.html and evaluated here, with a
// deterministic `crypto.getRandomValues` so the output is reproducible.
//
//   node test/vectors/export.mjs          rewrites test/vectors/v3.json
//
// test/vectors.test.mjs fails when v3.json no longer matches what this builds.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHmac, webcrypto } from 'node:crypto';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
export const VECTORS_PATH = join(HERE, 'v3.json');
const SEED = 'darkp2p/v3 test vectors';

const hex = (bytes) => Buffer.from(bytes).toString('hex');
const unhex = (str) => new Uint8Array(Buffer.from(str, 'hex'));
const utf8 = (str) => new Uint8Array(Buffer.from(str, 'utf8'));

// HMAC-SHA256(seed, label | counter) stream: independent of call order elsewhere.
function drbg(label) {
  let counter = 0;
  let pool = Buffer.alloc(0);
  const bytes = (n) => {
    while (pool.length < n) {
      const block = createHmac('sha256', SEED).update(`${label}|${counter}`).digest();
      counter += 1;
      pool = Buffer.concat([pool, block]);
    }
    const out = new Uint8Array(pool.subarray(0, n));
    pool = pool.subarray(n);
    return out;
  };
  return {
    bytes,
    // Uniform integer in [0, n).
    int(n) {
      return Number(Buffer.from(bytes(6)).readUIntBE(0, 6) % n);
    },
    // In [0, 1).
    float() {
      return Buffer.from(bytes(4)).readUInt32BE(0) / 2 ** 32;
    },
    fill(array) {
      array.set(bytes(array.length));
      return array;
    },
  };
}

function loadPage() {
  const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8').replace(/\r\n?/g, '\n');
  const block = /\/\/ ==BEGIN darkp2p-framing==\n([\s\S]*?)\/\/ ==END darkp2p-framing==/.exec(html);
  if (!block) throw new Error('index.html has no darkp2p-framing block');
  // Top-level functions of the inline script end at the first "    }" line.
  const fn = (name) => {
    const m = new RegExp(`\\n    function ${name}\\([^)]*\\) \\{\\n[\\s\\S]*?\\n    \\}\\n`).exec(html);
    if (!m) throw new Error(`index.html has no function ${name}`);
    return m[0];
  };
  return { framing: block[1], link: fn('toBase64Url') + fn('fromBase64Url') + fn('initRoomAndKey') };
}

const PAGE = loadPage();

// The framing block with `crypto` replaced: real SubtleCrypto, scripted randomness.
function framing(random) {
  const shim = { subtle: webcrypto.subtle, getRandomValues: (a) => random.fill(a) };
  // eslint-disable-next-line no-new-func
  return new Function('crypto', `${PAGE.framing}
return {
  importRoomKey, deriveTransferKey, deriveHelloKey, createTransferKeyCache, encodeFrame, decodeFrame, buildAad,
  RxStateMachine, TxWindow, RelayBudget, reconnectDelay, candidateExposesIp,
  FRAME_TYPE_HELLO, FRAME_TYPE_CTRL, FRAME_TYPE_DATA, LEGACY_FRAME_TYPES, ROLE_INITIATOR, ROLE_RESPONDER,
  FRAME_HEADER_LEN, FRAME_OVERHEAD, FRAME_IV_LEN, FRAME_TAG_LEN, TRANSFER_ID_LEN, SESSION_NONCE_LEN, ROOM_KEY_LEN,
  ACK_EVERY, TX_WINDOW, RAM_MODE_MAX_BYTES, ACCEPT_TIMEOUT_MS, ACK_STALL_MS, COMPLETE_TIMEOUT_MS, MAX_FILE_CHUNKS,
  RECONNECT_BASE_MS, RECONNECT_MAX_MS, RECONNECT_STABLE_MS, RELAY_CTRL_RESERVE, RELAY_MIN_REMAINING,
  RELAY_BUSY_RETRY_MS, ABORT_REASONS, DECLINE_REASONS,
};`)(shim);
}

// The page's link parser on a given fragment. `isNew` means the page ignored
// the fragment and made a new room.
function parseLink(hash) {
  const random = drbg(`link|${hash}`);
  // eslint-disable-next-line no-new-func
  return new Function('location', 'crypto', 'btoa', 'atob', `${PAGE.link}
const r = initRoomAndKey();
return { roomId: r.roomId, keyBytes: r.keyBytes, isNew: r.isNew === true, fragment: r.roomId + ':' + toBase64Url(r.keyBytes) };`)(
    { hash }, { getRandomValues: (a) => random.fill(a) }, btoa, atob);
}

// HKDF output as raw bytes. The page's keys are not extractable, so this
// recomputes them; frames() proves they are the keys the page encrypts with.
async function hkdfBits(ikm, salt, info) {
  const base = await webcrypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  return new Uint8Array(await webcrypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, base, 256));
}

async function gcmEncrypt(rawKey, iv, aad, plaintext) {
  const key = await webcrypto.subtle.importKey('raw', rawKey, 'AES-GCM', false, ['encrypt']);
  return new Uint8Array(await webcrypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, key, plaintext));
}

function concat(...parts) {
  return new Uint8Array(Buffer.concat(parts.map((p) => Buffer.from(p))));
}

const INFO_HELLO = utf8('darkp2p/v3/hello');
const INFO_TRANSFER = utf8('darkp2p/v3/transfer');

// ---------------------------------------------------------------------------

function constants(F) {
  const out = {};
  for (const [name, value] of Object.entries(F)) {
    if (typeof value === 'number' || Array.isArray(value)) out[name] = value;
  }
  // Constants of the inline script outside the framing block.
  const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8');
  for (const name of ['CHUNK_SIZE', 'HIGH_WATER', 'HELLO_TIMEOUT_MS', 'TRANSFER_KEY_CACHE_MAX', 'SEEN_TRANSFER_IDS_MAX',
    'ENDED_TRANSFER_IDS_MAX', 'RX_MAX_PENDING_CHUNKS', 'RX_MAX_PENDING_CTRL', 'TX_WATCHDOG_MS', 'FULL_RETRY_MS']) {
    const m = new RegExp(`\\n    const ${name} = ([0-9_]+);`).exec(html);
    if (!m) throw new Error(`index.html has no numeric constant ${name}`);
    out[name] = Number(m[1].replace(/_/g, ''));
  }
  return out;
}

function links() {
  const room = 'a1b2c3d4e5f60718293a4b5c6d7e8f90';
  const key = Buffer.from(drbg('link-key').bytes(32));
  const b64u = key.toString('base64url');
  const cases = [
    ['canonical', `#${room}:${b64u}`],
    ['uppercase room id', `#${room.toUpperCase()}:${b64u}`],
    ['shortest room id (16 hex)', `#${room.slice(0, 16)}:${b64u}`],
    ['longest room id (64 hex)', `#${room}${room}:${b64u}`],
    ['room id too short (15 hex)', `#${room.slice(0, 15)}:${b64u}`],
    ['room id too long (65 hex)', `#${room}${room}a:${b64u}`],
    ['room id not hex', `#${room.slice(0, 31)}g:${b64u}`],
    ['key too short (42 chars)', `#${room}:${b64u.slice(0, 42)}`],
    ['key with one extra char (44 chars, 33 bytes)', `#${room}:${b64u}A`],
    ['key with padding (44 chars)', `#${room}:${key.toString('base64url')}=`],
    ['key in standard base64 alphabet', `#${room}:${Buffer.alloc(32, 0xfb).toString('base64').slice(0, 43)}`],
    ['key with non-zero trailing bits', `#${room}:${b64u.slice(0, 42)}${'BCD'[key[31] & 1]}`],
    ['key with an invalid character', `#${room}:${b64u.slice(0, 42)}*`],
    ['third part', `#${room}:${b64u}:x`],
    ['no key', `#${room}`],
    ['empty fragment', ''],
    ['no leading # (the page always drops the first character)', `${room}:${b64u}`],
    ['query string in fragment', `#${room}:${b64u}?NP_id=1`],
  ];
  return cases.map(([name, hash]) => {
    const r = parseLink(hash);
    return r.isNew
      ? { name, hash, valid: false }
      : { name, hash, valid: true, roomId: r.roomId, key: hex(r.keyBytes), fragment: r.fragment };
  });
}

async function hkdf() {
  const r = drbg('hkdf');
  const out = [];
  // RFC 5869 test cases 1-3 (SHA-256), truncated to the 32 bytes HKDF yields here.
  const rfc = [
    ['0b'.repeat(22), '000102030405060708090a0b0c', 'f0f1f2f3f4f5f6f7f8f9'],
    [hex(Uint8Array.from({ length: 80 }, (_, i) => i)), hex(Uint8Array.from({ length: 80 }, (_, i) => 0x60 + i)),
      hex(Uint8Array.from({ length: 80 }, (_, i) => 0xb0 + i))],
    ['0b'.repeat(22), '', ''],
  ];
  for (const [i, [ikm, salt, info]] of rfc.entries()) {
    out.push({ name: `RFC 5869 test case ${i + 1} (first 32 bytes)`, ikm, salt, info,
      okm: hex(await hkdfBits(unhex(ikm), unhex(salt), unhex(info))) });
  }
  for (let i = 0; i < 4; i++) {
    const roomKey = r.bytes(32);
    const transferId = r.bytes(16);
    const initNonce = r.bytes(32);
    const respNonce = r.bytes(32);
    out.push({ name: `hello key ${i}`, kind: 'hello', roomKey: hex(roomKey), transferId: hex(transferId),
      ikm: hex(roomKey), salt: hex(transferId), info: hex(INFO_HELLO),
      okm: hex(await hkdfBits(roomKey, transferId, INFO_HELLO)) });
    const salt = concat(transferId, initNonce, respNonce);
    out.push({ name: `transfer key ${i}`, kind: 'transfer', roomKey: hex(roomKey), transferId: hex(transferId),
      initNonce: hex(initNonce), respNonce: hex(respNonce),
      ikm: hex(roomKey), salt: hex(salt), info: hex(INFO_TRANSFER),
      okm: hex(await hkdfBits(roomKey, salt, INFO_TRANSFER)) });
  }
  return out;
}

function aad(F) {
  const r = drbg('aad');
  const out = [];
  for (const type of [F.FRAME_TYPE_HELLO, F.FRAME_TYPE_CTRL, F.FRAME_TYPE_DATA]) {
    for (const role of [F.ROLE_INITIATOR, F.ROLE_RESPONDER]) {
      for (const seq of [0, 1, 0x01020304, 0xffffffff]) {
        const transferId = r.bytes(16);
        out.push({ type, role, transferId: hex(transferId), seq, aad: hex(F.buildAad(type, role, transferId, seq)) });
      }
    }
  }
  return out;
}

// The control payloads exactly as the page serializes them (same key order).
function ctrlPayloads(nonce) {
  const b64u = Buffer.from(nonce).toString('base64url');
  return [
    { type: 'hello', v: 3, nonce: b64u },
    { type: 'hello-ack', v: 3 },
    { type: 'meta', name: 'report.pdf', size: 40_000, mime: 'application/pdf', chunks: 3 },
    { type: 'meta', name: 'ünïcødé – 文件.bin', size: 0, mime: '', chunks: 0 },
    { type: 'done', chunks: 3, size: 40_000 },
    { type: 'accept' },
    { type: 'decline', reason: 'user' },
    { type: 'decline', reason: 'size' },
    { type: 'decline', reason: 'busy' },
    { type: 'ack', seq: 64 },
    { type: 'ack', seq: 3, done: true },
    { type: 'complete' },
    { type: 'abort', reason: 'user' },
    { type: 'abort', reason: 'integrity' },
    { type: 'abort', reason: 'disk' },
    { type: 'abort', reason: 'timeout' },
  ];
}

// One session with frames in both directions. Each frame carries the raw key it
// was encrypted under; a recomputation with that raw key must give the same bytes.
async function frames() {
  const r = drbg('frames');
  const roomKeyBytes = r.bytes(32);
  const initNonce = r.bytes(32);
  const respNonce = r.bytes(32);
  const F = framing(drbg('frames-iv'));
  const roomKey = await F.importRoomKey(roomKeyBytes);
  const keys = F.createTransferKeyCache(roomKey, { initNonce, respNonce }, 64);
  const out = [];

  const add = async (name, { type, role, transferId, seq, payload, json }) => {
    const hello = type === F.FRAME_TYPE_HELLO;
    const key = hello ? await F.deriveHelloKey(roomKey, transferId) : await keys(transferId);
    const rawKey = hello
      ? await hkdfBits(roomKeyBytes, transferId, INFO_HELLO)
      : await hkdfBits(roomKeyBytes, concat(transferId, initNonce, respNonce), INFO_TRANSFER);
    const bytes = await F.encodeFrame({ type, role, transferId, seq, payload, key });
    const iv = bytes.slice(F.FRAME_HEADER_LEN - F.FRAME_IV_LEN, F.FRAME_HEADER_LEN);
    const expected = await gcmEncrypt(rawKey, iv, F.buildAad(type, role, transferId, seq), payload);
    if (hex(expected) !== hex(bytes.subarray(F.FRAME_HEADER_LEN))) {
      throw new Error(`${name}: the recomputed key is not the key the page encrypts with`);
    }
    const entry = { name, type, role, transferId: hex(transferId), seq, key: hex(rawKey), iv: hex(iv) };
    if (json !== undefined) entry.json = json;
    entry.payload = hex(payload);
    entry.frame = hex(bytes);
    out.push(entry);
  };

  const A = F.ROLE_INITIATOR;
  const B = F.ROLE_RESPONDER;
  for (const [i, obj] of ctrlPayloads(initNonce).entries()) {
    const json = JSON.stringify(obj);
    const type = obj.type === 'hello' ? F.FRAME_TYPE_HELLO : F.FRAME_TYPE_CTRL;
    const reply = ['accept', 'decline', 'ack', 'complete'].includes(obj.type);
    await add(`ctrl ${obj.type}${obj.reason ? ` (${obj.reason})` : ''}${obj.done ? ' (final)' : ''}`, {
      type, role: reply ? B : A, transferId: r.bytes(16), seq: obj.type === 'done' ? 4 : reply ? i : 0,
      payload: utf8(json), json,
    });
  }
  const transferId = r.bytes(16);
  for (const [name, seq, size] of [['data: 1 byte', 1, 1], ['data: 255 bytes', 2, 255], ['data: empty payload', 3, 0],
    ['data: full chunk (16000 bytes)', 4, 16000], ['data: highest seq', 0xffffffff, 32]]) {
    await add(name, { type: F.FRAME_TYPE_DATA, role: A, transferId, seq, payload: r.bytes(size) });
  }
  await add('data: sent by the responder', { type: F.FRAME_TYPE_DATA, role: B, transferId: r.bytes(16), seq: 1, payload: r.bytes(48) });
  return { roomKey: hex(roomKeyBytes), initNonce: hex(initNonce), respNonce: hex(respNonce), frames: out };
}

// Frames that must be refused, and how. decode = decodeFrame(frame, peerRole,
// lookup) where lookup returns the session's transfer key for the transfer ID in
// the frame, whatever its type.
async function framesBad() {
  const r = drbg('frames-bad');
  const roomKeyBytes = r.bytes(32);
  const session = { initNonce: r.bytes(32), respNonce: r.bytes(32) };
  const F = framing(drbg('frames-bad-iv'));
  const roomKey = await F.importRoomKey(roomKeyBytes);
  const keys = F.createTransferKeyCache(roomKey, session, 1024);
  const A = F.ROLE_INITIATOR;
  const B = F.ROLE_RESPONDER;
  const transferId = r.bytes(16);
  const base = await F.encodeFrame({
    type: F.FRAME_TYPE_DATA, role: A, transferId, seq: 7, payload: r.bytes(24), key: await keys(transferId),
  });

  const code = async (bytes, peerRole) => {
    try {
      await F.decodeFrame(bytes, peerRole, (id) => keys(id));
      return 'ok';
    } catch (err) {
      if (err.name !== 'FrameError') throw err;
      return err.code;
    }
  };
  if (await code(base, A) !== 'ok') throw new Error('base frame does not decode');

  const bitFlips = [];
  for (let i = 0; i < base.length; i++) {
    for (let bit = 0; bit < 8; bit++) {
      const t = base.slice();
      t[i] ^= 1 << bit;
      bitFlips.push([i, bit, await code(t, A)]);
    }
  }
  const truncations = [];
  for (let len = 0; len < base.length; len++) truncations.push([len, await code(base.slice(0, len), A)]);

  const others = [];
  const other = async (name, bytes, peerRole) => others.push({ name, frame: hex(bytes), peerRole, code: await code(bytes, peerRole) });
  await other('reflected: decoded with the wrong sender role', base, B);
  for (const legacy of F.LEGACY_FRAME_TYPES) {
    const t = base.slice();
    t[0] = legacy;
    await other(`legacy frame type 0x${legacy.toString(16).padStart(2, '0')}`, t, A);
    await other(`legacy frame type 0x${legacy.toString(16).padStart(2, '0')}, one byte long`, t.slice(0, 1), A);
  }
  for (const unknown of [0x00, 0x03, 0x1f, 0x23, 0xff]) {
    const t = base.slice();
    t[0] = unknown;
    await other(`unknown frame type 0x${unknown.toString(16).padStart(2, '0')}`, t, A);
  }
  await other('trailing byte appended', concat(base, [0]), A);
  const otherKey = await F.importRoomKey(r.bytes(32));
  const wrongKeyFrame = await F.encodeFrame({
    type: F.FRAME_TYPE_DATA, role: A, transferId, seq: 7, payload: r.bytes(24),
    key: await F.deriveTransferKey(otherKey, transferId, session),
  });
  await other('encrypted under another room key', wrongKeyFrame, A);
  const swapped = await F.encodeFrame({
    type: F.FRAME_TYPE_DATA, role: A, transferId, seq: 7, payload: r.bytes(24),
    key: await F.deriveTransferKey(roomKey, transferId, { initNonce: session.respNonce, respNonce: session.initNonce }),
  });
  await other('session nonces swapped', swapped, A);

  return {
    roomKey: hex(roomKeyBytes), initNonce: hex(session.initNonce), respNonce: hex(session.respNonce),
    peerRole: A, base: hex(base), bitFlips, truncations, others,
  };
}

// RxStateMachine.parseMeta on JSON text, as the page calls it: JSON.parse first.
function meta(F) {
  const inputs = [
    '{"type":"meta","name":"a.txt","size":1,"mime":"text/plain","chunks":1}',
    '{"type":"meta","name":"a.txt","size":16000,"mime":"text/plain","chunks":1}',
    '{"type":"meta","name":"a.txt","size":16001,"mime":"text/plain","chunks":2}',
    '{"type":"meta","name":"empty","size":0,"mime":"","chunks":0}',
    '{"type":"meta","name":"empty","size":0,"mime":"","chunks":1}',
    '{"type":"meta","name":"a","size":5,"mime":"x/y","chunks":0}',
    '{"type":"meta","name":"a","size":5,"mime":"x/y","chunks":5}',
    '{"type":"meta","name":"a","size":5,"mime":"x/y","chunks":6}',
    '{"type":"meta","name":"a","size":-1,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":1.5,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":3.0,"mime":"x/y","chunks":1.0}',
    '{"type":"meta","name":"a","size":3e0,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":"3","mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":9007199254740991,"mime":"x/y","chunks":4294967293}',
    '{"type":"meta","name":"a","size":9007199254740991,"mime":"x/y","chunks":4294967294}',
    '{"type":"meta","name":"a","size":9007199254740992,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":3,"mime":"x/y"}',
    '{"type":"meta","name":"a","mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":null,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"../../etc/passwd","size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"C:\\\\dir\\\\file","size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"","size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":null,"size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":0,"size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":42,"size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":true,"size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":false,"size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","name":"a","size":3,"chunks":1}',
    '{"type":"meta","name":"a","size":3,"mime":7,"chunks":1}',
    '{"type":"meta","name":"a","size":3,"mime":null,"chunks":1}',
    '{"type":"meta","name":"a","size":3,"mime":"x/y","chunks":1,"extra":{"ignored":true}}',
    '{"type":"done","name":"a","size":3,"mime":"x/y","chunks":1}',
    '{"name":"a","size":3,"mime":"x/y","chunks":1}',
    '{"type":"meta","type":"done","name":"a","size":3,"mime":"x/y","chunks":1}',
  ];
  return inputs.map((json) => {
    try {
      return { json, meta: F.RxStateMachine.parseMeta(JSON.parse(json)) };
    } catch (err) {
      if (err.name !== 'ProtocolError') throw err;
      return { json, error: err.reason };
    }
  });
}

// Runs scripted calls on one object and records, per call, what it returned or
// the abort reason it threw, and the object's state afterwards.
function trace(make, snapshot, steps) {
  let target;
  const out = [];
  for (const [op, ...args] of steps) {
    const step = { op, args };
    try {
      if (op === 'new') target = make(...args);
      else {
        const ret = target[op](...args);
        if (ret !== undefined) step.ret = ret;
      }
    } catch (err) {
      if (err.name !== 'ProtocolError') throw err;
      step.error = err.reason;
    }
    if (target) step.state = snapshot(target);
    out.push(step);
  }
  return out;
}

function rxTraces(F) {
  const snapshot = (m) => ({
    phase: m.phase, active: m.active, expectedSeq: m.expectedSeq, chunks: m.chunks, bytes: m.bytes, replySeq: m.replySeq,
  });
  const run = (name, steps) => ({ name, steps: trace((meta) => new F.RxStateMachine(meta), snapshot, steps) });
  const metaOf = (size, chunks) => ({ name: 'f', size, mime: 'application/octet-stream', chunks });
  const out = [];

  out.push(run('three chunks, accepted and completed', [
    ['new', metaOf(40000, 3)], ['accept'], ['nextReplySeq'],
    ['onData', 1, 16000], ['onData', 2, 16000], ['onData', 3, 8000],
    ['onDone', 4, { type: 'done', chunks: 3, size: 40000 }], ['nextReplySeq'], ['complete'], ['nextReplySeq'], ['abort'],
  ]));
  out.push(run('empty file', [
    ['new', metaOf(0, 0)], ['accept'], ['onDone', 1, { type: 'done', chunks: 0, size: 0 }], ['complete'],
  ]));
  out.push(run('declined', [['new', metaOf(10, 1)], ['decline'], ['accept'], ['onData', 1, 10], ['abort']]));
  out.push(run('data before accept', [['new', metaOf(10, 1)], ['onData', 1, 10], ['accept'], ['onData', 1, 10]]));
  out.push(run('out-of-order, empty, oversized and surplus chunks', [
    ['new', metaOf(20, 2)], ['accept'], ['onData', 2, 10], ['onData', 0, 10], ['onData', 1, 0], ['onData', 1, 21],
    ['onData', 1, 10], ['onData', 1, 10], ['onData', 2, 11], ['onData', 2, 10], ['onData', 3, 1],
    ['onDone', 4, { type: 'done', chunks: 2, size: 20 }], ['onDone', 3, { type: 'done', chunks: 2, size: 20 }],
  ]));
  out.push(run('done with the wrong totals', [
    ['new', metaOf(20, 2)], ['accept'], ['onData', 1, 10],
    ['onDone', 2, { type: 'done', chunks: 1, size: 20 }], ['onDone', 2, { type: 'done', chunks: 2, size: 20 }],
    ['onData', 2, 10], ['onDone', 3, { type: 'done', chunks: 2, size: 21 }], ['onDone', 3, { type: 'done', chunks: 3, size: 20 }],
    ['onDone', 3, { type: 'done', chunks: 2, size: 20 }], ['onDone', 4, { type: 'done', chunks: 2, size: 20 }],
  ]));
  out.push(run('fewer bytes than announced', [
    ['new', metaOf(20, 2)], ['accept'], ['onData', 1, 5], ['onData', 2, 5], ['onDone', 3, { type: 'done', chunks: 2, size: 20 }],
  ]));
  out.push(run('peer abort while offered, receiving and after the end', [
    ['new', metaOf(20, 2)], ['onAbort', 2], ['onAbort', 1], ['onAbort', 1], ['abort'],
  ]));
  out.push(run('peer abort mid-transfer', [
    ['new', metaOf(20, 2)], ['accept'], ['onData', 1, 10], ['onAbort', 3], ['onAbort', 2], ['onData', 2, 10],
  ]));
  out.push(run('local abort while verifying', [
    ['new', metaOf(5, 1)], ['accept'], ['onData', 1, 5], ['onDone', 2, { type: 'done', chunks: 1, size: 5 }],
    ['abort'], ['complete'], ['abort'],
  ]));
  {
    const steps = [['new', metaOf(200 * 100, 200)], ['accept']];
    for (let seq = 1; seq <= 200; seq++) steps.push(['onData', seq, 100]);
    steps.push(['onDone', 201, { type: 'done', chunks: 200, size: 20000 }], ['complete']);
    out.push(run('200 chunks: an ack every 64', steps));
  }
  // Random walks: mostly the next valid call, sometimes a wrong one.
  for (let n = 0; n < 12; n++) {
    const r = drbg(`rx-walk-${n}`);
    const chunks = r.int(6);
    const sizes = Array.from({ length: chunks }, () => 1 + r.int(50));
    const size = sizes.reduce((a, b) => a + b, 0);
    const steps = [['new', metaOf(size, chunks)]];
    let seq = 1;
    for (let i = 0; i < 40; i++) {
      const roll = r.int(20);
      if (roll === 0) steps.push(['abort']);
      else if (roll === 1) steps.push(['onAbort', seq + r.int(2)]);
      else if (roll === 2) steps.push(['decline']);
      else if (roll === 3) steps.push(['onData', seq + 1 + r.int(3), 1 + r.int(50)]);
      else if (roll === 4) steps.push(['complete']);
      else if (roll === 5) steps.push(['nextReplySeq']);
      else if (roll === 6) steps.push(['onDone', seq, { type: 'done', chunks: chunks + r.int(2), size: size + r.int(2) }]);
      else if (roll < 10) steps.push(['accept']);
      else if (seq <= chunks) {
        steps.push(['onData', seq, sizes[seq - 1]]);
        seq += 1;
      } else if (seq === chunks + 1) {
        steps.push(['onDone', seq, { type: 'done', chunks, size }]);
        seq += 1;
      } else steps.push(['complete']);
    }
    out.push(run(`random walk ${n}`, steps));
  }
  return out;
}

function txTraces(F) {
  const snapshot = (w) => ({
    phase: w.phase, active: w.active, inFlight: w.inFlight, dataSent: w.dataSent, acked: w.acked, forwardSeq: w.forwardSeq,
    expectedReplySeq: w.expectedReplySeq, since: w.since, declineReason: w.declineReason, abortReason: w.abortReason,
    doneAcked: w.doneAcked,
  });
  const run = (name, steps) => ({ name, steps: trace((opts) => new F.TxWindow(opts), snapshot, steps) });
  const out = [];

  out.push(run('three chunks, delivered', [
    ['new', { chunks: 3, now: 1000 }], ['canSendData'], ['timedOut', 1000],
    ['onReply', 1, { type: 'accept' }, 1100], ['canSendData'],
    ['nextDataSeq', 1200], ['nextDataSeq', 1201], ['nextDataSeq', 1202], ['canSendData'], ['doneSeq', 1300],
    ['onReply', 2, { type: 'ack', seq: 3, done: true }, 1400], ['onReply', 3, { type: 'complete' }, 1500],
    ['abortSeq', 'user'], ['endUnconfirmed'], ['timedOut', 9_999_999],
  ]));
  out.push(run('empty file', [
    ['new', { chunks: 0, now: 0 }], ['onReply', 1, { type: 'accept' }, 1], ['canSendData'], ['doneSeq', 2],
    ['onReply', 2, { type: 'ack', seq: 0, done: true }, 3], ['onReply', 3, { type: 'complete' }, 4],
  ]));
  out.push(run('declined, each reason and an unknown one', [
    ['new', { chunks: 1, now: 0 }], ['onReply', 1, { type: 'decline', reason: 'busy' }, 1], ['onReply', 2, { type: 'accept' }, 2],
    ['new', { chunks: 1, now: 0 }], ['onReply', 1, { type: 'decline', reason: 'size' }, 1],
    ['new', { chunks: 1, now: 0 }], ['onReply', 1, { type: 'decline', reason: 'user' }, 1],
    ['new', { chunks: 1, now: 0 }], ['onReply', 1, { type: 'decline', reason: 'whatever' }, 1],
    ['new', { chunks: 1, now: 0 }], ['onReply', 1, { type: 'decline' }, 1],
  ]));
  out.push(run('accept timeout', [
    ['new', { chunks: 1, now: 0 }], ['timedOut', 299_999], ['timedOut', 300_000], ['abortSeq', 'timeout'], ['abortSeq', 'timeout'],
    ['onReply', 1, { type: 'accept' }, 300_001],
  ]));
  out.push(run('reply order and unknown replies', [
    ['new', { chunks: 2, now: 0 }], ['onReply', 2, { type: 'accept' }, 1], ['onReply', 0, { type: 'accept' }, 1],
    ['onReply', 1, { type: 'ack', seq: 0 }, 1], ['onReply', 2, { type: 'accept' }, 2], ['onReply', 3, { type: 'accept' }, 3],
    ['onReply', 4, { type: 'hello' }, 4], ['onReply', 5, {}, 5], ['onReply', 6, null, 6],
    ['onReply', 7, { type: 'complete' }, 7], ['onReply', 8, { type: 'decline', reason: 'user' }, 8],
  ]));
  out.push(run('ack validation', [
    ['new', { chunks: 4, now: 0 }], ['onReply', 1, { type: 'accept' }, 1],
    ['nextDataSeq', 2], ['nextDataSeq', 3],
    ['onReply', 2, { type: 'ack', seq: 3 }, 4], ['onReply', 3, { type: 'ack', seq: 1.5 }, 4], ['onReply', 4, { type: 'ack', seq: '1' }, 4],
    ['onReply', 5, { type: 'ack' }, 4], ['onReply', 6, { type: 'ack', seq: 1, done: false }, 4],
    ['onReply', 7, { type: 'ack', seq: 1, done: null }, 4], ['onReply', 8, { type: 'ack', seq: 1, done: 1 }, 4],
    ['onReply', 9, { type: 'ack', seq: 1, done: true }, 4], ['onReply', 10, { type: 'ack', seq: 1 }, 5],
    ['onReply', 11, { type: 'ack', seq: 1 }, 6], ['onReply', 12, { type: 'ack', seq: 0 }, 7], ['onReply', 13, { type: 'ack', seq: 2 }, 8],
    ['nextDataSeq', 9], ['nextDataSeq', 10], ['nextDataSeq', 11], ['doneSeq', 12],
    ['onReply', 14, { type: 'complete' }, 13], ['onReply', 15, { type: 'ack', seq: 3, done: true }, 14],
    ['onReply', 16, { type: 'ack', seq: 4, done: true }, 15], ['onReply', 17, { type: 'ack', seq: 4, done: true }, 16],
    ['onReply', 18, { type: 'ack', seq: 4 }, 17], ['onReply', 19, { type: 'complete' }, 18],
  ]));
  out.push(run('done before every chunk was sent', [
    ['new', { chunks: 2, now: 0 }], ['doneSeq', 1], ['onReply', 1, { type: 'accept' }, 1], ['nextDataSeq', 2], ['doneSeq', 3],
    ['nextDataSeq', 4], ['nextDataSeq', 5], ['doneSeq', 6], ['doneSeq', 7], ['nextDataSeq', 8],
  ]));
  out.push(run('receiver aborts, each reason and an unknown one', [
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'abort', reason: 'disk' }, 1], ['abortSeq', 'user'],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'accept' }, 1], ['nextDataSeq', 2], ['onReply', 2, { type: 'abort', reason: 'user' }, 3],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'abort', reason: 'nonsense' }, 1],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'abort' }, 1],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'abort', reason: 'timeout' }, 1],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'abort', reason: 'integrity' }, 1],
  ]));
  out.push(run('local abort at each stage gives the next forward seq', [
    ['new', { chunks: 2, now: 0 }], ['abortSeq', 'user'],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'accept' }, 1], ['abortSeq', 'disk'],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'accept' }, 1], ['nextDataSeq', 2], ['abortSeq', 'integrity'],
    ['new', { chunks: 2, now: 0 }], ['onReply', 1, { type: 'accept' }, 1], ['nextDataSeq', 2], ['nextDataSeq', 3], ['doneSeq', 4],
    ['abortSeq', 'timeout'], ['abortSeq', 'user'],
  ]));
  out.push(run('complete never arrives', [
    ['new', { chunks: 1, now: 0 }], ['onReply', 1, { type: 'accept' }, 1], ['nextDataSeq', 2], ['doneSeq', 1000],
    ['endUnconfirmed'], ['timedOut', 300_999], ['timedOut', 301_000],
    ['onReply', 2, { type: 'ack', seq: 1, done: true }, 301_001], ['timedOut', 301_002], ['endUnconfirmed'], ['endUnconfirmed'],
    ['onReply', 3, { type: 'complete' }, 301_003],
  ]));
  {
    // A small window: fills, stalls, is credited by acks.
    const steps = [['new', { chunks: 10, now: 0, window: 4, ackStallMs: 1000 }], ['onReply', 1, { type: 'accept' }, 10]];
    for (let i = 0; i < 4; i++) steps.push(['canSendData'], ['nextDataSeq', 100 + i]);
    steps.push(['canSendData'], ['nextDataSeq', 200], ['timedOut', 1102], ['timedOut', 1103],
      ['onReply', 2, { type: 'ack', seq: 2 }, 1200], ['timedOut', 1300], ['canSendData'],
      ['nextDataSeq', 1400], ['nextDataSeq', 1401], ['canSendData'], ['timedOut', 2400], ['timedOut', 2401],
      ['onReply', 3, { type: 'ack', seq: 6 }, 2500]);
    for (let i = 0; i < 4; i++) steps.push(['nextDataSeq', 2600 + i]);
    steps.push(['canSendData'], ['doneSeq', 2700], ['onReply', 4, { type: 'ack', seq: 8 }, 2800],
      ['onReply', 5, { type: 'ack', seq: 10, done: true }, 2900], ['onReply', 6, { type: 'complete' }, 3000]);
    out.push(run('window of 4: fills, stalls and is credited', steps));
  }
  out.push(run('invalid chunk counts', [
    ['new', { chunks: -1, now: 0 }], ['new', { chunks: 1.5, now: 0 }], ['new', { chunks: 4294967294, now: 0 }],
    ['new', { chunks: 4294967293, now: 0 }],
  ]));
  for (let n = 0; n < 12; n++) {
    const r = drbg(`tx-walk-${n}`);
    const chunks = r.int(8);
    const steps = [['new', { chunks, now: 0, window: 1 + r.int(4), acceptTimeoutMs: 500, ackStallMs: 200, completeTimeoutMs: 500 }]];
    let now = 0;
    let reply = 1;
    let sent = 0;
    for (let i = 0; i < 50; i++) {
      now += r.int(120);
      const roll = r.int(24);
      if (roll === 0) steps.push(['abortSeq', F.ABORT_REASONS[r.int(4)]]);
      else if (roll === 1) steps.push(['onReply', reply++, { type: 'abort', reason: F.ABORT_REASONS[r.int(4)] }, now]);
      else if (roll === 2) steps.push(['onReply', reply + 1, { type: 'ack', seq: sent }, now]);
      else if (roll === 3) steps.push(['onReply', reply++, { type: 'decline', reason: F.DECLINE_REASONS[r.int(3)] }, now]);
      else if (roll === 4) steps.push(['onReply', reply++, { type: 'ack', seq: sent + 1 }, now]);
      else if (roll === 5) steps.push(['endUnconfirmed']);
      else if (roll === 6) steps.push(['onReply', reply++, { type: 'complete' }, now]);
      else if (roll < 10) steps.push(['timedOut', now]);
      else if (roll < 12) steps.push(['onReply', reply++, { type: 'accept' }, now]);
      else if (roll < 15) steps.push(['onReply', reply++, { type: 'ack', seq: r.int(sent + 1) }, now]);
      else if (roll < 17) steps.push(['onReply', reply++, { type: 'ack', seq: chunks, done: true }, now]);
      else if (roll < 22) {
        steps.push(['canSendData'], ['nextDataSeq', now]);
        if (sent < chunks) sent += 1;
      } else steps.push(['doneSeq', now]);
    }
    out.push(run(`random walk ${n}`, steps));
  }
  return out;
}

function budget(F) {
  const MB = 1024 * 1024;
  const wire = [0, 1, 15_999, 16_000, 16_001, 64 * 16_000, 64 * 16_000 + 1, 1_000_000, 50 * MB, 500 * MB, 2 ** 40]
    .map((size) => ({ size, chunkSize: 16_000, wireBytes: F.RelayBudget.wireBytes(size, 16_000) }));
  wire.push({ size: 1_000_000, chunkSize: 1000, wireBytes: F.RelayBudget.wireBytes(1_000_000, 1000) });

  const runs = [];
  const run = (name, init, steps) => {
    const entry = { name, init };
    let b;
    try {
      b = new F.RelayBudget(init);
    } catch (err) {
      if (!(err instanceof RangeError)) throw err;
      entry.error = 'range';
      runs.push(entry);
      return;
    }
    entry.steps = steps.map(([op, ...args]) => {
      const ret = b[op](...args);
      const step = { op, args, remaining: b.remaining };
      if (ret !== undefined) step.ret = ret;
      return step;
    });
    runs.push(entry);
  };
  run('fresh free room', { cap: 50 * MB, remaining: 50 * MB }, [
    ['fits', 50 * MB, 16_000], ['fits', 49 * MB, 16_000], ['fits', 0, 16_000], ['charge', 125], ['charge', 75], ['charge', 16_049],
    ['charge', 0], ['charge', -5], ['charge', 1.5], ['fits', 49 * MB, 16_000], ['charge', 50 * MB], ['fits', 0, 16_000], ['charge', 1],
  ]);
  run('largest file that fits exactly', { cap: 1_000_000, remaining: 1_000_000 }, [
    ['fits', 995_702, 16_000], ['fits', 995_703, 16_000], ['fits', 995_704, 16_000],
  ]);
  run('partly used room', { cap: 500 * MB, remaining: 4096 }, [['fits', 1, 16_000], ['fits', 2000, 16_000], ['fits', 3000, 16_000]]);
  run('remaining above the cap', { cap: 10, remaining: 11 }, []);
  run('negative remaining', { cap: 10, remaining: -1 }, []);
  run('fractional cap', { cap: 10.5, remaining: 1 }, []);
  run('missing figures (older server)', {}, []);
  return { wireBytes: wire, runs };
}

function backoff(F) {
  const out = [];
  const rands = [0, 0.25, 0.5, 0.999999, 1, -0.1, 1.5];
  for (const attempt of [0, 1, 2, 3, 4, 5, 6, 15, 16, 17, 1000, -1, 1.5]) {
    for (const rand of rands) out.push({ attempt, rand, delayMs: F.reconnectDelay(attempt, rand) });
  }
  return out;
}

function candidates(F) {
  const lines = [
    'candidate:842163049 1 udp 2122260223 192.168.1.23 54321 typ host generation 0 ufrag abcd network-id 1',
    'candidate:842163049 1 udp 2122260223 2001:db8::1234 54321 typ host generation 0',
    'candidate:1 1 udp 2113937151 3f2a8c1e-7b5d-4e0a-9c11-2d3e4f5a6b7c.local 54321 typ host generation 0',
    'candidate:1 1 udp 2113937151 3F2A8C1E-7B5D-4E0A-9C11-2D3E4F5A6B7C.LOCAL 54321 typ host',
    'candidate:2 1 udp 1686052607 203.0.113.7 61000 typ srflx raddr 192.168.1.23 rport 54321 generation 0',
    'candidate:2 1 udp 1686052607 203.0.113.7 61000 typ srflx raddr 0.0.0.0 rport 0 generation 0',
    'candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr 0.0.0.0 rport 0 generation 0',
    'candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr :: rport 0 generation 0',
    'candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr 203.0.113.7 rport 61000 generation 0',
    'candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay generation 0',
    'candidate:3 1 tcp 41885439 198.51.100.9 443 typ relay raddr 0.0.0.0 rport 0 tcptype passive',
    'candidate:4 1 udp 1845501695 203.0.113.7 61000 typ prflx raddr 192.168.1.23 rport 54321',
    'candidate:5 1 tcp 1518280447 192.168.1.23 9 typ host tcptype active generation 0',
    '  candidate:842163049   1 udp 2122260223 192.168.1.23 54321 typ host  ',
    'candidate:6 1 udp 1 typ host',
    'candidate:7 1 udp 2122260223 192.168.1.23 54321 typ',
    'candidate:8 1 udp 2122260223 192.168.1.23 54321',
    'candidate:9 1 udp 2122260223 192.168.1.23 54321 typ future-type',
    'a=end-of-candidates',
    '',
  ];
  const out = lines.map((line) => ({ candidate: { candidate: line }, exposesIp: F.candidateExposesIp({ candidate: line }) }));
  // Browsers also give the parsed fields, which the page prefers to the line.
  for (const candidate of [
    { candidate: '', type: 'host', address: '192.168.1.23' },
    { candidate: '', type: 'host', address: 'abc.local' },
    { candidate: '', type: 'host' },
    { candidate: '', type: 'relay', relatedAddress: null },
    { candidate: '', type: 'relay', relatedAddress: '203.0.113.7' },
    { candidate: 'candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr 203.0.113.7 rport 61000', type: 'relay', relatedAddress: null },
    { candidate: 'candidate:1 1 udp 1 192.168.1.23 1 typ host', type: 'relay', relatedAddress: '0.0.0.0' },
    { candidate: 42 },
    {},
  ]) out.push({ candidate, exposesIp: F.candidateExposesIp(candidate) });
  out.push({ candidate: null, exposesIp: F.candidateExposesIp(null) });
  return out;
}

// Which lookups of the transfer-key cache are served from the cache.
async function lru() {
  const r = drbg('lru');
  const F = framing(drbg('lru-iv'));
  const roomKey = await F.importRoomKey(r.bytes(32));
  const session = { initNonce: r.bytes(32), respNonce: r.bytes(32) };
  const ids = Array.from({ length: 12 }, () => r.bytes(16));
  const out = [];
  for (const [max, order] of [
    [8, [0, 1, 2, 3, 4, 5, 6, 7, 0, 8, 1, 0, 2, 9, 10, 11, 0, 3, 4, 5]],
    [1, [0, 0, 1, 0, 1, 1]],
    [3, Array.from({ length: 40 }, () => r.int(6))],
  ]) {
    const keys = F.createTransferKeyCache(roomKey, session, max);
    const last = new Map();
    const hits = [];
    for (const i of order) {
      const pending = keys(ids[i]);
      hits.push(last.get(i) === pending);
      last.set(i, pending);
      await pending;
    }
    out.push({ max, order, hits });
  }
  return out;
}

export async function buildVectors() {
  const F = framing(drbg('unused'));
  return {
    format: 'darkp2p/v3',
    about: 'Generated by test/vectors/export.mjs from public/index.html. Do not edit: run `node test/vectors/export.mjs`.',
    encoding: 'Byte strings are lowercase hex. Frame types and roles are their wire byte values.',
    constants: constants(F),
    links: links(),
    hkdf: await hkdf(),
    aad: aad(F),
    frames: await frames(),
    framesBad: await framesBad(),
    meta: meta(F),
    rxTraces: rxTraces(F),
    txTraces: txTraces(F),
    relayBudget: budget(F),
    reconnectDelay: backoff(F),
    candidates: candidates(F),
    transferKeyCache: await lru(),
  };
}

// One top-level entry per line keeps the file small and its diffs readable.
export function serialize(vectors) {
  const lines = ['{'];
  const groups = Object.entries(vectors);
  groups.forEach(([name, value], g) => {
    const comma = g === groups.length - 1 ? '' : ',';
    if (Array.isArray(value)) {
      lines.push(` ${JSON.stringify(name)}: [`);
      value.forEach((item, i) => lines.push(`  ${JSON.stringify(item)}${i === value.length - 1 ? '' : ','}`));
      lines.push(` ]${comma}`);
    } else lines.push(` ${JSON.stringify(name)}: ${JSON.stringify(value)}${comma}`);
  });
  lines.push('}');
  return `${lines.join('\n')}\n`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const text = serialize(await buildVectors());
  writeFileSync(VECTORS_PATH, text);
  console.log(`wrote ${VECTORS_PATH} (${text.length} bytes)`);
}
