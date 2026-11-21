// Transfer protocol state machines (RxStateMachine, TxWindow) from the marked block in public/index.html.
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
return {
  RxStateMachine, TxWindow, ProtocolError, ACK_EVERY, TX_WINDOW, RAM_MODE_MAX_BYTES,
  ACCEPT_TIMEOUT_MS, ACK_STALL_MS, COMPLETE_TIMEOUT_MS,
};`)();
const { RxStateMachine, TxWindow, ProtocolError } = P;

const CHUNK = 16000;
const integrity = { name: 'ProtocolError', reason: 'integrity' };

function metaFor(size, extra = {}) {
  return { type: 'meta', name: 'f.bin', size, mime: 'application/octet-stream', chunks: Math.ceil(size / CHUNK), ...extra };
}
function rxFor(size) {
  const rx = new RxStateMachine(RxStateMachine.parseMeta(metaFor(size)));
  rx.accept();
  return rx;
}
const chunkLen = (size, seq) => Math.min(CHUNK, size - (seq - 1) * CHUNK);

test('constants match the Step 6 spec', () => {
  assert.equal(P.ACK_EVERY, 64);
  assert.equal(P.TX_WINDOW, 256);
  assert.equal(P.RAM_MODE_MAX_BYTES, 512 * 1024 * 1024);
  assert.equal(P.ACCEPT_TIMEOUT_MS, 5 * 60 * 1000);
});

test('parseMeta validates size and chunk count', () => {
  const ok = RxStateMachine.parseMeta(metaFor(32001, { name: 'a/b\\c.txt', mime: 7 }));
  assert.deepEqual(ok, { name: 'a_b_c.txt', size: 32001, mime: 'application/octet-stream', chunks: 3 });
  assert.equal(RxStateMachine.parseMeta(metaFor(0)).chunks, 0);
  for (const bad of [
    metaFor(-1), metaFor(1.5), { ...metaFor(10), size: '10' }, metaFor(2 ** 53),
    metaFor(10, { chunks: 0 }), metaFor(0, { chunks: 1 }), metaFor(10, { chunks: 11 }),
    metaFor(10, { chunks: 1.5 }), metaFor(10, { type: 'done' }), null,
  ]) {
    assert.throws(() => RxStateMachine.parseMeta(bad), integrity, JSON.stringify(bad));
  }
});

test('RAM mode auto-declines files over 512 MB', () => {
  const big = { size: P.RAM_MODE_MAX_BYTES + 1 };
  assert.equal(RxStateMachine.autoDeclineReason(big, { diskStreaming: false }), 'size');
  assert.equal(RxStateMachine.autoDeclineReason(big, { diskStreaming: true }), null);
  assert.equal(RxStateMachine.autoDeclineReason({ size: P.RAM_MODE_MAX_BYTES }, { diskStreaming: false }), null);
});

test('rx happy path: ack every 64 chunks, final ack after done, complete', () => {
  const size = 200 * CHUNK + 5;
  const rx = rxFor(size);
  const acks = [];
  for (let seq = 1; seq <= 201; seq++) {
    const { ack } = rx.onData(seq, chunkLen(size, seq));
    if (ack !== null) acks.push(ack);
  }
  assert.deepEqual(acks, [64, 128, 192]);
  assert.deepEqual(rx.onDone(202, { type: 'done', chunks: 201, size }), { ack: 201 });
  assert.equal(rx.phase, 'verifying');
  rx.complete();
  assert.equal(rx.phase, 'complete');
  assert.equal(rx.active, false);
  assert.deepEqual([rx.nextReplySeq(), rx.nextReplySeq()], [1, 2]);
});

test('rx: empty file needs no data, done at seq 1', () => {
  const rx = rxFor(0);
  assert.throws(() => rx.onData(1, 1), integrity);
  const rx2 = rxFor(0);
  assert.deepEqual(rx2.onDone(1, { type: 'done', chunks: 0, size: 0 }), { ack: 0 });
});

test('rx: out-of-order seq is rejected', () => {
  const size = 5 * CHUNK;
  for (const seqs of [[2], [1, 1], [1, 3], [1, 2, 1], [0]]) {
    const rx = rxFor(size);
    const last = seqs.pop();
    for (const s of seqs) rx.onData(s, CHUNK);
    assert.throws(() => rx.onData(last, CHUNK), /Out-of-order chunk/, `sequence ${seqs},${last}`);
  }
});

test('rx: size overflow and extra chunks are rejected', () => {
  const size = 2 * CHUNK - 1;
  const rx = rxFor(size);
  rx.onData(1, CHUNK);
  assert.throws(() => rx.onData(2, CHUNK), /exceeds announced file size/);

  const rx2 = rxFor(CHUNK);
  rx2.onData(1, CHUNK);
  assert.throws(() => rx2.onData(2, 1), /More chunks than announced/);

  const rx3 = rxFor(10);
  assert.throws(() => rx3.onData(1, 0), /Empty chunk/);
});

test('rx: duplicate, early, misnumbered or miscounted done is rejected', () => {
  const size = 2 * CHUNK;
  const full = () => { const r = rxFor(size); r.onData(1, CHUNK); r.onData(2, CHUNK); return r; };
  const done = { type: 'done', chunks: 2, size };

  const dup = full();
  dup.onDone(3, done);
  assert.throws(() => dup.onDone(3, done), /Unexpected done/);
  assert.throws(() => dup.onDone(4, done), /Unexpected done/);

  assert.throws(() => full().onDone(4, done), /Out-of-order done/);
  assert.throws(() => full().onDone(3, { ...done, chunks: 3 }), /Integrity mismatch/);
  assert.throws(() => full().onDone(3, { ...done, size: size + 1 }), /Integrity mismatch/);

  const early = rxFor(size);
  early.onData(1, CHUNK);
  assert.throws(() => early.onDone(2, { type: 'done', chunks: 1, size }), /Integrity mismatch/);
});

test('rx: pre-data decline — no data accepted before accept or after decline', () => {
  const meta = RxStateMachine.parseMeta(metaFor(3 * CHUNK));
  const offered = new RxStateMachine(meta);
  assert.throws(() => offered.onData(1, CHUNK), /Unexpected chunk \(offered\)/);
  assert.throws(() => offered.onDone(1, { type: 'done', chunks: 0, size: 0 }), /Unexpected done/);

  const declined = new RxStateMachine(meta);
  declined.decline();
  assert.equal(declined.phase, 'declined');
  assert.equal(declined.active, false);
  assert.throws(() => declined.onData(1, CHUNK), /Unexpected chunk \(declined\)/);
  assert.throws(() => declined.accept(), /Unexpected accept/);
  assert.equal(declined.abort(), false, 'nothing to abort after a decline');
});

test('rx: mid-stream abort from the sender', () => {
  const rx = rxFor(4 * CHUNK);
  rx.onData(1, CHUNK);
  rx.onData(2, CHUNK);
  assert.throws(() => rx.onAbort(2), /Out-of-order abort/);
  rx.onAbort(3);
  assert.equal(rx.phase, 'aborted');
  assert.throws(() => rx.onData(3, CHUNK), /Unexpected chunk \(aborted\)/);
  assert.throws(() => rx.onAbort(4), /Unexpected abort/);
  assert.equal(rx.abort(), false);
});

test('rx: local abort reports whether the peer must be told', () => {
  const rx = rxFor(4 * CHUNK);
  rx.onData(1, CHUNK);
  assert.equal(rx.abort(), true);
  assert.equal(rx.abort(), false);
});

test('tx happy path: window caps in-flight chunks at 256, acks reopen it', () => {
  const tx = new TxWindow({ chunks: 300, now: 0 });
  assert.equal(tx.canSendData(), false, 'no data before accept');
  assert.equal(tx.onReply(1, { type: 'accept' }, 0), 'accept');
  let seq = 0;
  while (tx.canSendData()) seq = tx.nextDataSeq(0);
  assert.equal(seq, 256);
  assert.equal(tx.inFlight, 256);
  assert.throws(() => tx.nextDataSeq(0), /No send credit/);
  tx.onReply(2, { type: 'ack', seq: 64 }, 1);
  while (tx.canSendData()) seq = tx.nextDataSeq(1);
  assert.equal(seq, 300);
  assert.throws(() => tx.onReply(3, { type: 'complete' }, 2), /Unexpected complete \(sending\)/);
});

test('tx: done then complete only after the final ack', () => {
  const tx = new TxWindow({ chunks: 2, now: 0 });
  tx.onReply(1, { type: 'accept' }, 0);
  assert.throws(() => tx.doneSeq(0), /done before all chunks/);
  tx.nextDataSeq(0);
  tx.nextDataSeq(0);
  assert.equal(tx.doneSeq(0), 3);
  assert.throws(() => tx.onReply(2, { type: 'complete' }, 0), /complete before the final ack/);

  const ok = new TxWindow({ chunks: 2, now: 0 });
  ok.onReply(1, { type: 'accept' }, 0);
  ok.nextDataSeq(0);
  ok.nextDataSeq(0);
  ok.doneSeq(0);
  ok.onReply(2, { type: 'ack', seq: 2 }, 0);
  assert.throws(() => ok.onReply(3, { type: 'complete' }, 0), /complete before the final ack/,
    'a chunk ack for N is not the final ack');

  const fin = new TxWindow({ chunks: 2, now: 0 });
  fin.onReply(1, { type: 'accept' }, 0);
  fin.nextDataSeq(0);
  fin.nextDataSeq(0);
  fin.doneSeq(0);
  fin.onReply(2, { type: 'ack', seq: 2 }, 0);
  fin.onReply(3, { type: 'ack', seq: 2, done: true }, 0);
  assert.equal(fin.doneAcked, true);
  assert.equal(fin.onReply(4, { type: 'complete' }, 0), 'complete');
  assert.equal(fin.phase, 'delivered');
  assert.throws(() => fin.onReply(5, { type: 'complete' }, 0), /finished transfer/);
});

test('tx: replies must be in order and acks must be valid', () => {
  const tx = new TxWindow({ chunks: 100, now: 0 });
  assert.throws(() => tx.onReply(2, { type: 'accept' }, 0), /Out-of-order reply/);

  const t2 = new TxWindow({ chunks: 100, now: 0 });
  t2.onReply(1, { type: 'accept' }, 0);
  assert.throws(() => t2.onReply(2, { type: 'accept' }, 0), /Unexpected accept/);

  const t3 = new TxWindow({ chunks: 100, now: 0 });
  t3.onReply(1, { type: 'accept' }, 0);
  for (let i = 0; i < 10; i++) t3.nextDataSeq(0);
  assert.throws(() => t3.onReply(2, { type: 'ack', seq: 11 }, 0), /Invalid ack/, 'ack beyond sent');

  const t4 = new TxWindow({ chunks: 100, now: 0 });
  t4.onReply(1, { type: 'accept' }, 0);
  for (let i = 0; i < 10; i++) t4.nextDataSeq(0);
  t4.onReply(2, { type: 'ack', seq: 8 }, 0);
  t4.onReply(3, { type: 'ack', seq: 8 }, 0);
  assert.throws(() => t4.onReply(4, { type: 'ack', seq: 7 }, 0), /Invalid ack/, 'ack going backwards');

  for (const bad of [{ type: 'ack', seq: '1' }, { type: 'ack' }, { type: 'nope' }]) {
    const t = new TxWindow({ chunks: 1, now: 0 });
    t.onReply(1, { type: 'accept' }, 0);
    t.nextDataSeq(0);
    assert.throws(() => t.onReply(2, bad, 0), ProtocolError, JSON.stringify(bad));
  }
});

test('tx: stalled ack window times out; progress resets the timer', () => {
  const stall = P.ACK_STALL_MS;
  const tx = new TxWindow({ chunks: 1000, now: 0 });
  tx.onReply(1, { type: 'accept' }, 0);
  for (let i = 0; i < 255; i++) tx.nextDataSeq(1000);
  assert.equal(tx.timedOut(1000 + stall * 10), null, 'window not full: not a stall');
  tx.nextDataSeq(5000); // fills the window at t=5000
  assert.equal(tx.timedOut(5000 + stall - 1), null);
  assert.equal(tx.timedOut(5000 + stall), 'ack');
  tx.onReply(2, { type: 'ack', seq: 64 }, 5000 + stall);
  assert.equal(tx.timedOut(5000 + stall + 1), null, 'ack reopened the window');
  while (tx.canSendData()) tx.nextDataSeq(70000);
  assert.equal(tx.timedOut(70000 + stall), 'ack');
  tx.onReply(3, { type: 'ack', seq: 64 }, 70000 + stall); // no progress: still stalled
  assert.equal(tx.timedOut(70000 + stall), 'ack');
});

test('tx: accept and complete timeouts', () => {
  const tx = new TxWindow({ chunks: 1, now: 100 });
  assert.equal(tx.timedOut(100 + P.ACCEPT_TIMEOUT_MS - 1), null);
  assert.equal(tx.timedOut(100 + P.ACCEPT_TIMEOUT_MS), 'accept');

  const t2 = new TxWindow({ chunks: 1, now: 0 });
  t2.onReply(1, { type: 'accept' }, 0);
  t2.nextDataSeq(0);
  t2.doneSeq(10);
  assert.equal(t2.timedOut(10 + P.COMPLETE_TIMEOUT_MS - 1), null);
  assert.equal(t2.timedOut(10 + P.COMPLETE_TIMEOUT_MS), 'complete');
  t2.abortSeq('timeout');
  assert.equal(t2.timedOut(10 ** 12), null, 'finished transfers never time out');
});

test('tx: pre-data decline stops the transfer', () => {
  const tx = new TxWindow({ chunks: 5, now: 0 });
  assert.equal(tx.onReply(1, { type: 'decline', reason: 'size' }, 0), 'decline');
  assert.equal(tx.phase, 'declined');
  assert.equal(tx.declineReason, 'size');
  assert.equal(tx.canSendData(), false);
  assert.throws(() => tx.nextDataSeq(0), /No send credit/);
  assert.equal(tx.abortSeq('user'), null);

  const odd = new TxWindow({ chunks: 5, now: 0 });
  odd.onReply(1, { type: 'decline', reason: '<script>' }, 0);
  assert.equal(odd.declineReason, 'user', 'unknown reasons are normalized');

  const late = new TxWindow({ chunks: 5, now: 0 });
  late.onReply(1, { type: 'accept' }, 0);
  assert.throws(() => late.onReply(2, { type: 'decline' }, 0), /Unexpected decline/);
});

test('tx: mid-stream abort by either side', () => {
  const byPeer = new TxWindow({ chunks: 10, now: 0 });
  byPeer.onReply(1, { type: 'accept' }, 0);
  byPeer.nextDataSeq(0);
  assert.equal(byPeer.onReply(2, { type: 'abort', reason: 'disk' }, 0), 'abort');
  assert.equal(byPeer.abortReason, 'disk');
  assert.equal(byPeer.canSendData(), false);
  assert.equal(byPeer.abortSeq('user'), null, 'already aborted: nothing to send');

  // Local abort uses the next forward seq, which the receiver expects.
  const size = 10 * CHUNK;
  const tx = new TxWindow({ chunks: 10, now: 0 });
  const rx = new RxStateMachine(RxStateMachine.parseMeta(metaFor(size)));
  rx.accept();
  tx.onReply(rx.nextReplySeq(), { type: 'accept' }, 0);
  for (let i = 0; i < 3; i++) {
    const seq = tx.nextDataSeq(0);
    rx.onData(seq, CHUNK);
  }
  const abortSeq = tx.abortSeq('user');
  assert.equal(abortSeq, 4);
  rx.onAbort(abortSeq);
  assert.equal(rx.phase, 'aborted');
  assert.equal(tx.phase, 'aborted');
  assert.equal(tx.abortReason, 'user');

  // Abort while still offered, and after done.
  const offered = new TxWindow({ chunks: 1, now: 0 });
  assert.equal(offered.abortSeq('timeout'), 1);
  const afterDone = new TxWindow({ chunks: 1, now: 0 });
  afterDone.onReply(1, { type: 'accept' }, 0);
  afterDone.nextDataSeq(0);
  afterDone.doneSeq(0);
  assert.equal(afterDone.abortSeq('timeout'), 3);
});

test('lockstep: an honest receiver never has more than 256 chunks queued', () => {
  // Deterministic PRNG so failures are reproducible.
  let s = 42;
  const rnd = () => ((s = (s * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
  const chunks = 2000;
  const size = chunks * CHUNK;
  const tx = new TxWindow({ chunks, now: 0 });
  const rx = new RxStateMachine(RxStateMachine.parseMeta(metaFor(size)));
  const toRx = [];
  const toTx = [];
  rx.accept();
  toTx.push({ seq: rx.nextReplySeq(), msg: { type: 'accept' } });
  let maxQueued = 0;
  let doneSent = false;

  for (let step = 0; step < 100000 && tx.phase !== 'delivered'; step++) {
    const r = rnd();
    if (r < 0.45) {
      // Sender sends as far as credit allows.
      while (tx.canSendData()) toRx.push({ kind: 'data', seq: tx.nextDataSeq(step) });
      if (!doneSent && tx.phase === 'sending' && tx.dataSent === chunks) {
        toRx.push({ kind: 'done', seq: tx.doneSeq(step) });
        doneSent = true;
      }
    } else if (r < 0.8) {
      // Receiver processes a few frames.
      for (let n = Math.floor(rnd() * 40); n > 0 && toRx.length; n--) {
        const f = toRx.shift();
        if (f.kind === 'data') {
          const { ack } = rx.onData(f.seq, CHUNK);
          if (ack !== null) toTx.push({ seq: rx.nextReplySeq(), msg: { type: 'ack', seq: ack } });
        } else {
          const { ack } = rx.onDone(f.seq, { type: 'done', chunks, size });
          toTx.push({ seq: rx.nextReplySeq(), msg: { type: 'ack', seq: ack, done: true } });
          rx.complete();
          toTx.push({ seq: rx.nextReplySeq(), msg: { type: 'complete' } });
        }
      }
    } else {
      while (toTx.length) { const f = toTx.shift(); tx.onReply(f.seq, f.msg, step); }
    }
    maxQueued = Math.max(maxQueued, toRx.filter((f) => f.kind === 'data').length);
  }
  assert.equal(tx.phase, 'delivered');
  assert.equal(rx.phase, 'complete');
  assert.ok(maxQueued <= 256, `max queued ${maxQueued}`);
  assert.ok(maxQueued > 192, `window was exercised (max queued ${maxQueued})`);
});

test('tx: final ack (done: true) is only valid once, after done, for the last chunk', () => {
  const ready = (chunks = 3) => {
    const t = new TxWindow({ chunks, now: 0 });
    t.onReply(1, { type: 'accept' }, 0);
    for (let i = 0; i < chunks; i++) t.nextDataSeq(0);
    return t;
  };
  assert.throws(() => ready().onReply(2, { type: 'ack', seq: 3, done: true }, 0), /Unexpected final ack \(sending\)/);
  const wrongSeq = ready();
  wrongSeq.doneSeq(0);
  assert.throws(() => wrongSeq.onReply(2, { type: 'ack', seq: 2, done: true }, 0), /Unexpected final ack/);
  const twice = ready();
  twice.doneSeq(0);
  twice.onReply(2, { type: 'ack', seq: 3, done: true }, 0);
  assert.throws(() => twice.onReply(3, { type: 'ack', seq: 3, done: true }, 0), /Unexpected final ack/);
  const junk = ready();
  junk.doneSeq(0);
  assert.throws(() => junk.onReply(2, { type: 'ack', seq: 3, done: 'yes' }, 0), /Invalid ack/);
});

test('tx: missing complete after the final ack is unconfirmed, not a timeout abort', () => {
  const T = P.COMPLETE_TIMEOUT_MS;
  const tx = new TxWindow({ chunks: 1, now: 0 });
  tx.onReply(1, { type: 'accept' }, 0);
  tx.nextDataSeq(0);
  tx.doneSeq(10);
  assert.equal(tx.endUnconfirmed(), false, 'done not acknowledged yet');
  tx.onReply(2, { type: 'ack', seq: 1, done: true }, 20);
  assert.equal(tx.timedOut(10 + T - 1), null);
  assert.equal(tx.timedOut(10 + T), 'unconfirmed');
  assert.equal(tx.endUnconfirmed(), true);
  assert.equal(tx.phase, 'unconfirmed');
  assert.equal(tx.active, false);
  assert.equal(tx.abortSeq('timeout'), null, 'nothing is sent to the peer');
  assert.equal(tx.timedOut(10 ** 12), null);
  assert.equal(tx.endUnconfirmed(), false, 'only once');

  // Transport lost before the final ack: still an interruption, not unconfirmed.
  const early = new TxWindow({ chunks: 1, now: 0 });
  early.onReply(1, { type: 'accept' }, 0);
  early.nextDataSeq(0);
  early.doneSeq(0);
  assert.equal(early.endUnconfirmed(), false);
  assert.equal(early.timedOut(T), 'complete');

  // Mid-stream: never unconfirmed.
  const mid = new TxWindow({ chunks: 5, now: 0 });
  mid.onReply(1, { type: 'accept' }, 0);
  mid.nextDataSeq(0);
  assert.equal(mid.endUnconfirmed(), false);
});
