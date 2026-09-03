/*
 * Phonalyser web - FFT trace solid-fill probe (the striping regression).
 *
 * The user compared Java vs web: Java's noise band is a SOLID opaque fill
 * (ColumnBucketPainter pass 1 - AA-off integer native-pixel bars); the web
 * showed semi-transparent vertical stripes + a standout midline. Two causes,
 * fixed in fft-view.js: stroked (anti-aliased) bars, then CSS-px integer
 * fills landing on FRACTIONAL device pixels at fractional devicePixelRatio.
 *
 * This probe runs the REAL app at deviceScaleFactor 1.25 (the Windows 125 %
 * scaling case), records with the fake mic, reads the spectrum canvas's
 * DEVICE pixels, and measures: of the INTERIOR band pixels (blue-dominant
 * with blue-dominant pixels directly above AND below), what fraction is a
 * partial white-blend instead of the full trace color? Solid Java-style fill
 * -> near zero; the striped bug -> a large fraction (~half the band).
 *
 * Run: node test/e2e/_fft-solid-fill-probe.mjs   (exits non-zero on any FAIL)
 */
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html':'text/html','.mjs':'text/javascript','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.wasm':'application/wasm','.properties':'text/plain' };
function startServer() {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const fp = normalize(join(WEB_ROOT, p));
    if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()]||'application/octet-stream')+'; charset=utf-8', 'Cache-Control':'no-store' });
    createReadStream(fp).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, port: server.address().port })));
}

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const fakeWav = join(__dirname, 'fake-audio.wav');
makeFakeAudioWav(fakeWav);
const browser = await chromium.launch({ headless: true, args: ['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required',`--use-file-for-fake-audio-capture=${fakeWav}`] });
// deviceScaleFactor 1.25 == Windows 125 % display scaling: window.devicePixelRatio = 1.25,
// the exact fractional-dpr case that re-created the striping.
const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1400, height: 940 }, deviceScaleFactor: 1.25 });
await context.grantPermissions(['microphone'], { origin });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push('' + (e && e.stack || e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('.fft-pane .led-btn', { state: 'attached', timeout: 15000 });
await page.waitForTimeout(700);

// Start FFT recording at 16384 (≈6 bins/column at this width - every column multi-bin,
// so the envelope bars cover the whole band like the user's 2M case).
await page.evaluate(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const fft = document.querySelector('.fft-pane');
  if (fft.classList.contains('collapsed')) {
    fft.querySelector('.pane-header').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await sleep(400);
  }
  const fs = document.querySelector('#fftSize');
  if (fs) { fs.value = '16384'; fs.dispatchEvent(new Event('change', { bubbles: true })); await sleep(300); }
  fft.querySelector('.led-btn').click();
});
await page.waitForTimeout(1500);   // few averages only - the noise band is still TALL (more interior)

const m = await page.evaluate(() => {
  const cv = document.getElementById('spec');
  const g = cv.getContext('2d');
  const dpr = window.devicePixelRatio;
  const img = g.getImageData(0, 0, cv.width, cv.height);
  const { data, width, height } = img;
  // Trace color from the live pref (default #1a5fb4).
  const pane = window.__fftPane;
  const hex = pane && pane.prefs ? pane.prefs.fftLineColor.get() : 0x1a5fb4;
  const tr = (hex >> 16) & 0xff;
  // Plot interior only (skip the left margin + top toolbar strip + bottom axis), device px.
  const x0 = Math.ceil(80 * dpr), x1 = Math.floor((cv.width / dpr - 30) * dpr);
  const y0 = Math.ceil(60 * dpr), y1 = Math.floor((cv.height / dpr - 40) * dpr);
  const at = (x, y) => (y * width + x) * 4;
  const blueish = (i) => data[i + 2] > data[i] + 20 && data[i + 2] > data[i + 1] + 20;
  let interior = 0, blended = 0;
  for (let y = y0 + 2; y < y1 - 2; y++) {
    for (let x = x0; x < x1; x++) {
      const i = at(x, y);
      // BAND interior only: centre of a vertical blueish run ≥ 5 px. The AA'd midpoint
      // polyline alone (≤3 px tall at fractional dpr, Java-faithful) must not count -
      // the user's striping complaint is about the DENSE band, not the line trace.
      if (!blueish(i) || !blueish(at(x, y - 1)) || !blueish(at(x, y + 1))
        || !blueish(at(x, y - 2)) || !blueish(at(x, y + 2))) continue;
      interior++;
      // White->trace mix fraction off the red channel (white 255 -> trace tr):
      // a solid bar reads ≈1.0; the striped bug's half-alpha columns ≈0.5.
      const t = (255 - data[i]) / (255 - tr);
      if (t > 0.2 && t < 0.8) blended++;
    }
  }
  return { dpr, interior, blended, frac: interior ? blended / interior : 1 };
});

// Visual artifact for review: a crop of the noise band region.
const spec = await page.$('#spec');
if (spec) {
  const bb = await spec.boundingBox();
  await page.screenshot({ path: join(__dirname, '_fft-solid-fill-crop.png'),
    clip: { x: bb.x + 300, y: bb.y + 120, width: Math.min(700, bb.width - 340), height: Math.min(260, bb.height - 160) } });
}

console.log(`dpr=${m.dpr} interior band px=${m.interior} partially-blended=${m.blended} fraction=${(m.frac * 100).toFixed(1)}%`);
ok(m.dpr === 1.25, `probe runs at fractional devicePixelRatio (${m.dpr})`);
ok(m.interior > 5000, `noise band found (${m.interior} interior pixels)`);
// ~10% of interior pixels are legitimately anti-aliased: the STEEP segments of the
// pass-2 midpoint polyline inside the band (Java strokes the same AA'd path) and the
// band's ragged upper edge. The stroked-bar striping bug reads far above this (every
// bar pixel a half-blend); 15% separates them cleanly (measured 9.5% post-fix).
ok(m.frac < 0.15, `band is a SOLID fill: ${(m.frac * 100).toFixed(1)}% of interior pixels are partial blends (< 15%; the striped bug reads ~40%+)`);
ok(errors.length === 0, `no console/page errors (${errors.length})${errors.length ? ' :: ' + errors[0] : ''}`);

await browser.close();
server.close();
console.log(`\nfft-solid-fill: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
