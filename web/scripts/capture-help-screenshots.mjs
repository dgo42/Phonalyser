/*
 * Phonalyser web — capture help screenshots from the running WEB app.
 *
 * Drives the real app headless in a FIXED 1280×768 window (deviceScaleFactor 1 → exact
 * pixels, no dpr scaling) and writes web/help/<lang>/img/<file>.png for each spec, so the
 * help shows the WEB UI (which differs slightly from the desktop app's screenshots).
 *
 * Each spec carries a `ready` flag: specs whose web pane/dialog isn't built yet (FFT,
 * FreqResp, Preferences, generator sweep/dual-tone, the per-tab toolbars) are SKIPPED and
 * logged, so this can run today and fill in as parts 2–5 land — re-run per feature.
 *
 * Run:  node scripts/capture-help-screenshots.mjs                # every ready spec
 *       node scripts/capture-help-screenshots.mjs app scope      # only the given ids
 *       node scripts/capture-help-screenshots.mjs --list         # list specs + readiness
 * GNU AGPL v3 or later.
 */
import http from 'node:http';
import { createReadStream, existsSync, statSync, mkdirSync } from 'node:fs';
import { join, normalize, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const WEB = normalize(join(dirname(fileURLToPath(import.meta.url)), '..'));
const VIEW = { width: 1280, height: 768 };   // FIXED capture window (project requirement)
const LANG = 'en';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.wasm': 'application/wasm',
  '.properties': 'text/plain', '.woff': 'font/woff', '.woff2': 'font/woff2', '.wav': 'audio/wav', '.flac': 'audio/flac' };

function serve() {
  const s = http.createServer((req, res) => {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const fp = normalize(join(WEB, p));
    if (!fp.startsWith(WEB) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()] || 'application/octet-stream') + '; charset=utf-8', 'Cache-Control': 'no-store' });
    createReadStream(fp).pipe(res);
  });
  return new Promise((r) => s.listen(0, '127.0.0.1', () => r({ s, port: s.address().port })));
}

// id      → CLI selector. file → help/<lang>/img/<file>. ready → capturable now.
// shot(page) → optional async; returns an ElementHandle to clip, or null for the whole
// 1280×768 window. Specs without shot() default to a full-window capture.
const SPECS = [
  { id: 'app',        file: 'multifunctional.png',  ready: true },
  { id: 'scope',      file: 'oscilloscope-pane.png', ready: true,
    async shot(page) { return page.$('#scopePane'); } },
  { id: 'generator',  file: 'generator-pane.png',    ready: true,
    async shot(page) { return page.$('#genCol'); } },

  // ── below: pending parts 2–5; skipped until the web UI exists ──────────────────────
  { id: 'fft',            file: 'fft-pane.png',                  ready: false },  // part 4
  { id: 'freqresp',       file: 'freqresp-pane.png',             ready: false },  // part 5
  { id: 'gen-dualtone',   file: 'Generator - dual tone.png',     ready: false },  // part 3
  { id: 'gen-sweep',      file: 'Generator - sweep mode.png',    ready: false },  // part 3
  { id: 'prefs-lookfeel', file: 'Preferences Look and Feel.png', ready: false },  // part 2
  { id: 'prefs-audio',    file: 'Preferences Audio.png',         ready: false },  // part 2
  { id: 'prefs-scope',    file: 'Preferences Oscilloscope.png',  ready: false },  // part 2
  { id: 'prefs-fft',      file: 'Preferences FFT.png',           ready: false },  // part 2
  { id: 'prefs-freqresp', file: 'Preferences Frequency response.png', ready: false }, // part 2
  // (scope/FFT/FreqResp per-tab toolbar shots — Oscilloscope - Left.png, FFT - …, etc. —
  //  added as each toolbar lands; same shape: {id, file, ready, shot})
];

const argv = process.argv.slice(2);
if (argv.includes('--list')) {
  for (const s of SPECS) console.log(`${s.ready ? '✓' : '·'} ${s.id.padEnd(16)} ${s.file}`);
  process.exit(0);
}
const want = argv.filter((a) => !a.startsWith('--'));
const todo = SPECS.filter((s) => (want.length ? want.includes(s.id) : true));

const { s, port } = await serve();
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: VIEW, deviceScaleFactor: 1 });
const page = await ctx.newPage();
await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'networkidle' });
await page.waitForSelector('#scopePane', { timeout: 10000 }).catch(() => {});
await page.waitForTimeout(500);   // let layout/fonts settle

const imgDir = join(WEB, 'help', LANG, 'img');
mkdirSync(imgDir, { recursive: true });

let done = 0, skipped = 0;
for (const spec of todo) {
  if (!spec.ready) { console.log(`skip (web UI not ready): ${spec.id} → ${spec.file}`); skipped++; continue; }
  const out = join(imgDir, spec.file);
  const el = spec.shot ? await spec.shot(page) : null;
  if (el) await el.screenshot({ path: out });
  else await page.screenshot({ path: out });
  console.log(`captured: ${spec.id} → help/${LANG}/img/${spec.file}`);
  done++;
}

await browser.close();
s.close();
console.log(`\n${done} captured, ${skipped} skipped (pending parts 2–5).`);
