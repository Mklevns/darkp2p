// Runs the executable inline <script> of public/index.html outside a browser:
// a minimal fake DOM, the real WebCrypto, a real WebSocket (the `ws` package)
// to a real server.js, and optionally a fake RTCPeerConnection. This covers the
// page's connection/UI glue, which the pure-block tests cannot reach.
//
// The script runs in this realm (new Function, as the pure-block tests do), so
// `instanceof ArrayBuffer` and `instanceof WebSocket` behave as in a browser.
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import NodeWebSocket from 'ws';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

// Same selection rules as scripts/check-inline-js.mjs and server.js.
function inlineScript() {
  const html = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8').replace(/\r\n?/g, '\n');
  const blocks = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    if (/\bsrc\s*=/i.test(m[1])) continue;
    const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(m[1]);
    if (type && !/^(module|text\/javascript|application\/javascript)$/i.test(type[1])) continue;
    blocks.push(m[2]);
  }
  if (blocks.length !== 1) throw new Error(`expected 1 executable inline script, found ${blocks.length}`);
  return blocks[0];
}

const SOURCE = inlineScript();

// Names the script reads as globals and the harness replaces.
const GLOBALS = ['document', 'window', 'location', 'history', 'localStorage', 'navigator', 'WebSocket',
  'RTCPeerConnection', 'URL', 'fetch', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'];
// eslint-disable-next-line no-new-func
const runScript = new Function(...GLOBALS, SOURCE);

export function roomHash(keyBytes = randomBytes(32)) {
  return `#${randomBytes(16).toString('hex')}:${Buffer.from(keyBytes).toString('base64url')}`;
}

// A link to the same room with a different key (an altered link).
export function withOtherKey(hash) {
  return `${hash.split(':')[0]}:${randomBytes(32).toString('base64url')}`;
}

export function fakeFile(name, size) {
  const bytes = randomBytes(size);
  return {
    name,
    size,
    type: 'application/octet-stream',
    bytes,
    slice: (start, end) => ({ arrayBuffer: async () => new Uint8Array(bytes.subarray(start, end)).buffer }),
  };
}

class FakeElement {
  #page;
  #text = '';
  #disabled = false;

  constructor(page, id = null) {
    this.#page = page;
    this.id = id;
    this.children = [];
    this.parent = null;
    this.className = '';
    this.value = '';
    this.hidden = false;
    this.checked = false;
    this.dataset = {};
    this.style = {};
    this.scrollTop = 0;
    this.scrollHeight = 0;
    const classes = new Set();
    this.classList = {
      add: (c) => classes.add(c),
      remove: (c) => classes.delete(c),
      contains: (c) => classes.has(c),
      toggle: (c, on) => {
        const want = on === undefined ? !classes.has(c) : Boolean(on);
        if (want) classes.add(c); else classes.delete(c);
        return want;
      },
    };
  }

  get textContent() { return this.#text; }
  set textContent(v) {
    this.#text = String(v);
    if (this.id === 'dropLabel') {
      this.#page.events.push({ type: 'label', text: this.#text, enabled: this.#page.dropEnabled });
    }
  }

  get disabled() { return this.#disabled; }
  set disabled(v) {
    this.#disabled = Boolean(v);
    if (this.id === 'fileInput') this.#page.events.push({ type: 'drop', enabled: !this.#disabled });
  }

  appendChild(child) {
    child.parent = this;
    this.children.push(child);
    if (this.id === 'log') {
      this.#page.logs.push({ text: child.textContent.replace(/^> /, ''), cls: child.className });
      this.#page.events.push({ type: 'log', text: child.textContent.replace(/^> /, '') });
    }
    return child;
  }

  append(...nodes) { for (const n of nodes) this.appendChild(n); }

  // Inserted elements (the incoming-file offer) are tracked on the page.
  after(node) { this.#page.inserted.push(node); node.parent = this.#page; }

  remove() {
    const list = this.parent === this.#page ? this.#page.inserted : this.parent?.children;
    if (list && list.includes(this)) list.splice(list.indexOf(this), 1);
    this.parent = null;
  }

  querySelectorAll(selector) {
    const out = [];
    const walk = (el) => {
      for (const c of el.children) {
        if (selector === c.tagName) out.push(c);
        walk(c);
      }
    };
    walk(this);
    return out;
  }

  querySelector() { return null; }
  closest() { return null; }
  click() {}
  select() {}
  addEventListener() {}
  removeEventListener() {}
}

// A pair of linked RTCPeerConnection fakes. Signaling still goes through the
// real server: the SDP only carries the fake connection's id.
export function createFakeRtcNet() {
  const net = { pcs: new Map(), nextId: 1, connectOnAnswer: true, candidateType: 'host' };

  class FakeDataChannel {
    constructor() {
      this.readyState = 'connecting';
      this.bufferedAmount = 0;
      this.peer = null;
      this.onopen = this.onclose = this.onerror = this.onmessage = null;
    }
    send(data) {
      if (this.readyState !== 'open') throw new Error('channel not open');
      const { peer } = this;
      setImmediate(() => { if (peer && peer.readyState === 'open' && peer.onmessage) peer.onmessage({ data }); });
    }
    close() {
      if (this.readyState === 'closed') return;
      this.readyState = 'closed';
      const { peer } = this;
      if (peer && peer.readyState !== 'closed') {
        peer.readyState = 'closed';
        setImmediate(() => { if (peer.onclose) peer.onclose(); });
      }
    }
    addEventListener() {}
    removeEventListener() {}
  }

  function connect(a, b) {
    const remote = new FakeDataChannel();
    remote.peer = a.channel;
    a.channel.peer = remote;
    b.channel = remote;
    setImmediate(() => {
      if (a.closed || b.closed) return;
      if (b.ondatachannel) b.ondatachannel({ channel: remote });
      for (const pc of [a, b]) {
        pc.channel.readyState = 'open';
        pc.emitState('connected');
        if (pc.channel.onopen) pc.channel.onopen();
      }
    });
  }

  function makeClass(page) {
    return class FakeRTCPeerConnection {
      constructor(config) {
        this.config = config;
        this.id = net.nextId++;
        this.connectionState = 'new';
        this.channel = null;
        this.remoteId = null;
        this.closed = false;
        this.onicecandidate = this.ondatachannel = this.onconnectionstatechange = null;
        net.pcs.set(this.id, this);
        page.pcs.push(this);
      }
      createDataChannel() {
        this.channel = new FakeDataChannel();
        return this.channel;
      }
      async createOffer() { return { type: 'offer', sdp: `fake-rtc ${this.id}` }; }
      async createAnswer() { return { type: 'answer', sdp: `fake-rtc ${this.id}` }; }
      async setLocalDescription() {}
      async setRemoteDescription(desc) {
        this.remoteId = Number(/^fake-rtc (\d+)$/.exec(desc.sdp)[1]);
        if (desc.type === 'answer' && net.connectOnAnswer) connect(this, net.pcs.get(this.remoteId));
      }
      async addIceCandidate() {}
      async getStats() {
        return new Map([
          ['T', { type: 'transport', selectedCandidatePairId: 'P' }],
          ['P', { type: 'candidate-pair', id: 'P', localCandidateId: 'L' }],
          ['L', { type: 'local-candidate', candidateType: net.candidateType }],
        ]);
      }
      close() {
        this.closed = true;
        this.connectionState = 'closed';
        if (this.channel) this.channel.close();
      }
      // Test hooks: what the browser would report.
      emitState(state) {
        this.connectionState = state;
        if (this.onconnectionstatechange) this.onconnectionstatechange();
      }
      emitCandidate(candidate) {
        if (this.onicecandidate) this.onicecandidate({ candidate });
      }
    };
  }

  return { net, makeClass };
}

// Loads the page. `rtc` is a createFakeRtcNet() result; without it the page
// sees no RTCPeerConnection at all (as in a browser with WebRTC disabled).
// `search` is the query string the page is opened with. `lazyLocation` makes
// location.hash and location.href keep their old value after the page assigns
// location.hash (until page.settleLocation()), as a browser may.
export function loadPage({ port, hash = '', search = '', lazyLocation = false, forceWsRelay = false,
  hideIp = false, rtc = null, storage = {} }) {
  const page = {
    logs: [],        // { text, cls } in order
    events: [],      // log lines, drop enable/disable and label changes, in order
    inserted: [],    // elements added next to the drop zone (file offers)
    sockets: [],     // every signaling WebSocket the page opened
    pcs: [],         // every RTCPeerConnection the page created
    downloads: [],   // Blobs handed to URL.createObjectURL
    closed: false,
  };
  const elements = new Map();
  const el = (id) => {
    if (!elements.has(id)) elements.set(id, new FakeElement(page, id));
    return elements.get(id);
  };
  page.el = el;
  Object.defineProperties(page, {
    dropEnabled: { get: () => !el('fileInput').disabled },
    label: { get: () => el('dropLabel').textContent },
    modeBadge: { get: () => el('modeBadge').textContent },
    ipBadge: { get: () => el('ipBadge').textContent },
  });
  // As in the HTML: the drop zone starts disabled.
  el('fileInput').disabled = true;
  el('dropLabel').textContent = 'WAITING FOR PEER HANDSHAKE...';
  el('forceWsRelay').checked = forceWsRelay;
  el('hideIpToggle').checked = hideIp;
  page.events.length = 0;

  const document = {
    getElementById: el,
    createElement: (tag) => {
      const e = new FakeElement(page);
      e.tagName = tag;
      return e;
    },
    querySelector: (sel) => el(`query:${sel}`),
    querySelectorAll: () => [],
    addEventListener() {},
  };

  let currentHash = hash;
  let currentSearch = search;
  let pendingHash = null;
  const location = {
    protocol: 'http:',
    host: `127.0.0.1:${port}`,
    pathname: '/',
    get origin() { return `http://${this.host}`; },
    get search() { return currentSearch; },
    get hash() { return currentHash; },
    set hash(v) {
      const next = v === '' || String(v).startsWith('#') ? String(v) : `#${v}`;
      page.hashAssignments.push(next);
      if (lazyLocation) pendingHash = next;
      else currentHash = next;
    },
    get href() { return `${this.origin}${this.pathname}${currentSearch}${currentHash}`; },
    assign() {},
  };
  page.hashAssignments = [];
  page.historyCalls = [];
  page.location = location;
  page.settleLocation = () => {
    if (pendingHash !== null) currentHash = pendingHash;
    pendingHash = null;
  };
  const history = {
    replaceState(state, title, url) {
      page.historyCalls.push(url);
      const u = new URL(url, location.href);
      currentSearch = u.search;
      if (lazyLocation) pendingHash = u.hash;
      else currentHash = u.hash;
    },
  };
  const store = new Map(Object.entries(storage));
  const localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
  };

  // Timers are tracked so a closed page leaves nothing running, and a test can
  // fire a long timer (the 30 s relay retry, the 12 s ICE timeout) at once.
  const timers = new Map();
  let timerSeq = 0;
  const addTimer = (kind, fn, ms, args) => {
    if (page.closed) return 0;
    const id = ++timerSeq;
    const run = () => {
      if (kind === 'timeout') timers.delete(id);
      fn(...args);
    };
    const handle = kind === 'timeout' ? setTimeout(run, ms) : setInterval(run, ms);
    timers.set(id, { kind, ms, handle, run });
    return id;
  };
  const dropTimer = (id) => {
    const t = timers.get(id);
    if (!t) return;
    (t.kind === 'timeout' ? clearTimeout : clearInterval)(t.handle);
    timers.delete(id);
  };
  page.pendingTimers = (ms) => [...timers.values()].filter((t) => t.kind === 'timeout' && t.ms === ms).length;
  // Fires every pending setTimeout with exactly this delay, now.
  page.runTimers = (ms) => {
    let fired = 0;
    for (const [id, t] of [...timers]) {
      if (t.kind !== 'timeout' || t.ms !== ms) continue;
      clearTimeout(t.handle);
      timers.delete(id);
      t.run();
      fired += 1;
    }
    return fired;
  };

  class PageWebSocket extends NodeWebSocket {
    constructor(url) {
      super(url, { origin: 'https://test.local' });
      this.sentText = [];
      this.sentBinary = 0;
      page.sockets.push(this);
      // An error always comes with a close; without a listener it would throw.
      this.on('error', () => {});
    }
    send(data, ...rest) {
      if (typeof data === 'string') this.sentText.push(JSON.parse(data));
      else this.sentBinary += 1;
      return super.send(data, ...rest);
    }
  }

  class PageURL extends URL {
    static createObjectURL(blob) {
      page.downloads.push(blob);
      return `blob:test/${page.downloads.length}`;
    }
    static revokeObjectURL() {}
  }

  const RTC = rtc ? rtc.makeClass(page) : undefined;
  runScript(
    document, {}, location, history, localStorage, {}, PageWebSocket, RTC, PageURL,
    async () => { throw new Error('no fetch in the page harness'); },
    (fn, ms = 0, ...args) => addTimer('timeout', fn, ms, args), dropTimer,
    (fn, ms = 0, ...args) => addTimer('interval', fn, ms, args), dropTimer,
  );

  page.logTexts = () => page.logs.map((l) => l.text);
  page.hasLog = (re) => page.logs.some((l) => re.test(l.text));
  page.countLog = (re) => page.logs.filter((l) => re.test(l.text)).length;
  page.waitFor = async (pred, what = 'condition', timeoutMs = 5000) => {
    const end = Date.now() + timeoutMs;
    for (;;) {
      if (pred()) return;
      if (Date.now() > end) {
        throw new Error(`timed out waiting for ${what}\n  label: ${page.label}\n  mode: ${page.modeBadge}\n` +
          `  log:\n    ${page.logTexts().join('\n    ')}`);
      }
      await new Promise((r) => setTimeout(r, 10));
    }
  };
  page.waitForLabel = (re, timeoutMs) => page.waitFor(() => re.test(page.label), `label ${re}`, timeoutMs);
  page.waitForLog = (re, timeoutMs) => page.waitFor(() => page.hasLog(re), `log ${re}`, timeoutMs);
  // What the user does.
  page.chooseFile = (file) => el('fileInput').onchange({ target: { files: [file] } });
  page.clickDropzone = () => el('dropzone').onclick();
  page.setForceWsRelay = (checked) => {
    el('forceWsRelay').checked = checked;
    el('forceWsRelay').onchange({ target: el('forceWsRelay') });
  };
  page.offerButton = (text) => {
    for (const box of page.inserted) {
      const btn = box.querySelectorAll('button').find((b) => b.textContent === text);
      if (btn) return btn;
    }
    return null;
  };
  page.close = () => {
    page.closed = true;
    for (const id of [...timers.keys()]) dropTimer(id);
    for (const s of page.sockets) {
      try { s.terminate(); } catch {}
    }
    for (const pc of page.pcs) {
      pc.onconnectionstatechange = pc.ondatachannel = pc.onicecandidate = null;
    }
  };
  return page;
}
