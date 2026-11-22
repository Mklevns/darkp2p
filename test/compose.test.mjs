// Renders docker-compose.yml with dummy values and checks the darkp2p env.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
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
function runComposeConfig(dotEnv, interpolationEnv = fixtureText) {
  const dir = mkdtempSync(join(tmpdir(), 'darkp2p-compose-'));
  try {
    copyFileSync(join(ROOT, 'docker-compose.yml'), join(dir, 'docker-compose.yml'));
    writeFileSync(join(dir, '.env'), dotEnv);
    writeFileSync(join(dir, 'interpolation.env'), interpolationEnv);
    // DOMAIN must come from the env file alone, not from the caller's shell.
    const env = { ...process.env };
    delete env.DOMAIN;
    return spawnSync(
      'docker',
      ['compose', '--project-directory', dir, '-f', join(dir, 'docker-compose.yml'),
        '--env-file', join(dir, 'interpolation.env'), 'config', '--format', 'json'],
      { encoding: 'utf8', env }
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function renderCompose(dotEnv) {
  const r = runComposeConfig(dotEnv);
  assert.equal(r.status, 0, `docker compose config failed:\n${r.stderr}`);
  return JSON.parse(r.stdout);
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
  assert.equal(env.NOWPAYMENTS_API_KEY, fixture.NOWPAYMENTS_API_KEY);
  assert.equal(env.NOWPAYMENTS_IPN_SECRET, fixture.NOWPAYMENTS_IPN_SECRET);
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
  const tor = cfg.services.tor;
  assert.ok(tor.command == null, 'tor must not install or configure at runtime');
  assert.ok(tor.build?.context?.endsWith('/tor'), 'tor not built from ./tor');
  const torrcMount = tor.volumes.find((v) => v.target === '/etc/tor/torrc');
  assert.ok(torrcMount, 'torrc not mounted');
  assert.equal(torrcMount.read_only, true);
  const torrc = readFileSync(join(ROOT, 'tor', 'torrc'), 'utf8');
  const lines = torrc.split('\n').map((l) => l.trim());
  for (const want of ['SocksPort 0', 'HiddenServiceDir /var/lib/tor/hidden_service/',
    'HiddenServicePort 80 darkp2p:3001', 'HiddenServiceMaxStreams 256',
    'HiddenServiceMaxStreamsCloseCircuit 1']) {
    assert.ok(lines.includes(want), `torrc missing: ${want}`);
  }
  assert.doesNotMatch(torrc, /darkp2p:3000/);
  const df = readFileSync(join(ROOT, 'tor', 'Dockerfile'), 'utf8');
  assert.match(df, /^FROM alpine:3\.(2[2-9]|[3-9]\d)\.\d+$/m, 'tor base must be a supported Alpine (3.22+)');
  assert.match(df, /apk add --no-cache tor=\d+\.\d+\.\d+\.\d+-r\d+/);
  assert.match(df, /^USER tor$/m);
});

test('every service: no-new-privileges; bounded local logs except coturn', { skip }, () => {
  const { services } = renderCompose(fixtureText);
  for (const [name, svc] of Object.entries(services)) {
    assert.ok(svc.security_opt?.includes('no-new-privileges:true'), `${name}: no-new-privileges`);
    if (name === 'coturn') continue;
    assert.equal(svc.logging?.driver, 'local', `${name}: logging driver`);
    assert.deepEqual(svc.logging.options, { 'max-size': '10m', 'max-file': '3' }, `${name}: log rotation`);
  }
  const app = services.darkp2p;
  assert.equal(String(app.mem_limit), String(384 * 1024 * 1024));
  assert.equal(app.pids_limit, 128);
  assert.deepEqual(app.tmpfs, ['/tmp']);
  assert.equal(app.read_only, true);
  const caddy = services.caddy;
  assert.deepEqual(caddy.cap_drop, ['ALL']);
  assert.deepEqual(caddy.cap_add, ['NET_BIND_SERVICE']);
  assert.match(caddy.image, /^caddy:2\.\d+\.\d+-alpine@sha256:[a-f0-9]{64}$/);
  assert.match(readFileSync(join(ROOT, 'Dockerfile'), 'utf8'), /^FROM node:22\.\d+\.\d+-alpine3\.\d+@sha256:[a-f0-9]{64}$/m);
});

test('tor and coturn: capabilities dropped, memory and pids limited', { skip }, () => {
  const { services } = renderCompose(fixtureText);
  for (const name of ['tor', 'coturn']) {
    const svc = services[name];
    assert.deepEqual(svc.cap_drop, ['ALL'], `${name}: cap_drop`);
    assert.equal(String(svc.mem_limit), String(256 * 1024 * 1024), `${name}: mem_limit`);
    assert.equal(svc.pids_limit, 128, `${name}: pids_limit`);
  }
  assert.ok(services.tor.cap_add == null, 'tor needs no capability');
  // The coturn image sets the NET_BIND_SERVICE file capability on turnserver;
  // without it in the bounding set the kernel refuses to exec the binary.
  assert.deepEqual(services.coturn.cap_add, ['NET_BIND_SERVICE']);
});

test('compose refuses a missing or empty DOMAIN', { skip }, () => {
  const drop = fixtureText.split('\n').filter((l) => !l.startsWith('DOMAIN=')).join('\n');
  for (const [label, envText] of [['missing', drop], ['empty', `${drop}\nDOMAIN=\n`]]) {
    const r = runComposeConfig(fixtureText, envText);
    assert.notEqual(r.status, 0, `compose accepted a ${label} DOMAIN`);
    assert.match(r.stderr, /set DOMAIN in \.env/, label);
  }
});

test('coturn: config file only, no secret or interpolation in compose', { skip }, () => {
  const coturn = renderCompose(fixtureText).services.coturn;
  assert.deepEqual(coturn.command, ['-c', '/etc/coturn/turnserver.conf']);
  const mount = coturn.volumes.find((v) => v.target === '/etc/coturn/turnserver.conf');
  assert.ok(mount, 'turnserver.conf not mounted');
  assert.equal(mount.read_only, true);
  assert.equal(coturn.logging?.driver, 'none');
  assert.match(
    coturn.image,
    /^coturn\/coturn:4\.6\.\d+-r\d+-alpine@sha256:[a-f0-9]{64}$/,
    'coturn image not pinned to an exact 4.6.x tag and digest'
  );
  const raw = readFileSync(join(ROOT, 'docker-compose.yml'), 'utf8');
  assert.doesNotMatch(raw, /TURN_SECRET|static-auth-secret/);
  assert.ok(!JSON.stringify(coturn).includes(fixture.TURN_SECRET));
});

// No docker needed: every path the Dockerfile copies must survive the
// allowlist, and nothing else may.
test('.dockerignore is an allowlist of exactly what Dockerfile copies', () => {
  const rules = readFileSync(join(ROOT, '.dockerignore'), 'utf8')
    .split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));
  assert.equal(rules[0], '*', 'the first rule must ignore everything');
  const allowed = rules.slice(1).map((r) => {
    assert.ok(r.startsWith('!'), `only "!" exceptions may follow "*": ${r}`);
    return r.slice(1).replace(/\/$/, '');
  }).sort();
  const copied = [];
  for (const line of readFileSync(join(ROOT, 'Dockerfile'), 'utf8').split('\n')) {
    const m = /^COPY\s+(.+)$/.exec(line.trim());
    if (m) copied.push(...m[1].split(/\s+/).slice(0, -1).filter((a) => !a.startsWith('--')));
  }
  assert.deepEqual(allowed, copied.map((c) => c.replace(/^\.\//, '')).sort());
  for (const secret of ['.env', 'coturn', '.git', 'node_modules', 'test', 'scripts']) {
    assert.ok(!allowed.includes(secret), `${secret} must stay out of the build context`);
  }
});

// gen-turnserver-conf.sh needs bash and runs without docker.
const GEN = join(ROOT, 'scripts', 'gen-turnserver-conf.sh');

function runGen(envText) {
  const dir = mkdtempSync(join(tmpdir(), 'darkp2p-turnconf-'));
  writeFileSync(join(dir, 'env'), envText);
  const out = join(dir, 'out', 'turnserver.conf');
  const r = spawnSync('bash', [GEN, join(dir, 'env'), out],
    { encoding: 'utf8', env: { ...process.env, COTURN_OWNER: '' } });
  return { dir, out, r };
}

test('gen-turnserver-conf: hardened coturn flags, secret only in a 0600 file', () => {
  const { dir, out, r } = runGen(fixtureText);
  try {
    assert.equal(r.status, 0, r.stderr);
    assert.ok(!r.stdout.includes(fixture.TURN_SECRET) && !r.stderr.includes(fixture.TURN_SECRET));
    assert.equal(statSync(out).mode & 0o777, 0o600);
    const lines = readFileSync(out, 'utf8').split('\n');
    for (const want of [
      `static-auth-secret=${fixture.TURN_SECRET}`, `realm=${fixture.TURN_HOST}`, 'use-auth-secret',
      'no-tcp-relay', 'total-quota=40', 'no-stdout-log', 'simple-log', 'log-file=/dev/null', 'no-tls', 'no-dtls',
      'denied-peer-ip=::ffff:0.0.0.0-::ffff:255.255.255.255', 'denied-peer-ip=::1',
      'denied-peer-ip=127.0.0.0-127.255.255.255', 'denied-peer-ip=10.0.0.0-10.255.255.255',
    ]) {
      assert.ok(lines.includes(want), `missing: ${want}`);
    }
    // coturn 4.6.3 answers 403 Forbidden IP for every IPv4 peer when the
    // unspecified address is denied, and denying the server's own address
    // blocks relay-to-relay traffic (both peers on TURN). Neither may return.
    const denied = lines.filter((l) => l.startsWith('denied-peer-ip=')).map((l) => l.slice(15));
    assert.deepEqual(denied.filter((d) => /^::(-::)?$/.test(d)), []);
    assert.ok(!denied.includes(fixture.TURN_PUBLIC_IP), 'the server\'s own address must not be denied');
    assert.deepEqual(lines.filter((l) => l !== 'no-stdout-log' && /stdout/i.test(l)), []);
    assert.deepEqual(lines.filter((l) => /^(cert|pkey|tls-listening-port)=/.test(l)), []);
    // Without TURN_LISTEN_IP / TURN_EXTERNAL_IP coturn keeps its defaults.
    assert.deepEqual(lines.filter((l) => /^(listening-ip|relay-ip|external-ip)=/.test(l)), []);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('gen-turnserver-conf: TURN_LISTEN_IP / TURN_EXTERNAL_IP for a 1:1-NAT address', () => {
  const addrLines = (out) => readFileSync(out, 'utf8').split('\n')
    .filter((l) => /^(listening-ip|relay-ip|external-ip)=/.test(l));
  let run = runGen(`${fixtureText}\nTURN_LISTEN_IP=10.20.0.5\nTURN_EXTERNAL_IP=203.0.113.10\n`);
  try {
    assert.equal(run.r.status, 0, run.r.stderr);
    assert.deepEqual(addrLines(run.out),
      ['listening-ip=10.20.0.5', 'relay-ip=10.20.0.5', 'external-ip=203.0.113.10/10.20.0.5']);
    assert.equal(statSync(run.out).mode & 0o777, 0o600);
  } finally {
    rmSync(run.dir, { recursive: true, force: true });
  }
  run = runGen(`${fixtureText}\nTURN_LISTEN_IP=203.0.113.10\n`);
  try {
    assert.equal(run.r.status, 0, run.r.stderr);
    assert.deepEqual(addrLines(run.out), ['listening-ip=203.0.113.10', 'relay-ip=203.0.113.10']);
  } finally {
    rmSync(run.dir, { recursive: true, force: true });
  }
  for (const [label, extra, re] of [
    ['bad listen IP', 'TURN_LISTEN_IP=10.20.0.256', /TURN_LISTEN_IP/],
    ['listen IP with a second conf line', 'TURN_LISTEN_IP=10.20.0.5 no-auth', /TURN_LISTEN_IP/],
    ['IPv6 listen IP', 'TURN_LISTEN_IP=2001:db8::1', /TURN_LISTEN_IP/],
    ['bad external IP', 'TURN_LISTEN_IP=10.20.0.5\nTURN_EXTERNAL_IP=example.test', /TURN_EXTERNAL_IP/],
    ['external without listen', 'TURN_EXTERNAL_IP=203.0.113.10', /TURN_EXTERNAL_IP needs TURN_LISTEN_IP/],
    ['external equal to listen', 'TURN_LISTEN_IP=203.0.113.10\nTURN_EXTERNAL_IP=203.0.113.10', /are the same/],
  ]) {
    const bad = runGen(`${fixtureText}\n${extra}\n`);
    try {
      assert.notEqual(bad.r.status, 0, label);
      assert.match(bad.r.stderr, re, label);
      assert.throws(() => statSync(bad.out), label);
    } finally {
      rmSync(bad.dir, { recursive: true, force: true });
    }
  }
});

test('gen-turnserver-conf: refuses a directory at the target path and leaves it empty', () => {
  // What `docker compose up` leaves behind when it runs before the generator.
  const dir = mkdtempSync(join(tmpdir(), 'darkp2p-turnconf-'));
  try {
    writeFileSync(join(dir, 'env'), fixtureText);
    const out = join(dir, 'coturn', 'turnserver.conf');
    mkdirSync(out, { recursive: true });
    const r = spawnSync('bash', [GEN, join(dir, 'env'), out],
      { encoding: 'utf8', env: { ...process.env, COTURN_OWNER: '' } });
    assert.notEqual(r.status, 0, 'the generator wrote into a directory');
    assert.match(r.stderr, /is a directory/);
    assert.match(r.stderr, /rmdir/);
    assert.ok(!r.stderr.includes(fixture.TURN_SECRET));
    assert.ok(statSync(out).isDirectory());
    assert.deepEqual(readdirSync(out), []);
    assert.deepEqual(readdirSync(join(dir, 'coturn')), ['turnserver.conf'], 'a temp file was left behind');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('docs name --force-recreate for coturn and a tor restart after darkp2p is recreated', () => {
  const readme = readFileSync(join(ROOT, 'README.md'), 'utf8');
  const gen = readFileSync(GEN, 'utf8');
  assert.match(readme, /docker compose up -d --force-recreate coturn/);
  assert.match(gen, /docker compose up -d --force-recreate coturn/);
  assert.doesNotMatch(readme, /docker compose up -d coturn\s+# pick up/);
  assert.match(readme, /docker compose restart tor/);
  assert.match(gen, /docker compose restart tor/);
});

test('check-turn-relay.sh parses and uses the coturn image pinned in docker-compose.yml', () => {
  const script = join(ROOT, 'scripts', 'check-turn-relay.sh');
  const r = spawnSync('bash', ['-n', script], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  const text = readFileSync(script, 'utf8');
  // Both relay paths: an ordinary peer, and relay-to-relay.
  assert.match(text, /turnutils_peer/);
  assert.match(text, /turnutils_uclient/);
  assert.match(text, /\s-y\b/);
  assert.match(text, /docker-compose\.yml/);
  assert.doesNotMatch(text, /coturn\/coturn:\d/, 'the image must come from docker-compose.yml, not a second pin');
});

test('gen-turnserver-conf: refuses a missing or bad TURN_PUBLIC_IP / TURN_SECRET', () => {
  const drop = (key) => fixtureText.split('\n').filter((l) => !l.startsWith(`${key}=`)).join('\n');
  for (const [label, envText, re] of [
    ['no public IP', drop('TURN_PUBLIC_IP'), /TURN_PUBLIC_IP/],
    ['bad public IP', `${drop('TURN_PUBLIC_IP')}\nTURN_PUBLIC_IP=203.0.113.300\n`, /TURN_PUBLIC_IP/],
    ['no secret', drop('TURN_SECRET'), /TURN_SECRET/],
    ['secret with space', `${drop('TURN_SECRET')}\nTURN_SECRET=abcdefgh ijklmnopq\n`, /TURN_SECRET/],
  ]) {
    const { dir, out, r } = runGen(envText);
    try {
      assert.notEqual(r.status, 0, label);
      assert.match(r.stderr, re, label);
      assert.throws(() => statSync(out), label);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('setup-ufw.sh opens the same relay range coturn is configured with', () => {
  const gen = readFileSync(GEN, 'utf8');
  const ufw = readFileSync(join(ROOT, 'setup-ufw.sh'), 'utf8');
  const num = (text, key) => Number(new RegExp(`^${key}=(\\d+)$`, 'm').exec(text)?.[1]);
  assert.ok(num(gen, 'MIN_PORT') > 0 && num(gen, 'MAX_PORT') > num(gen, 'MIN_PORT'));
  assert.equal(num(ufw, 'TURN_MIN_PORT'), num(gen, 'MIN_PORT'));
  assert.equal(num(ufw, 'TURN_MAX_PORT'), num(gen, 'MAX_PORT'));
});
