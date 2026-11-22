// Renders the committed PNGs from their SVG sources:
//   assets/og-image.svg  -> public/og-image.png          (1200x630 social card)
//   public/favicon.svg   -> public/apple-touch-icon.png  (180x180)
// Run by hand after editing an SVG: node scripts/render-images.mjs
// Needs Playwright with Chromium (not a dependency of this project) and the
// JetBrains Mono font installed locally. Nothing is fetched from the network.
import { readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const { chromium } = createRequire(import.meta.url)('playwright');

const jobs = [
  { svg: 'assets/og-image.svg', png: 'public/og-image.png', width: 1200, height: 630 },
  { svg: 'public/favicon.svg', png: 'public/apple-touch-icon.png', width: 180, height: 180 },
];

const browser = await chromium.launch();
try {
  for (const { svg, png, width, height } of jobs) {
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    const data = readFileSync(join(root, svg)).toString('base64');
    await page.setContent(
      `<body style="margin:0;background:#0a0c0e"><img width="${width}" height="${height}" ` +
        `style="display:block" src="data:image/svg+xml;base64,${data}">`
    );
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: join(root, png), type: 'png' });
    await page.close();
    console.log(`${png}: ${width}x${height}, ${statSync(join(root, png)).size} bytes`);
  }
} finally {
  await browser.close();
}
