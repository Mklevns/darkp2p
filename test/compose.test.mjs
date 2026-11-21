// Renders docker-compose.yml with dummy values and checks the darkp2p env.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, copyFileSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const FIXTURE = join(ROOT, 'test', 'fixtures', 'compose.env');

const probe = spawnSync('docker', ['compose', 'version'], { encoding: 'utf8' });
const skip = probe.status === 0 ? false : 'docker compose not available';

function parseEnvFile(text) {
  const out = {};
  for (const line of text.split('\n')) {
    const m = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
    if (m) out[m[1]] = m[2];
  }
  return out;
}

// The compose file reads a mandatory .env next to itself, so render it from a
// temp project dir whose .env we control. --env-file supplies interpolation.
function renderCompose(dotEnv) {
  const dir = mkdtempSync(join(tmpdir(), 'darkp2p-compose-'));
  try {
    copyFileSync(join(ROOT, 'docker-compose.yml'), join(dir, 'docker-compose.yml'));
    writeFileSync(join(dir, '.env'), dotEnv);
    const r = spawnSync(
      'docker',
      ['compose', '--project-directory', dir, '-f', join(dir, 'docker-compose.yml'),
        '--env-file', FIXTURE, 'config', '--format', 'json'],
      { encoding: 'utf8' }
    );
    assert.equal(r.status, 0, `docker compose config failed:\n${r.stderr}`);
    return JSON.parse(r.stdout);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const fixtureText = readFileSync(FIXTURE, 'utf8');
const fixture = parseEnvFile(fixtureText);

test('darkp2p env has no merged list items and keeps .env values', { skip }, () => {
  const env = renderCompose(fixtureText).services.darkp2p.environment;
  for (const [k, v] of Object.entries(env)) {
    assert.ok(!String(v ?? '').includes(' - '), `${k} contains a merged list item`);
  }
  assert.equal(env.TURN_TTL, fixture.TURN_TTL, 'TURN_TTL from .env was overridden');
  assert.equal(env.PRO_TOKEN_DAYS, fixture.PRO_TOKEN_DAYS);
  assert.equal(env.PRO_TOKEN_SECRET, fixture.PRO_TOKEN_SECRET);
  assert.equal(env.BTCPAY_WEBHOOK_SECRET, fixture.BTCPAY_WEBHOOK_SECRET);
  assert.equal(env.PORT, '3000');
  assert.equal(env.ONION_PORT, '3001');
});

test('darkp2p environment: interpolates nothing, only fixed non-secret values', { skip }, () => {
  // With an empty .env, anything left must come from `environment:` itself.
  const env = renderCompose('').services.darkp2p.environment;
  assert.deepEqual(env, { PORT: '3000', ONION_PORT: '3001' });
});

test('caddy keeps DOMAIN; tor forwards to the onion listener', { skip }, () => {
  const cfg = renderCompose(fixtureText);
  assert.equal(cfg.services.caddy.environment.DOMAIN, fixture.DOMAIN);
  const cmd = [].concat(cfg.services.tor.command).join(' ');
  assert.match(cmd, /HiddenServicePort 80 darkp2p:3001/);
  assert.doesNotMatch(cmd, /darkp2p:3000/);
});

test('coturn: no TCP relay, mapped-IPv4 deny, no stdout log, logging disabled', { skip }, () => {
  const coturn = renderCompose(fixtureText).services.coturn;
  const cmd = [].concat(coturn.command).map(String);
  assert.ok(cmd.includes('--no-tcp-relay'), 'missing --no-tcp-relay');
  assert.ok(cmd.includes('--denied-peer-ip=::ffff:0.0.0.0-::ffff:255.255.255.255'), 'missing ::ffff: deny');
  assert.ok(cmd.includes('--denied-peer-ip=::-::'), 'missing :: deny');
  assert.ok(cmd.includes('--denied-peer-ip=127.0.0.0-127.255.255.255'), 'missing 127/8 deny');
  assert.ok(cmd.includes(`--denied-peer-ip=${fixture.TURN_PUBLIC_IP}`), 'missing own public IP deny');
  const stdoutFlags = cmd.filter((a) => a !== '--no-stdout-log' && /stdout/i.test(a));
  assert.deepEqual(stdoutFlags, [], 'coturn logs to stdout');
  assert.ok(cmd.includes('--no-stdout-log'));
  assert.ok(cmd.includes('--log-file=/dev/null'));
  assert.ok(cmd.includes('--total-quota=40'));
  assert.equal(coturn.logging?.driver, 'none');
  assert.match(
    coturn.image,
    /^coturn\/coturn:4\.6\.\d+-r\d+-alpine@sha256:[a-f0-9]{64}$/,
    'coturn image not pinned to an exact 4.6.x tag and digest'
  );
});

test('compose refuses to render without TURN_PUBLIC_IP', { skip }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'darkp2p-compose-'));
  try {
    copyFileSync(join(ROOT, 'docker-compose.yml'), join(dir, 'docker-compose.yml'));
    const noIp = fixtureText.split('\n').filter((l) => !l.startsWith('TURN_PUBLIC_IP=')).join('\n');
    writeFileSync(join(dir, '.env'), noIp);
    writeFileSync(join(dir, 'interp.env'), noIp);
    const r = spawnSync(
      'docker',
      ['compose', '--project-directory', dir, '-f', join(dir, 'docker-compose.yml'),
        '--env-file', join(dir, 'interp.env'), 'config', '-q'],
      { encoding: 'utf8' }
    );
    assert.notEqual(r.status, 0);
    assert.match(r.stderr, /TURN_PUBLIC_IP/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
