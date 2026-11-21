// Spawns server.js on a free port with an explicit environment.
import { spawn } from 'node:child_process';
import { createHmac, randomBytes } from 'node:crypto';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

export const TEST_PRO_TOKEN_SECRET = 'test-pro-token-secret-0123456789abcdef';
export const TEST_NOWPAYMENTS_API_KEY = 'test-nowpayments-api-key';
export const TEST_NOWPAYMENTS_IPN_SECRET = 'test-nowpayments-ipn-secret-0123456789ab';
// Refuses connections at once, so no test can reach the real NOWPayments API.
export const TEST_NOWPAYMENTS_API_URL = 'https://127.0.0.1:1';

// The periodic stats line in full: fixed keys, integers only (relayMB with one
// decimal), nothing else. Anything identifying would break the match.
export const STATS_LINE_RE =
  /^\[darkp2p\] stats conns=\d+ rooms=\d+ relayRooms=\d+ relayMB=\d+\.\d rejected429=\d+ stalls=\d+$/;

export async function freePort() {
  const srv = createServer();
  srv.listen(0, '127.0.0.1');
  await once(srv, 'listening');
  const { port } = srv.address();
  srv.close();
  await once(srv, 'close');
  return port;
}

export async function startServer(overrides = {}) {
  const port = await freePort();
  const env = {
    DOMAIN: 'test.local',
    PRO_TOKEN_SECRET: TEST_PRO_TOKEN_SECRET,
    NOWPAYMENTS_API_KEY: TEST_NOWPAYMENTS_API_KEY,
    NOWPAYMENTS_IPN_SECRET: TEST_NOWPAYMENTS_IPN_SECRET,
    NOWPAYMENTS_API_URL: TEST_NOWPAYMENTS_API_URL,
    ...overrides,
    PORT: String(port),
  };
  for (const [k, v] of Object.entries(env)) if (v === undefined) delete env[k];

  const child = spawn(process.execPath, ['server.js'], {
    cwd: ROOT,
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const logs = { stdout: '', stderr: '', get all() { return this.stdout + this.stderr; } };
  let exited = null;
  const exitPromise = new Promise((resolve) => {
    child.once('exit', (code, signal) => {
      exited = { code, signal };
      resolve(exited);
    });
  });

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`server did not log "listening on" within 5s\n${logs.all}`));
    }, 5000);
    const check = () => {
      if (/listening on/.test(logs.stdout)) {
        clearTimeout(timer);
        resolve();
      }
    };
    child.stdout.on('data', (d) => { logs.stdout += d; check(); });
    child.stderr.on('data', (d) => { logs.stderr += d; });
    exitPromise.then(({ code, signal }) => {
      clearTimeout(timer);
      reject(new Error(`server exited early (code=${code}, signal=${signal})\n${logs.all}`));
    });
  });

  return {
    port,
    logs,
    child,
    isAlive: () => exited === null,
    async stop() {
      if (exited) return exited;
      child.kill('SIGTERM');
      let timer;
      const timeout = new Promise((resolve) => {
        timer = setTimeout(() => resolve(null), 6000);
      });
      const result = await Promise.race([exitPromise, timeout]);
      clearTimeout(timer);
      if (result === null) child.kill('SIGKILL');
      assert.ok(result !== null, 'server did not exit within 6s of SIGTERM');
      return result;
    },
  };
}

// NOWPayments IPN signing: hex HMAC-SHA512 over the JSON with keys sorted
// recursively. Independent of server.js, so the tests check its scheme.
function sortKeysDeep(value) {
  if (Array.isArray(value)) return value.map(sortKeysDeep);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((k) => [k, sortKeysDeep(value[k])]));
  }
  return value;
}

export function signIpn(payload, secret = TEST_NOWPAYMENTS_IPN_SECRET) {
  return createHmac('sha512', secret).update(JSON.stringify(sortKeysDeep(payload))).digest('hex');
}

// POSTs an IPN. The body is sent with its keys in the given (unsorted) order.
export function postIpn(port, payload, { secret, sig } = {}) {
  return fetch(`http://127.0.0.1:${port}/webhook/nowpayments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-nowpayments-sig': sig ?? signIpn(payload, secret) },
    body: JSON.stringify(payload),
  });
}

// A finished IPN for one of our orders, as NOWPayments sends it.
export function finishedIpn(fields = {}) {
  const paymentId = fields.payment_id ?? String(4000000000 + Math.floor(Math.random() * 1e9));
  return {
    payment_status: 'finished',
    payment_id: Number(paymentId),
    invoice_id: 5000000000 + Math.floor(Math.random() * 1e9),
    order_id: `pro-1d:${randomBytes(16).toString('hex')}`,
    order_description: 'DARKP2P Pro 24h',
    price_amount: 2,
    price_currency: 'usd',
    pay_amount: 0.0123,
    actually_paid: 0.0123,
    pay_currency: 'xmr',
    outcome_amount: 0.0121,
    outcome_currency: 'xmr',
    fee: { currency: 'xmr', depositFee: 0, withdrawalFee: 0, serviceFee: 0.0002 },
    created_at: new Date(Date.now() - 600_000).toISOString(),
    updated_at: new Date().toISOString(),
    ...fields,
  };
}
