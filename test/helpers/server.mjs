// Spawns server.js on a free port with an explicit environment.
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

export const TEST_PRO_TOKEN_SECRET = 'test-pro-token-secret-0123456789abcdef';
export const TEST_BTCPAY_WEBHOOK_SECRET = 'test-btcpay-webhook-secret-0123456789ab';

async function freePort() {
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
    BTCPAY_WEBHOOK_SECRET: TEST_BTCPAY_WEBHOOK_SECRET,
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
