// The page in a real headless Chromium, for the Android interop tests. Loaded
// by peer.mjs only when a test asks for a Chromium page, so the Node-only
// tests do not need Playwright. Playwright is not a dependency of this
// repository: it is used where the machine already has it.
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createRequire } from 'node:module';

let browser = null;

async function launch() {
  if (browser) return browser;
  const require = createRequire(import.meta.url);
  const { chromium } = require(process.env.DARKP2P_PLAYWRIGHT || 'playwright');
  browser = await chromium.launch();
  return browser;
}

export async function closeBrowser() {
  if (browser) await browser.close();
  browser = null;
}

// The same surface as the pages of test/helpers/page.mjs that peer.mjs uses.
export async function loadChromiumPage({ port, hash, forceWsRelay }) {
  const context = await (await launch()).newContext({ acceptDownloads: true });
  const page = await context.newPage();
  // Without a save dialog (as in Firefox) the page streams to its private
  // storage and hands the file over as a download. Nobody could answer a
  // native dialog in a headless browser.
  await page.addInitScript(() => { delete window.showSaveFilePicker; });
  const downloads = [];
  page.on('download', (d) => downloads.push(d.path().then((p) => readFileSync(p))));
  if (forceWsRelay) {
    // The page reads the box when its peer arrives; tick it as soon as it exists.
    await page.addInitScript(() => {
      document.addEventListener('DOMContentLoaded', () => { document.getElementById('forceWsRelay').checked = true; });
    });
  }
  await page.goto(`http://127.0.0.1:${port}/${hash}`);

  const logs = () => page.$$eval('#log > div', (els) => els.map((e) => ({ text: e.textContent.replace(/^> /, ''), cls: e.className })));
  const label = () => page.$eval('#dropLabel', (e) => e.textContent);
  const waitFor = async (pred, what, timeoutMs = 5000) => {
    const end = Date.now() + timeoutMs;
    for (;;) {
      if (await pred()) return;
      if (Date.now() > end) {
        throw new Error(`timed out waiting for ${what}\n  label: ${await label()}\n  log:\n    ${(await logs()).map((l) => l.text).join('\n    ')}`);
      }
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const button = (text) => page.locator('.offer button', { hasText: text }).first();

  return {
    waitForLog: (re, timeoutMs) => waitFor(async () => (await logs()).some((l) => re.test(l.text)), `log ${re}`, timeoutMs),
    waitForLabel: (re, timeoutMs) => waitFor(async () => re.test(await label()), `label ${re}`, timeoutMs),
    async state() {
      const all = await logs();
      return {
        label: await label(), mode: await page.$eval('#modeBadge', (e) => e.textContent), ip: await page.$eval('#ipBadge', (e) => e.textContent),
        dropEnabled: !(await page.$eval('#fileInput', (e) => e.disabled)),
        logs: all.map((l) => l.text), errors: all.filter((l) => l.cls === 'err').map((l) => l.text),
        sockets: -1, downloads: downloads.length,
      };
    },
    async send({ name, bytes, mime }) {
      // Playwright takes at most 50 MB as a buffer; larger files go through a temporary file.
      if (bytes.length <= 40 * 1024 * 1024) {
        await page.setInputFiles('#fileInput', { name, mimeType: mime, buffer: bytes });
        return;
      }
      const dir = mkdtempSync(join(tmpdir(), 'darkp2p-interop-'));
      try {
        writeFileSync(join(dir, name), bytes);
        await page.setInputFiles('#fileInput', join(dir, name));
      } finally {
        // The browser has the file open by now; the page reads it through that handle.
        setTimeout(() => rmSync(dir, { recursive: true, force: true }), 120_000).unref();
      }
    },
    async click(text, timeoutMs) {
      await button(text).click({ timeout: timeoutMs });
    },
    async download(index, timeoutMs) {
      await waitFor(async () => downloads.length > index, `download ${index}`, timeoutMs);
      const bytes = await downloads[index];
      return { size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
    },
    async setForceWsRelay(checked) {
      await page.setChecked('#forceWsRelay', checked);
    },
    async clickDropzone() {
      await page.click('#dropzone');
    },
    async close() {
      await context.close();
    },
  };
}
