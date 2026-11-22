// Syntax-check every executable inline <script> in public/index.html.
// Selection rules mirror inlineScriptHashes() in server.js: blocks with a
// src= attribute and blocks whose type is not JavaScript are skipped.
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const file = join(root, 'public', 'index.html');
const html = readFileSync(file, 'utf8');

const blocks = [];
const re = /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi;
let match;
while ((match = re.exec(html)) !== null) {
  const attrs = match[1];
  if (/\bsrc\s*=/i.test(attrs)) continue;
  const type = /\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs);
  if (type && !/^(module|text\/javascript|application\/javascript)$/i.test(type[1])) continue;
  const line = html.slice(0, match.index).split('\n').length;
  blocks.push({ line, source: match[2].replace(/\r\n?/g, '\n') });
}

if (blocks.length === 0) {
  console.error('check-inline-js: no executable inline <script> found in public/index.html');
  process.exit(1);
}

const dir = mkdtempSync(join(tmpdir(), 'darkp2p-inline-js-'));
let failed = 0;
try {
  blocks.forEach((block, i) => {
    const out = join(dir, `inline-${i}.mjs`);
    writeFileSync(out, block.source);
    const r = spawnSync(process.execPath, ['--check', out], { encoding: 'utf8' });
    if (r.status !== 0) {
      failed += 1;
      console.error(`check-inline-js: <script> at public/index.html:${block.line} failed:`);
      console.error(r.stderr || r.stdout || `exit ${r.status}`);
    }
  });
} finally {
  rmSync(dir, { recursive: true, force: true });
}

if (failed) process.exit(1);
console.log(`check-inline-js: ${blocks.length} inline script block(s) OK`);
