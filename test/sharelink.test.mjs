// The share link shown on the page: built from the room ID and key, not read
// back from location.href, which may lag behind a location.hash assignment.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startServer } from './helpers/server.mjs';
import { loadPage, roomHash } from './helpers/page.mjs';

const FRAGMENT_RE = /^[a-f0-9]{32}:[A-Za-z0-9_-]{43}$/;

async function withPage(opts, fn) {
  const srv = await startServer();
  const page = loadPage({ port: srv.port, ...opts });
  try {
    await fn(page, `http://127.0.0.1:${srv.port}`);
  } finally {
    page.close();
    await srv.stop();
  }
}

// What the join message carries is the room the page really uses.
async function joinedRoom(page) {
  await page.waitFor(() => page.sockets[0]?.sentText.some((m) => m.type === 'join'), 'the join message');
  return page.sockets[0].sentText.find((m) => m.type === 'join').room;
}

test('a fresh page shows the full #roomId:key link even while location.href lags behind', async () => {
  await withPage({ lazyLocation: true }, async (page, origin) => {
    // The browser has not applied the hash yet: location still has none.
    assert.equal(page.location.hash, '');
    assert.equal(page.location.href, `${origin}/`);

    const shown = page.el('shareUrl').value;
    assert.ok(shown.startsWith(`${origin}/#`), `share link lacks the room and key: ${shown}`);
    const fragment = shown.slice(`${origin}/#`.length);
    assert.match(fragment, FRAGMENT_RE);

    // The address bar is given the same fragment, once.
    assert.deepEqual(page.hashAssignments, [`#${fragment}`]);
    page.settleLocation();
    assert.equal(page.location.href, shown);
    // And it is the room the page joins.
    assert.equal(await joinedRoom(page), fragment.split(':')[0]);
  });
});

test('a fresh page with an up-to-date location shows the same kind of link', async () => {
  await withPage({}, async (page, origin) => {
    const shown = page.el('shareUrl').value;
    assert.equal(shown, page.location.href);
    assert.match(shown.slice(`${origin}/#`.length), FRAGMENT_RE);
  });
});

test('opening a share link shows that link and does not touch location.hash', async () => {
  const hash = roomHash();
  await withPage({ hash, lazyLocation: true }, async (page, origin) => {
    assert.equal(page.el('shareUrl').value, `${origin}/${hash}`);
    assert.deepEqual(page.hashAssignments, []);
    assert.equal(await joinedRoom(page), hash.slice(1).split(':')[0]);
  });
});

test('a malformed fragment is replaced by a fresh room and key', async () => {
  await withPage({ hash: '#not-a-room:short', lazyLocation: true }, async (page, origin) => {
    const shown = page.el('shareUrl').value;
    assert.match(shown.slice(`${origin}/#`.length), FRAGMENT_RE);
    assert.equal(page.hashAssignments.length, 1);
    assert.ok(!shown.includes('not-a-room'));
  });
});

test('payment return: the query is removed, the fragment kept, and the link has no payment ID', async () => {
  // A fresh visit with ?NP_id=...: the hash was assigned a moment ago and
  // location.hash is still empty when the URL is cleaned.
  await withPage({ search: '?NP_id=4242424242', lazyLocation: true }, async (page, origin) => {
    const shown = page.el('shareUrl').value;
    const fragment = shown.slice(`${origin}/#`.length);
    assert.match(fragment, FRAGMENT_RE);
    assert.ok(!shown.includes('NP_id') && !shown.includes('4242424242'), shown);
    // The cleaned URL keeps the room and key, although location.hash was stale.
    assert.deepEqual(page.historyCalls, [`/#${fragment}`]);
    page.settleLocation();
    assert.equal(page.location.href, shown);
    assert.equal(page.location.search, '');
  });

  // The same on an existing share link, and for the older ?invoiceId= form.
  const hash = roomHash();
  await withPage({ hash, search: '?invoiceId=77', lazyLocation: true }, async (page, origin) => {
    assert.equal(page.el('shareUrl').value, `${origin}/${hash}`);
    assert.deepEqual(page.historyCalls, [`/${hash}`]);
  });
});

test('without a payment return the URL is not rewritten', async () => {
  await withPage({ hash: roomHash() }, async (page) => {
    assert.deepEqual(page.historyCalls, []);
  });
});

// The link needs only random bytes. Without Web Crypto or WebSocket the page
// cannot do anything else, and must say so rather than die without a word.
for (const [name, opts, api] of [
  ['crypto.subtle', { noSubtle: true }, /Web Crypto/],
  ['WebSocket', { noWebSocket: true }, /WebSocket/],
]) {
  test(`without ${name} the link is still shown and the page says why it cannot run`, async () => {
    await withPage(opts, async (page, origin) => {
      assert.match(page.el('shareUrl').value.slice(`${origin}/#`.length), FRAGMENT_RE);
      assert.equal(page.countLog(api), 1);
      assert.equal(page.label, 'THIS BROWSER CANNOT RUN DARKP2P');
      assert.equal(page.dropEnabled, false);
      assert.equal(page.sockets.length, 0);
      // Time for a stray rejection to surface: node:test would fail on one.
      await new Promise((r) => setTimeout(r, 50));
    });
  });
}

test('an uncaught error or rejection is reported in the log, once', async () => {
  await withPage({}, async (page) => {
    page.windowListeners.get('unhandledrejection')({ reason: new Error('boom') });
    page.windowListeners.get('error')({ message: 'again' });
    assert.equal(page.countLog(/Something went wrong in the page/), 1);
    assert.ok(page.hasLog(/\(boom\)/));
  });
});
