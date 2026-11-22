// Static pages, assets and crawl files: routing, headers and page hygiene.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer, freePort } from './helpers/server.mjs';

const PUBLIC = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');
const PAGES = ['/', '/how-it-works', '/security', '/tor', '/compare', '/abuse'];
const ONION = `${'a'.repeat(56)}.onion`;
// The only other-origin URLs a page may mention: the source link, and on the
// app page the sponsored VPN link. Vocabulary URLs (schema.org) are not links.
const ALLOWED_EXTERNAL = [
  /^https:\/\/github\.com\/Mklevns\/darkp2p$/,
  /^https:\/\/pr\.tn\//,
  /^https:\/\/schema\.org$/,
];

let srv;
before(async () => {
  srv = await startServer();
});
after(async () => {
  if (srv) await srv.stop();
});

const base = () => `http://127.0.0.1:${srv.port}`;
const get = (path, init) => fetch(`${base()}${path}`, { redirect: 'manual', ...init });

function jsonLdBlocks(html) {
  const out = [];
  const re = /<script type="application\/ld\+json">([\s\S]*?)<\/script>/g;
  let m;
  while ((m = re.exec(html)) !== null) out.push(JSON.parse(m[1]));
  return out;
}

const text = (html) => html
  .replace(/<[^>]+>/g, '')
  .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
  .trim();

test('every page is 200 HTML with its own title, description, canonical, OG tags and one h1', async () => {
  const titles = new Set();
  const descriptions = new Set();
  for (const path of PAGES) {
    const res = await get(path);
    assert.equal(res.status, 200, path);
    assert.equal(res.headers.get('content-type'), 'text/html; charset=utf-8', path);
    const html = await res.text();

    const title = /<title>([^<]+)<\/title>/.exec(html);
    assert.ok(title, `${path}: title`);
    titles.add(title[1]);
    const desc = /<meta name="description" content="([^"]+)" \/>/.exec(html);
    assert.ok(desc, `${path}: description`);
    assert.ok(desc[1].length <= 160, `${path}: description is ${desc[1].length} chars`);
    descriptions.add(desc[1]);

    assert.equal((html.match(/<h1[\s>]/g) || []).length, 1, `${path}: exactly one h1`);
    assert.ok(html.includes(`<link rel="canonical" href="https://test.local${path}" />`), `${path}: canonical`);
    assert.ok(html.includes(`<meta property="og:url" content="https://test.local${path}" />`), `${path}: og:url`);
    for (const tag of [
      '<meta property="og:site_name" content="DarkP2P" />',
      '<meta property="og:image" content="https://test.local/og-image.png" />',
      '<meta property="og:image:width" content="1200" />',
      '<meta property="og:image:height" content="630" />',
      '<meta name="twitter:card" content="summary_large_image" />',
      '<meta name="twitter:image" content="https://test.local/og-image.png" />',
      '<meta name="theme-color" content="#0a0c0e" />',
      '<link rel="icon" href="/favicon.svg" type="image/svg+xml" />',
      '<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />',
    ]) {
      assert.ok(html.includes(tag), `${path}: missing ${tag}`);
    }
    assert.match(html, /<meta property="og:image:alt" content="[^"]+" \/>/, `${path}: og:image:alt`);
    assert.ok(!html.includes('darkp2p.com/'), `${path}: hardcoded darkp2p.com URL`);
    assert.ok(!/\{\{|<!--\/?onion:/.test(html), `${path}: unrendered template marker`);

    // The shared footer nav links every page.
    const nav = /<nav class="site-nav"[\s\S]*?<\/nav>/.exec(html);
    assert.ok(nav, `${path}: footer nav`);
    for (const target of PAGES) assert.ok(nav[0].includes(`href="${target}"`), `${path}: nav lacks ${target}`);
  }
  assert.equal(titles.size, PAGES.length, 'titles are unique');
  assert.equal(descriptions.size, PAGES.length, 'descriptions are unique');
});

test('every page carries valid JSON-LD', async () => {
  for (const path of PAGES) {
    const blocks = jsonLdBlocks(await (await get(path)).text());
    assert.equal(blocks.length, 1, `${path}: one JSON-LD block`);
    assert.equal(blocks[0]['@context'], 'https://schema.org', path);
    assert.ok(Array.isArray(blocks[0]['@graph']) && blocks[0]['@graph'].length >= 2, path);
  }
});

test('FAQPage JSON-LD on / matches the visible FAQ exactly', async () => {
  const html = await (await get('/')).text();
  const graph = jsonLdBlocks(html)[0]['@graph'];
  assert.ok(graph.some((n) => n['@type'] === 'SoftwareApplication' && n.name === 'DarkP2P'));
  const faq = graph.find((n) => n['@type'] === 'FAQPage');
  assert.ok(faq, 'FAQPage present');

  const visible = [];
  const re = /<details>\s*<summary>([\s\S]*?)<\/summary>\s*<p>([\s\S]*?)<\/p>\s*<\/details>/g;
  let m;
  while ((m = re.exec(html)) !== null) visible.push({ q: text(m[1]), a: text(m[2]) });
  assert.ok(visible.length >= 4, 'visible FAQ entries found');
  assert.deepEqual(
    faq.mainEntity.map((e) => ({ q: e.name, a: e.acceptedAnswer.text })),
    visible
  );
});

test('content pages run no script and load nothing from another origin', async () => {
  for (const path of PAGES) {
    const res = await get(path);
    const html = await res.text();
    const csp = res.headers.get('content-security-policy');
    assert.ok(csp.includes("default-src 'none'") && csp.includes("img-src 'self'"), `${path}: ${csp}`);
    if (path !== '/') {
      assert.ok(csp.includes("script-src 'none'"), `${path}: ${csp}`);
      assert.ok(!/<script(?![^>]*type="application\/ld\+json")/.test(html), `${path}: executable script`);
    }
    assert.ok(!/<(?:script|img|iframe|link|source|video|audio|embed|object)\b[^>]*\b(?:src|href)="(?:https?:)?\/\/(?!test\.local\/)/.test(html),
      `${path}: resource from another origin`);
    assert.ok(!/@import|url\(/.test(html), `${path}: CSS import or url()`);
    for (const [url] of html.matchAll(/https?:\/\/[^\s"'<>)]+/g)) {
      if (url.startsWith('https://test.local/')) continue;
      if (/^http:\/\/[a-z2-7]{56}\.onion\/$/.test(url)) continue;
      assert.ok(ALLOWED_EXTERNAL.some((re) => re.test(url)), `${path}: unexpected external URL ${url}`);
    }
  }
});

test('a trailing slash redirects to the clean URL, and unknown paths get the 404 page', async () => {
  const res = await get('/security/?x=1');
  assert.equal(res.status, 301);
  assert.equal(res.headers.get('location'), '/security?x=1');

  for (const path of ['/nope', '/security.html', '/index.html', '/how-it-works/extra', '/0123456789abcdef0123456789abcdef.txt']) {
    const miss = await get(path);
    assert.equal(miss.status, 404, path);
    assert.equal(miss.headers.get('content-type'), 'text/html; charset=utf-8', path);
    const html = await miss.text();
    assert.match(html, /<h1>404<\/h1>/, path);
    assert.match(html, /<meta name="robots" content="noindex">/, path);
    assert.ok(html.includes('href="/how-it-works"'), `${path}: 404 footer nav`);
  }
  assert.equal((await get('/security', { method: 'POST' })).status, 404);
});

test('HEAD on a page and an asset returns headers with no body', async () => {
  for (const path of ['/tor', '/og-image.png']) {
    const res = await get(path, { method: 'HEAD' });
    assert.equal(res.status, 200, path);
    assert.ok(Number(res.headers.get('content-length')) > 0, path);
    assert.equal((await res.arrayBuffer()).byteLength, 0, path);
  }
});

test('og-image.png is 1200x630 and under 300 KB; icons are served with their types', async () => {
  const png = async (path) => {
    const res = await get(path);
    assert.equal(res.status, 200, path);
    assert.equal(res.headers.get('content-type'), 'image/png', path);
    assert.equal(res.headers.get('cross-origin-resource-policy'), 'cross-origin', path);
    assert.match(res.headers.get('cache-control'), /max-age=\d+/, path);
    const buf = Buffer.from(await res.arrayBuffer());
    assert.equal(buf.subarray(1, 4).toString('latin1'), 'PNG', path);
    return { buf, width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  };
  const og = await png('/og-image.png');
  assert.deepEqual([og.width, og.height], [1200, 630]);
  assert.ok(og.buf.length < 300 * 1024, `og-image.png is ${og.buf.length} bytes`);
  const touch = await png('/apple-touch-icon.png');
  assert.deepEqual([touch.width, touch.height], [180, 180]);

  const svg = await get('/favicon.svg');
  assert.equal(svg.status, 200);
  assert.equal(svg.headers.get('content-type'), 'image/svg+xml');
  const body = await svg.text();
  assert.match(body, /^<svg /);
  assert.ok(!/<script|style=|<style|href=/.test(body), 'favicon.svg is plain shapes only');
});

test('robots.txt allows the site and disallows the API, webhook and health paths', async () => {
  const res = await get('/robots.txt');
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain; charset=utf-8');
  assert.deepEqual((await res.text()).trim().split('\n'), [
    'User-agent: *',
    'Allow: /',
    'Disallow: /api/',
    'Disallow: /webhook/',
    'Disallow: /health',
    'Sitemap: https://test.local/sitemap.xml',
  ]);
});

test('sitemap.xml lists every page with an ISO lastmod and no priority', async () => {
  const res = await get('/sitemap.xml');
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/xml; charset=utf-8');
  const xml = await res.text();
  assert.ok(!xml.includes('<priority>'));
  const entries = [...xml.matchAll(/<url><loc>([^<]+)<\/loc><lastmod>(\d{4}-\d{2}-\d{2})<\/lastmod><\/url>/g)];
  assert.deepEqual(entries.map((m) => m[1]), PAGES.map((p) => `https://test.local${p}`));
  assert.equal((xml.match(/<url>/g) || []).length, PAGES.length);
  for (const [, , lastmod] of entries) assert.ok(!Number.isNaN(Date.parse(lastmod)), lastmod);
});

test('the IndexNow key file is served at /<key>.txt and contains the key', async () => {
  const keys = readdirSync(PUBLIC).filter((f) => /^[a-f0-9]{32}\.txt$/.test(f));
  assert.equal(keys.length, 1, 'exactly one key file in public/');
  const key = keys[0].slice(0, -4);
  assert.equal(readFileSync(join(PUBLIC, keys[0]), 'utf8'), key);
  const res = await get(`/${key}.txt`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain; charset=utf-8');
  assert.equal(await res.text(), key);
});

test('/tor shows the onion address only when ONION_HOST is set, and pages send Onion-Location', async () => {
  const plain = await get('/tor');
  assert.equal(plain.headers.get('onion-location'), null);
  const plainHtml = await plain.text();
  assert.ok(!plainHtml.includes('.onion/'), 'no onion address without ONION_HOST');
  assert.ok(plainHtml.includes('does not publish an onion address'));

  // ONION_HOST requires the dedicated onion listener.
  const withOnion = await startServer({ ONION_HOST: ONION, ONION_PORT: String(await freePort()) });
  try {
    const res = await fetch(`http://127.0.0.1:${withOnion.port}/tor`);
    assert.equal(res.headers.get('onion-location'), `http://${ONION}/tor`);
    const html = await res.text();
    assert.ok(html.includes(`<code>http://${ONION}/</code>`));
    assert.ok(!html.includes('does not publish an onion address'));
    assert.ok(!/\{\{|<!--\/?onion:/.test(html));
    const home = await fetch(`http://127.0.0.1:${withOnion.port}/`);
    await home.arrayBuffer();
    assert.equal(home.headers.get('onion-location'), `http://${ONION}/`);
  } finally {
    await withOnion.stop();
  }
});
