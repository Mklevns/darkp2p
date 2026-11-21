// Boot-time config injection into index.html (Step 8, Task 8.1).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer } from './helpers/server.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const POS_URL = 'https://pay.test.local/apps/AbC123/pos';
// Shape-valid dummies (documentation-style, not real wallets).
const XMR = `4${'A'.repeat(94)}`;
const BTC = 'bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq';
const PAY_ENV = { BTCPAY_POS_URL: POS_URL, TIP_XMR_ADDRESS: XMR, TIP_BTC_ADDRESS: BTC };

const CONFIG_RE = /<script type="application\/json" id="darkp2p-config">([\s\S]*?)<\/script>/;

async function getIndex(env) {
  const srv = await startServer(env);
  try {
    const res = await fetch(`http://127.0.0.1:${srv.port}/`);
    assert.equal(res.status, 200);
    return { html: await res.text(), csp: res.headers.get('content-security-policy'), logs: srv.logs };
  } finally {
    await srv.stop();
  }
}

// Same selection rules as inlineScriptHashes() in server.js.
function executableScriptHashes(html) {
  const out = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    if (/\bsrc\s*=/i.test(m[1])) continue;
    const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(m[1]);
    if (type && !/^(module|text\/javascript|application\/javascript)$/i.test(type[1])) continue;
    out.push(createHash('sha256').update(m[2].replace(/\r\n?/g, '\n'), 'utf8').digest('base64'));
  }
  return out;
}

function assertCspMatches(html, csp) {
  const hashes = executableScriptHashes(html);
  assert.ok(hashes.length >= 1, 'served page has an executable inline script');
  for (const h of hashes) assert.ok(csp.includes(`'sha256-${h}'`), `CSP missing hash of served script: ${csp}`);
}

test('GET / has no placeholders, and DOMAIN replaces darkp2p.com', async () => {
  const { html, csp } = await getIndex({});
  assert.ok(!/YOUR/.test(html), 'YOUR* placeholder in served page');
  assert.ok(!html.includes('darkp2p.com/'), 'hardcoded darkp2p.com URL in served page');
  assert.match(html, /<link rel="canonical" href="https:\/\/test\.local\/" \/>/);
  assert.match(html, /<meta property="og:url" content="https:\/\/test\.local\/" \/>/);
  assert.match(html, /"url": "https:\/\/test\.local\/"/);

  const m = CONFIG_RE.exec(html);
  assert.ok(m, 'config block present');
  assert.deepEqual(JSON.parse(m[1]), { btcpayPosUrl: null, tipXmr: null, tipBtc: null });
  // Without config the CTAs ship hidden and carry no link.
  assert.match(html, /<a class="tier-cta" data-item-code="pro-1d" target="_blank" rel="noopener noreferrer" hidden>/);
  assertCspMatches(html, csp);
});

test('with the env set, the CTAs and config JSON are present and the CSP hash matches', async () => {
  const { html, csp } = await getIndex(PAY_ENV);
  assert.ok(!/YOUR/.test(html), 'YOUR* placeholder in served page');
  const m = CONFIG_RE.exec(html);
  assert.ok(m, 'config block present');
  assert.deepEqual(JSON.parse(m[1]), { btcpayPosUrl: POS_URL, tipXmr: XMR, tipBtc: BTC });
  for (const code of ['pro-1d', 'pro-30d']) {
    assert.ok(html.includes(`class="tier-cta" data-item-code="${code}"`), `${code} CTA missing`);
  }
  assert.ok(html.includes('class="tip-btn" data-coin="XMR"'));
  assert.ok(html.includes('class="tip-btn" data-coin="BTC"'));
  // The client builds `${btcpayPosUrl}?itemCode=…` from this exact config.
  assert.ok(html.includes('`${btcpayPosUrl}?itemCode=${encodeURIComponent(a.dataset.itemCode)}`'));
  assertCspMatches(html, csp);

  // The executable script is identical with and without config, so its hash is too.
  const plain = await getIndex({});
  assert.deepEqual(executableScriptHashes(html), executableScriptHashes(plain.html));
});

test('a </script> inside a config value cannot break out of the block', async () => {
  const evil = 'https://pay.test.local/a</script><script>alert(1)</script><!--';
  const { html, csp } = await getIndex({ BTCPAY_POS_URL: evil });
  const m = CONFIG_RE.exec(html);
  assert.ok(m, 'config block present');
  assert.ok(!m[1].includes('<'), 'raw "<" inside the config block');
  const cfg = JSON.parse(m[1]);
  assert.equal(new URL(cfg.btcpayPosUrl).host, 'pay.test.local');
  assert.ok(!cfg.btcpayPosUrl.includes('<'));
  // No script element was added: same count as the source file has.
  const count = (s) => (s.match(/<script\b/gi) || []).length;
  const source = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8');
  assert.equal(count(html), count(source));
  assert.ok(!html.includes('alert(1)</script>'));
  assertCspMatches(html, csp);
});

function bootFails(env) {
  return spawnSync(process.execPath, ['server.js'], {
    cwd: ROOT,
    env: { DOMAIN: 'test.local', PORT: '0', ...env },
    encoding: 'utf8',
    timeout: 5000,
  });
}

for (const [label, env, msg] of [
  ['http POS URL', { BTCPAY_POS_URL: 'http://pay.test.local/pos' }, /BTCPAY_POS_URL must be https/],
  ['javascript: POS URL', { BTCPAY_POS_URL: 'javascript:alert(1)' }, /BTCPAY_POS_URL must be https/],
  ['unparseable POS URL', { BTCPAY_POS_URL: 'not a url' }, /BTCPAY_POS_URL is not a valid URL/],
  ['POS URL with a query', { BTCPAY_POS_URL: 'https://pay.test.local/pos?x=1' }, /must not contain a query/],
  ['POS URL with credentials', { BTCPAY_POS_URL: 'https://u:p@pay.test.local/pos' }, /must not contain a query/],
  ['bad XMR address', { TIP_XMR_ADDRESS: '4YOUR_MONERO_ADDRESS_HERE' }, /TIP_XMR_ADDRESS is not a valid/],
  ['XMR address with markup', { TIP_XMR_ADDRESS: `4${'A'.repeat(84)}</script>` }, /TIP_XMR_ADDRESS is not a valid/],
  ['bad BTC address', { TIP_BTC_ADDRESS: 'bc1qYOUR_BITCOIN_ADDRESS_HERE' }, /TIP_BTC_ADDRESS is not a valid/],
]) {
  test(`refuses to start with a ${label}`, () => {
    const r = bootFails(env);
    assert.notEqual(r.status, 0, `server started with ${label}`);
    assert.match(r.stderr, msg);
  });
}

test('accepts legacy BTC and integrated XMR address shapes', async () => {
  const { html } = await getIndex({
    TIP_BTC_ADDRESS: '1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2',
    TIP_XMR_ADDRESS: `8${'B'.repeat(105)}`,
  });
  const cfg = JSON.parse(CONFIG_RE.exec(html)[1]);
  assert.equal(cfg.tipBtc, '1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2');
  assert.equal(cfg.btcpayPosUrl, null);
});
