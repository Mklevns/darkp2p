// The web side of the Android interop tests (android/transport, InteropTest).
//
// Started by a JUnit test, this process runs a real server.js and, on request,
// real copies of the page (test/helpers/page.mjs: the page's own script with a
// real WebSocket to that server). It is driven over stdin/stdout with one JSON
// object per line: {"req":n,"cmd":...} in, {"req":n,"ok":true|false,...} out.
// Nothing here knows about the protocol: the page does the talking.
import { spawn } from 'node:child_process';
import { createHash, createHmac } from 'node:crypto';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  freePort, TEST_PRO_TOKEN_SECRET, TEST_NOWPAYMENTS_API_KEY, TEST_NOWPAYMENTS_IPN_SECRET, TEST_NOWPAYMENTS_API_URL,
} from '../helpers/server.mjs';
import { loadPage } from '../helpers/page.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const pages = new Map();
let server = null; // { child, port, env, log }

// The same bytes the JVM side generates for (seed, size): SHA-256(seed | block) blocks.
export function fileBytes(seed, size) {
  const out = Buffer.alloc(size);
  for (let block = 0, off = 0; off < size; block++, off += 32) {
    createHash('sha256').update(`${seed}|${block}`).digest().copy(out, off, 0, Math.min(32, size - off));
  }
  return out;
}

async function startServer(env, port) {
  const fullEnv = {
    PATH: process.env.PATH,
    DOMAIN: 'test.local',
    PRO_TOKEN_SECRET: TEST_PRO_TOKEN_SECRET,
    NOWPAYMENTS_API_KEY: TEST_NOWPAYMENTS_API_KEY,
    NOWPAYMENTS_IPN_SECRET: TEST_NOWPAYMENTS_IPN_SECRET,
    NOWPAYMENTS_API_URL: TEST_NOWPAYMENTS_API_URL,
    ...env,
    PORT: String(port),
  };
  const child = spawn(process.execPath, ['server.js'], { cwd: ROOT, env: fullEnv, stdio: ['ignore', 'pipe', 'pipe'] });
  const state = { child, port, env, log: '' };
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`server did not start\n${state.log}`)), 5000);
    const onData = (d) => {
      state.log += d;
      if (/listening on/.test(state.log)) {
        clearTimeout(timer);
        resolve();
      }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.once('exit', () => reject(new Error(`server exited early\n${state.log}`)));
  });
  return state;
}

async function stopServer() {
  if (!server) return;
  const { child } = server;
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGKILL');
    await once(child, 'exit');
  }
}

const page = (id) => {
  const p = pages.get(id);
  if (!p) throw new Error(`no page ${id}`);
  return p;
};

// The page's own script on a fake DOM (test/helpers/page.mjs), behind the same
// few operations as a Chromium page (chromium.mjs).
function nodePage(opts) {
  const p = loadPage(opts);
  return {
    waitForLog: (re, timeoutMs) => p.waitForLog(re, timeoutMs),
    waitForLabel: (re, timeoutMs) => p.waitForLabel(re, timeoutMs),
    async state() {
      return {
        label: p.label, mode: p.modeBadge, ip: p.ipBadge, dropEnabled: p.dropEnabled,
        logs: p.logs.map((l) => l.text), errors: p.logs.filter((l) => l.cls === 'err').map((l) => l.text),
        sockets: p.sockets.length, downloads: p.downloads.length,
      };
    },
    async send({ name, bytes, mime }) {
      p.chooseFile({
        name, size: bytes.length, type: mime,
        slice: (start, end) => ({ arrayBuffer: async () => new Uint8Array(bytes.subarray(start, end)).buffer }),
      });
    },
    async click(text, timeoutMs) {
      await p.waitFor(() => p.offerButton(text), `the ${text} button`, timeoutMs);
      p.offerButton(text).onclick();
    },
    async download(index, timeoutMs) {
      await p.waitFor(() => p.downloads.length > index, `download ${index}`, timeoutMs);
      const bytes = Buffer.from(await p.downloads[index].arrayBuffer());
      return { size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    },
    async setForceWsRelay(checked) {
      p.setForceWsRelay(checked);
    },
    async clickDropzone() {
      p.clickDropzone();
    },
    async close() {
      p.close();
    },
  };
}

const commands = {
  async start({ env = {} }) {
    server = await startServer(env, await freePort());
    return { port: server.port };
  },
  // Kills the server at once (as a crash would) and leaves the port closed.
  async killServer() {
    await stopServer();
    return {};
  },
  async restartServer() {
    await stopServer();
    server = await startServer(server.env, server.port);
    return { port: server.port };
  },
  async serverLog() {
    return { log: server.log };
  },
  async token({ days = 1 }) {
    // As server.js mints it: "<expiry>.<hex HMAC-SHA256(secret, expiry)>".
    const exp = String(Math.floor(Date.now() / 1000) + days * 86400);
    return { token: `${exp}.${createHmac('sha256', TEST_PRO_TOKEN_SECRET).update(exp).digest('hex')}` };
  },
  // browser: "node" (the page's script on a fake DOM) or "chromium" (a real headless browser).
  async page({ id, hash, forceWsRelay = true, browser = 'node' }) {
    const opts = { port: server.port, hash, forceWsRelay };
    pages.set(id, browser === 'chromium' ? await (await import('./chromium.mjs')).loadChromiumPage(opts) : nodePage(opts));
    return {};
  },
  async closePage({ id }) {
    await page(id).close();
    pages.delete(id);
    return {};
  },
  async waitLog({ id, re, timeout = 5000 }) {
    await page(id).waitForLog(new RegExp(re), timeout);
    return {};
  },
  async waitLabel({ id, re, timeout = 5000 }) {
    await page(id).waitForLabel(new RegExp(re), timeout);
    return {};
  },
  async state({ id }) {
    return page(id).state();
  },
  async send({ id, name, seed, size, mime = 'application/octet-stream' }) {
    await page(id).send({ name, bytes: fileBytes(seed, size), mime });
    return {};
  },
  async accept({ id, timeout = 5000 }) {
    await page(id).click('Accept', timeout);
    return {};
  },
  async decline({ id, timeout = 5000 }) {
    await page(id).click('Decline', timeout);
    return {};
  },
  async download({ id, index, timeout = 30000 }) {
    return page(id).download(index, timeout);
  },
  async setForceWsRelay({ id, checked }) {
    await page(id).setForceWsRelay(checked);
    return {};
  },
  async clickDropzone({ id }) {
    await page(id).clickDropzone();
    return {};
  },
};

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const reply = (obj) => process.stdout.write(`${JSON.stringify(obj)}\n`);
  const rl = createInterface({ input: process.stdin });
  rl.on('line', (line) => {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }
    const fn = commands[msg.cmd];
    Promise.resolve()
      .then(() => {
        if (!fn) throw new Error(`unknown command ${msg.cmd}`);
        return fn(msg);
      })
      .then((res) => reply({ req: msg.req, ok: true, ...res }), (err) => reply({ req: msg.req, ok: false, error: String(err && err.message) }));
  });
  const shutdown = async () => {
    for (const p of pages.values()) await Promise.resolve(p.close()).catch(() => {});
    if (pages.size) await import('./chromium.mjs').then((m) => m.closeBrowser(), () => {});
    await stopServer();
    process.exit(0);
  };
  rl.on('close', shutdown); // the test process went away
  process.on('SIGTERM', shutdown);
  reply({ event: 'ready' });
}
