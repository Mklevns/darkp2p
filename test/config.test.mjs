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

// Shape-valid dummies (documentation-style, not real wallets).
const XMR = `4${'A'.repeat(94)}`;
const BTC = 'bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq';
// startServer enables NOWPayments by default (test key and IPN secret).
const PAY_ENV = { TIP_XMR_ADDRESS: XMR, TIP_BTC_ADDRESS: BTC };
// Mainnet subaddress shape (8…, 95 chars) and the BIP-350 mainnet Taproot test
// vector (bc1p…, 62 chars, valid bech32m). Neither is a real tip wallet.
const XMR_SUB = `8${'Bc7'.repeat(31)}x`;
const BTC_TAPROOT = 'bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0';

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

const NO_PAYMENTS = { NOWPAYMENTS_API_KEY: undefined, NOWPAYMENTS_IPN_SECRET: undefined };
const DEFAULT_PRICES = { 'pro-1d': 2, 'pro-30d': 5 };

test('GET / has no placeholders, and DOMAIN replaces darkp2p.com', async () => {
  const { html, csp } = await getIndex(NO_PAYMENTS);
  assert.ok(!/YOUR/.test(html), 'YOUR* placeholder in served page');
  assert.ok(!/btcpay/i.test(html), 'BTCPay reference in served page');
  assert.ok(!html.includes('darkp2p.com/'), 'hardcoded darkp2p.com URL in served page');
  assert.match(html, /<link rel="canonical" href="https:\/\/test\.local\/" \/>/);
  assert.match(html, /<meta property="og:url" content="https:\/\/test\.local\/" \/>/);
  assert.match(html, /"url": "https:\/\/test\.local\/"/);

  const m = CONFIG_RE.exec(html);
  assert.ok(m, 'config block present');
  assert.deepEqual(JSON.parse(m[1]), { checkoutEnabled: false, priceUsd: DEFAULT_PRICES, tipXmr: null, tipBtc: null });
  // The checkout buttons ship hidden and carry no link; the script reveals them.
  assert.match(html, /<button type="button" class="tier-cta" data-tier="pro-1d" hidden>/);
  assert.match(html, /<button type="button" class="tier-cta" data-tier="pro-30d" hidden>/);
  assert.ok(!/<a [^>]*class="tier-cta"/.test(html));
  assertCspMatches(html, csp);
});

test('with NOWPayments and tips set, the config enables checkout and the CSP hash matches', async () => {
  const { html, csp } = await getIndex(PAY_ENV);
  assert.ok(!/YOUR/.test(html), 'YOUR* placeholder in served page');
  const m = CONFIG_RE.exec(html);
  assert.ok(m, 'config block present');
  assert.deepEqual(JSON.parse(m[1]), { checkoutEnabled: true, priceUsd: DEFAULT_PRICES, tipXmr: XMR, tipBtc: BTC });
  assert.ok(html.includes('class="tip-btn" data-coin="XMR"'));
  assert.ok(html.includes('class="tip-btn" data-coin="BTC"'));
  // The client posts the tier to /api/checkout and follows the returned URL.
  assert.ok(html.includes("fetch('/api/checkout'"));
  assert.ok(html.includes('body: JSON.stringify({ tier })'));
  assert.ok(html.includes("params.get('NP_id')"), 'client captures NOWPayments NP_id');
  // The API key, IPN secret and API URL never reach the page.
  for (const secret of ['test-nowpayments-api-key', 'test-nowpayments-ipn-secret', '127.0.0.1:1']) {
    assert.ok(!html.includes(secret), `${secret} leaked into the page`);
  }
  // NOWPayments is reached by navigation only, so the CSP needs no extra origin.
  assert.ok(!/nowpayments/i.test(csp), 'CSP should not need a NOWPayments origin');
  assertCspMatches(html, csp);

  // The executable script is identical with and without config, so its hash is too.
  const plain = await getIndex(NO_PAYMENTS);
  assert.deepEqual(executableScriptHashes(html), executableScriptHashes(plain.html));
});

test('PRO_*_PRICE_USD set the prices shown and charged', async () => {
  const { html } = await getIndex({ PRO_1D_PRICE_USD: '1.50', PRO_30D_PRICE_USD: '9' });
  const cfg = JSON.parse(CONFIG_RE.exec(html)[1]);
  assert.deepEqual(cfg.priceUsd, { 'pro-1d': 1.5, 'pro-30d': 9 });
});

test('the config block never contains a raw "<"', async () => {
  const { html } = await getIndex({ ...PAY_ENV, TIP_XMR_ADDRESS: XMR_SUB, TIP_BTC_ADDRESS: BTC_TAPROOT });
  const m = CONFIG_RE.exec(html);
  assert.ok(m, 'config block present');
  assert.ok(!m[1].includes('<'), 'raw "<" inside the config block');
  const count = (s) => (s.match(/<script\b/gi) || []).length;
  const source = readFileSync(join(ROOT, 'public', 'index.html'), 'utf8');
  assert.equal(count(html), count(source));
});

function bootFails(env) {
  return spawnSync(process.execPath, ['server.js'], {
    cwd: ROOT,
    env: { DOMAIN: 'test.local', PORT: '0', ...env },
    encoding: 'utf8',
    timeout: 5000,
  });
}

const NP = { PRO_TOKEN_SECRET: 'x'.repeat(40), NOWPAYMENTS_API_KEY: 'k', NOWPAYMENTS_IPN_SECRET: 's'.repeat(32) };
for (const [label, env, msg] of [
  ['API key without IPN secret', { ...NP, NOWPAYMENTS_IPN_SECRET: '' }, /NOWPAYMENTS_API_KEY is set but NOWPAYMENTS_IPN_SECRET is not/],
  ['IPN secret without API key', { ...NP, NOWPAYMENTS_API_KEY: '' }, /NOWPAYMENTS_IPN_SECRET is set but NOWPAYMENTS_API_KEY is not/],
  ['API key without PRO_TOKEN_SECRET', { ...NP, PRO_TOKEN_SECRET: '' }, /PRO_TOKEN_SECRET is not/],
  ['http API URL', { ...NP, NOWPAYMENTS_API_URL: 'http://api.nowpayments.io' }, /NOWPAYMENTS_API_URL must be https/],
  ['unparseable API URL', { ...NP, NOWPAYMENTS_API_URL: 'not a url' }, /NOWPAYMENTS_API_URL is not a valid URL/],
  ['API URL with credentials', { ...NP, NOWPAYMENTS_API_URL: 'https://u:p@api.nowpayments.io' }, /must not contain a query/],
  ['zero price', { PRO_1D_PRICE_USD: '0' }, /PRO_1D_PRICE_USD must be a positive USD amount/],
  ['price with 3 decimals', { PRO_30D_PRICE_USD: '4.999' }, /PRO_30D_PRICE_USD must be a positive USD amount/],
  ['non-numeric price', { PRO_30D_PRICE_USD: 'five' }, /PRO_30D_PRICE_USD must be a positive USD amount/],
  ['bad XMR address', { TIP_XMR_ADDRESS: '4YOUR_MONERO_ADDRESS_HERE' }, /TIP_XMR_ADDRESS is not a valid/],
  ['XMR address with markup', { TIP_XMR_ADDRESS: `4${'A'.repeat(84)}</script>` }, /TIP_XMR_ADDRESS is not a valid/],
  ['bad BTC address', { TIP_BTC_ADDRESS: 'bc1qYOUR_BITCOIN_ADDRESS_HERE' }, /TIP_BTC_ADDRESS is not a valid/],
  ['testnet XMR address (9…)', { TIP_XMR_ADDRESS: `9${XMR_SUB.slice(1)}` }, /TIP_XMR_ADDRESS is not a valid/],
  ['stagenet XMR subaddress (7…)', { TIP_XMR_ADDRESS: `7${XMR_SUB.slice(1)}` }, /TIP_XMR_ADDRESS is not a valid/],
  ['94-character XMR subaddress', { TIP_XMR_ADDRESS: XMR_SUB.slice(0, 94) }, /TIP_XMR_ADDRESS is not a valid/],
  ['XMR subaddress with a non-base58 0', { TIP_XMR_ADDRESS: `8${'0'.repeat(94)}` }, /TIP_XMR_ADDRESS is not a valid/],
  ['testnet Taproot address (tb1p…)', { TIP_BTC_ADDRESS: `tb1p${BTC_TAPROOT.slice(4)}` }, /TIP_BTC_ADDRESS is not a valid/],
  ['uppercase Taproot address', { TIP_BTC_ADDRESS: BTC_TAPROOT.toUpperCase() }, /TIP_BTC_ADDRESS is not a valid/],
  ['Taproot address with a non-bech32 b', { TIP_BTC_ADDRESS: `${BTC_TAPROOT.slice(0, 20)}b${BTC_TAPROOT.slice(21)}` }, /TIP_BTC_ADDRESS is not a valid/],
  ['testnet P2PKH address (m…)', { TIP_BTC_ADDRESS: 'mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn' }, /TIP_BTC_ADDRESS is not a valid/],
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
  assert.equal(cfg.tipXmr, `8${'B'.repeat(105)}`);
});

test('8… mainnet subaddress and bc1p… Taproot boot clean and render with a matching CSP', async () => {
  assert.equal(XMR_SUB.length, 95);
  assert.equal(BTC_TAPROOT.length, 62);
  const { html, csp, logs } = await getIndex({ TIP_XMR_ADDRESS: XMR_SUB, TIP_BTC_ADDRESS: BTC_TAPROOT });
  assert.doesNotMatch(logs.all, /TIP_(XMR|BTC)_ADDRESS/);
  assert.match(logs.stdout, /listening on .*payments=on/);
  const cfg = JSON.parse(CONFIG_RE.exec(html)[1]);
  assert.deepEqual(cfg, { checkoutEnabled: true, priceUsd: DEFAULT_PRICES, tipXmr: XMR_SUB, tipBtc: BTC_TAPROOT });
  assert.ok(html.includes('class="tip-btn" data-coin="XMR"'));
  assert.ok(html.includes('class="tip-btn" data-coin="BTC"'));
  assertCspMatches(html, csp);
});
