// Step 7 pure helpers (reconnect backoff, relay budget, OPFS temp-file cleanup)
// from the marked block in public/index.html.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8').replace(/\r\n?/g, '\n');
const m = /\/\/ ==BEGIN darkp2p-framing==\n([\s\S]*?)\/\/ ==END darkp2p-framing==/.exec(html);
assert.ok(m, 'index.html has the darkp2p-framing block');
const P = new Function(`${m[1]}
return { reconnectDelay, RelayBudget, isStaleTempName, candidateExposesIp, FRAME_OVERHEAD, ACK_EVERY, OPFS_STALE_MS,
  RECONNECT_STABLE_MS, RELAY_MIN_REMAINING };`)();
const { reconnectDelay, RelayBudget, isStaleTempName, candidateExposesIp } = P;
const CHUNK = 16000;

test('reconnect delay doubles from 1 s to a 30 s cap', () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 6, 20].map((n) => reconnectDelay(n, 0.5)),
    [1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000]);
});

test('reconnect delay has +-20 % jitter and survives bad input', () => {
  assert.equal(reconnectDelay(0, 0), 800);
  assert.equal(reconnectDelay(0, 0.999999), 1200);
  assert.equal(reconnectDelay(10, 0), 24000);
  assert.equal(reconnectDelay(10, 0.999999), 36000);
  for (const [a, r] of [[-1, 0.5], [NaN, 0.5], [1.5, 0.5], [0, -1], [0, 1], [0, NaN]]) {
    const d = reconnectDelay(a, r);
    assert.ok(d >= 800 && d <= 1200, `attempt ${a} rand ${r} -> ${d}`);
  }
});

test('relay budget validates and charges', () => {
  const b = new RelayBudget({ cap: 1000, remaining: 600 });
  b.charge(100);
  assert.equal(b.remaining, 500);
  for (const junk of [0, -5, 1.5, NaN, '100']) b.charge(junk);
  assert.equal(b.remaining, 500);
  b.charge(10_000);
  assert.equal(b.remaining, 0, 'never negative');
  for (const bad of [{ cap: -1, remaining: 0 }, { cap: 10, remaining: 11 }, { cap: 10 },
    { cap: '10', remaining: 1 }, { cap: 10, remaining: 1.5 }]) {
    assert.throws(() => new RelayBudget(bad), RangeError, JSON.stringify(bad));
  }
});

test('relay budget: wireBytes is never below what a transfer really uses', () => {
  // Real frame sizes: header + tag around the JSON or chunk payload.
  const ctrl = (obj) => P.FRAME_OVERHEAD + Buffer.byteLength(JSON.stringify(obj));
  for (const size of [0, 1, 15999, 16000, 16001, 64 * CHUNK, 64 * CHUNK + 1, 50 * 1048576, 499 * 1048576]) {
    const n = Math.ceil(size / CHUNK);
    const name = 'n'.repeat(200); // a long file name
    let used = ctrl({ type: 'meta', name, size, mime: 'application/octet-stream', chunks: n });
    used += ctrl({ type: 'accept' });
    used += size + n * P.FRAME_OVERHEAD;
    for (let s = P.ACK_EVERY; s <= n; s += P.ACK_EVERY) used += ctrl({ type: 'ack', seq: s });
    used += ctrl({ type: 'done', chunks: n, size });
    used += ctrl({ type: 'ack', seq: n, done: true });
    used += ctrl({ type: 'complete' });
    const est = RelayBudget.wireBytes(size, CHUNK);
    assert.ok(est >= used, `size ${size}: estimate ${est} < real ${used}`);
    assert.ok(est - used < 4096, `size ${size}: estimate ${est} is far above real ${used}`);
  }
});

test('relay budget: fits() uses what the room has left, not the cap', () => {
  const MB = 1048576;
  const fresh = new RelayBudget({ cap: 50 * MB, remaining: 50 * MB });
  assert.equal(fresh.fits(49 * MB, CHUNK), true);
  assert.equal(fresh.fits(50 * MB, CHUNK), false, 'overhead pushes a cap-sized file over');
  const used = new RelayBudget({ cap: 50 * MB, remaining: 10 * MB });
  assert.equal(used.fits(20 * MB, CHUNK), false);
  assert.equal(used.fits(9 * MB, CHUNK), true);
  assert.equal(new RelayBudget({ cap: 0, remaining: 0 }).fits(0, CHUNK), false, 'cap 0 disables the relay');
});

test('OPFS cleanup only matches our own old temp files', () => {
  const now = 1_800_000_000_000;
  const old = now - P.OPFS_STALE_MS - 1;
  assert.equal(isStaleTempName(`darkp2p-${old}-abc123`, now), true);
  assert.equal(isStaleTempName(`darkp2p-${now - P.OPFS_STALE_MS}-abc123`, now), false, 'boundary is not stale');
  assert.equal(isStaleTempName(`darkp2p-${now - 1000}-abc123`, now), false, 'a live transfer in another tab');
  for (const name of ['other-file', `darkp2p-${old}`, `darkp2p-${old}-ABC`, `xdarkp2p-${old}-abc`,
    `darkp2p-${old}-abc.crswap`, `darkp2p-abc-${old}`, `darkp2p--abc`]) {
    assert.equal(isStaleTempName(name, now), false, name);
  }
});

test('candidateExposesIp: only mDNS hosts and blanked relay candidates are safe', () => {
  const line = (addr, typ, rest = '') => `candidate:1 1 udp 2113937151 ${addr} 54321 typ ${typ}${rest}`;
  const safe = [
    { type: 'host', address: '3b1f6a52-7a0c-4d2e-9d3a-1f2e3d4c5b6a.local', candidate: line('3b1f6a52-7a0c-4d2e-9d3a-1f2e3d4c5b6a.local', 'host') },
    // No address/type fields (older browsers): read from the candidate line.
    { candidate: line('3b1f6a52-7a0c-4d2e-9d3a-1f2e3d4c5b6a.LOCAL', 'host') },
    { type: 'relay', address: '203.0.113.10', relatedAddress: '0.0.0.0', candidate: line('203.0.113.10', 'relay', ' raddr 0.0.0.0 rport 0') },
    { candidate: line('203.0.113.10', 'relay', ' raddr 0.0.0.0 rport 0') },
    { candidate: line('2001:db8::10', 'relay', ' raddr :: rport 0') },
    { type: 'relay', address: '203.0.113.10', relatedAddress: null, candidate: line('203.0.113.10', 'relay') },
  ];
  for (const c of safe) assert.equal(candidateExposesIp(c), false, JSON.stringify(c));

  const exposing = [
    { type: 'host', address: '192.168.1.20', candidate: line('192.168.1.20', 'host') },
    { candidate: line('192.168.1.20', 'host') },
    { candidate: line('2001:db8::1', 'host') },
    // A host candidate whose address cannot be read at all must not count as masked.
    { type: 'host', address: '', candidate: '' },
    { type: 'host', address: null, candidate: 'candidate:1 1 udp' },
    { type: 'host' },
    { type: 'srflx', address: '198.51.100.7', candidate: line('198.51.100.7', 'srflx', ' raddr 0.0.0.0 rport 0') },
    { candidate: line('198.51.100.7', 'srflx', ' raddr 192.168.1.20 rport 5000') },
    { type: 'prflx', address: '198.51.100.7', candidate: '' },
    { type: 'relay', address: '203.0.113.10', relatedAddress: '198.51.100.7', candidate: line('203.0.113.10', 'relay', ' raddr 198.51.100.7 rport 6000') },
    { candidate: line('203.0.113.10', 'relay', ' raddr 198.51.100.7 rport 6000') },
    // Unreadable or unknown: assume the worst.
    { candidate: '' }, {}, null, undefined, { candidate: 42 },
    { candidate: line('198.51.100.7', 'somethingnew') },
  ];
  for (const c of exposing) assert.equal(candidateExposesIp(c), true, JSON.stringify(c));
});

test('reconnect and relay thresholds are sane', () => {
  // The backoff must not reset before a server that closes right after the join has done so.
  assert.ok(P.RECONNECT_STABLE_MS >= 5000);
  // A key handshake (two hellos and two hello-acks) fits in the minimum relay budget.
  assert.ok(P.RELAY_MIN_REMAINING >= 4 * (P.FRAME_OVERHEAD + 128));
});
