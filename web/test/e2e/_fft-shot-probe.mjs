// #28 verification (extended for the retest "screenshot is compressed/distorted"):
//  1. The FFT Utility camera (#fftShot) OPENS the shared screenshot dialog (it used to
//     immediately download a raw PNG), and addPane did not break the scope camera.
//  2. RENDER FIDELITY: a requested 1900x520 FFT screenshot is EXACTLY 1900x520 (IHDR
//     read straight off the PNG bytes), its spectrum plot is non-empty, and the text
//     BAKED INTO the #spec canvas (the y-axis tick labels) has the SAME pixel metrics
//     as the live canvas within ~3% - proving the spec canvas was RE-RENDERED from
//     scratch at the target resolution (fft-tab-control renderSpecShotCanvas - the web
//     mirror of Java AbstractPane.renderOffscreen + FftPane.createSnapshotClone), NOT
//     a scaled copy of the live bitmap (which squashed/stretched the glyphs).
// Runs at deviceScaleFactor 2 so the HiDPI backing path is exercised (the re-render
// applies the same dpr backing as the live paint - fft-view #27).
// Self-contained static server + headless Chromium. Temp (_-prefixed).
import http from 'node:http';
import { createReadStream, existsSync, statSync, writeFileSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const OUT_PNG = join(__dirname, '_fft-shot-proof.png');
const MIME = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.wasm': 'application/wasm', '.properties': 'text/plain', '.webmanifest': 'application/manifest+json' };

const TARGET_W = 1900, TARGET_H = 520;
const TOL = 0.03;   // ~3% tolerance for the glyph-metric match (mirrors shot-proof.mjs)

const server = http.createServer((req, res) => {
  let p = normalize(join(WEB_ROOT, decodeURIComponent(req.url.split('?')[0])));
  if (!p.startsWith(WEB_ROOT)) { res.writeHead(403); return res.end(); }
  if (existsSync(p) && statSync(p).isDirectory()) p = join(p, 'index.html');
  if (!existsSync(p)) { res.writeHead(404); return res.end('nf'); }
  res.writeHead(200, { 'content-type': MIME[extname(p)] || 'application/octet-stream' });
  createReadStream(p).pipe(res);
});
await new Promise((r) => server.listen(0, r));
const port = server.address().port;

// Independent PNG size read off the file bytes (IHDR: width bytes 16..19, height
// 20..23, big-endian) - proves the produced file IS the requested size (shot-proof.mjs).
function pngSize(buf) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  for (let i = 0; i < 8; i++) if (buf[i] !== sig[i]) return { ok: false };
  return { ok: true, w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

// Fake-audio capture file + mic permission (shot-proof.mjs pattern) so the FFT
// recording really accumulates frames - the shot must carry an analyzed spectrum.
const fakeWav = makeFakeAudioWav(join(__dirname, 'fake-audio.wav'));
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
  '--autoplay-policy=no-user-gesture-required', `--use-file-for-fake-audio-capture=${fakeWav}`] });
const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1680, height: 1000 }, deviceScaleFactor: 2, colorScheme: 'light' });
await context.grantPermissions(['microphone'], { origin: `http://127.0.0.1:${port}` });
const page = await context.newPage();
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 150)); });
page.on('pageerror', (e) => errs.push('PE ' + e.message.slice(0, 150)));
await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(2500);

let pass = 0, fail = 0;
const check = (n, c, d) => { console.log((c ? 'OK   ' : 'FAIL ') + n + (d ? ' - ' + d : '')); c ? pass++ : fail++; };
const clickOpens = (id) => page.evaluate((bid) => {
  const m = document.getElementById('shotModal');
  document.getElementById(bid).click();
  return new Promise((r) => setTimeout(() => r(m.classList.contains('show')), 450));
}, id);
const hideModal = () => page.evaluate(() => {
  const inst = window.bootstrap && window.bootstrap.Modal.getInstance(document.getElementById('shotModal'));
  if (inst) inst.hide();
}).then(() => page.waitForTimeout(450));

// ---- 1. Dialog wiring (the original #28 checks). ----
check('#fftShot OPENS the screenshot dialog (no longer an immediate save)', await clickOpens('fftShot'));
await hideModal();
check('#scopeShot still opens the dialog (addPane did not break the scope)', await clickOpens('scopeShot'));
await hideModal();

// ---- 2. Render fidelity at an exact 1900x520 target. ----
// Expand the FFT pane and record against the fake-audio tone so #spec carries a real
// spectrum + tick labels + readout table.
const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
if (collapsed) { await page.click('#fftPane .pane-header'); await page.waitForTimeout(300); }
await page.click('#fftPane .led-btn');
// Wait until real frames accumulate (#avgLbl leaves "0 average(s)") - the capture must
// show an analyzed spectrum, not the empty graticule.
let avgTxt = '';
try {
  await page.waitForFunction(() => { const t = document.getElementById('avgLbl'); return t && !/^0\s/.test(t.textContent); }, { timeout: 20000 });
} catch (e) { /* falls through to the check below */ }
avgTxt = await page.evaluate(() => (document.getElementById('avgLbl') || {}).textContent || '');
check('FFT recording accumulates frames (#avgLbl > 0)', !/^0\s/.test(avgTxt), `avgLbl="${avgTxt}"`);
await page.waitForTimeout(1500);

// Shared in-page glyph-band metric: within a pixel region, find the background (the
// dominant quantized colour), then group ink rows into text BANDS and report each
// band's pixel height + width. Applied to the LIVE #spec left margin (the y-axis tick
// labels, which the view bakes into the bitmap) and later to the SAME margin region of
// the produced image - a re-rendered shot reproduces the glyphs at IDENTICAL pixel
// size (same 11px font x same dpr), a scaled blit squashes/stretches them.
await page.evaluate(() => {
  window.__measureBands = (data, W, x0, x1, y0, y1) => {
    const q = (r, g, b) => ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const counts = new Map();
    for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
      const i = (y * W + x) * 4; const k = q(data[i], data[i + 1], data[i + 2]);
      counts.set(k, (counts.get(k) || 0) + 1);
    }
    let bg = 0, bc = -1; for (const [k, c] of counts) if (c > bc) { bc = c; bg = k; }
    const bgr = ((bg >> 8) & 15) * 17, bgg = ((bg >> 4) & 15) * 17, bgb = (bg & 15) * 17;
    const ink = (x, y) => { const i = (y * W + x) * 4;
      return Math.abs(data[i] - bgr) + Math.abs(data[i + 1] - bgg) + Math.abs(data[i + 2] - bgb) > 150; };
    const bands = [];
    let top = -1, minx = 1e9, maxx = -1;
    for (let y = y0; y <= y1; y++) {
      let n = 0, rMin = -1, rMax = -1;
      if (y < y1) for (let x = x0; x < x1; x++) if (ink(x, y)) { n++; if (rMin < 0) rMin = x; rMax = x; }
      if (n >= 2) { if (top < 0) { top = y; minx = 1e9; maxx = -1; } minx = Math.min(minx, rMin); maxx = Math.max(maxx, rMax); }
      else if (top >= 0) { bands.push({ h: y - top, w: maxx - minx + 1 }); top = -1; }
    }
    return bands;
  };
});

// LIVE #spec margin bands (device px). Scan the vertical middle (0.30..0.70 of the
// plot) so the L/R toolbar chips at the top and the x-axis strip stay out of the scan.
const live = await page.evaluate(() => {
  const c = document.getElementById('spec');
  const dpr = window.devicePixelRatio || 1;
  const W = c.width, H = c.height;
  const data = c.getContext('2d').getImageData(0, 0, W, H).data;
  const bands = window.__measureBands(data, W, 0, Math.round(62 * dpr), Math.round(H * 0.30), Math.round(H * 0.70));
  return { W, H, dpr, bands };
});
check('live #spec carries y-axis tick-label bands in its left margin', live.bands.length >= 2,
  `backing=${live.W}x${live.H} dpr=${live.dpr} bands=${JSON.stringify(live.bands)}`);

// Intercept toBlob so the produced image bytes are captured (shot-proof.mjs pattern),
// and stub the save picker + anchor download.
await page.evaluate(() => {
  window.__shot = null;
  const orig = HTMLCanvasElement.prototype.toBlob;
  HTMLCanvasElement.prototype.toBlob = function (cb, type, quality) {
    const w = this.width, h = this.height;
    return orig.call(this, (blob) => {
      if (blob) {
        const fr = new FileReader();
        fr.onload = () => { window.__shot = { w, h, dataUrl: fr.result, size: blob.size }; };
        fr.readAsDataURL(blob);
      }
      cb(blob);
    }, type, quality);
  };
  window.showSaveFilePicker = async ({ suggestedName } = {}) => ({
    name: suggestedName || 'fft.png',
    createWritable: async () => ({ write: async () => {}, close: async () => {} }),
  });
  const ap = HTMLAnchorElement.prototype; ap.click = function () {};
});

// Open the FFT screenshot dialog, force the exact target (keep-aspect off), save.
check('#fftShot re-opens the dialog for the sized capture', await clickOpens('fftShot'));
await page.evaluate(({ w, h }) => {
  const k = document.getElementById('shotKeepAspect');
  if (k && k.checked) { k.checked = false; k.dispatchEvent(new Event('change', { bubbles: true })); }
  const set = (id, v) => { const el = document.getElementById(id); el.value = String(v); el.dispatchEvent(new Event('input', { bubbles: true })); };
  set('shotWidth', w); set('shotHeight', h);
  document.getElementById('shotSave').click();
}, { w: TARGET_W, h: TARGET_H });
await page.waitForFunction(() => window.__shot && window.__shot.dataUrl, { timeout: 15000 });

// (2a) The produced PNG file is EXACTLY the requested size - read the IHDR bytes.
const dataUrl = await page.evaluate(() => window.__shot.dataUrl);
const buf = Buffer.from(dataUrl.split(',')[1], 'base64');
writeFileSync(OUT_PNG, buf);
console.log('saved produced image -> ' + OUT_PNG);
const size = pngSize(buf);
check('(2a) produced PNG is EXACTLY the requested 1900x520 (IHDR bytes)',
  size.ok && size.w === TARGET_W && size.h === TARGET_H,
  `IHDR=${size.w}x${size.h} requested=${TARGET_W}x${TARGET_H} bytes=${buf.length}`);

// (2b/2c) Decode the produced image IN-PAGE, measure the same left-margin glyph bands
// and the plot content. The re-rendered spec canvas sits at the pane's left edge, so
// the margin scan window is the same 62-CSS-px strip x the chrome scale (dpr).
const shot = await page.evaluate(async () => {
  const res = await fetch(window.__shot.dataUrl);
  const bmp = await createImageBitmap(await res.blob());
  const W = bmp.width, H = bmp.height;
  const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  const ctx = cv.getContext('2d'); ctx.drawImage(bmp, 0, 0);
  const data = ctx.getImageData(0, 0, W, H).data;
  const dpr = window.devicePixelRatio || 1;
  const bands = window.__measureBands(data, W, 0, Math.round(62 * dpr), Math.round(H * 0.30), Math.round(H * 0.70));
  // Plot content: ink pixels in the central plot region (trace + grid over the chart
  // background) - proves the spectrum was rendered into the shot at all.
  let inkC = 0;
  const cx0 = Math.round(W * 0.2), cx1 = Math.round(W * 0.8), cy0 = Math.round(H * 0.10), cy1 = Math.round(H * 0.55);
  // background = the dominant colour of the central region
  const q = (r, g, b) => ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
  const counts = new Map();
  for (let y = cy0; y < cy1; y += 2) for (let x = cx0; x < cx1; x += 2) {
    const i = (y * W + x) * 4; const k = q(data[i], data[i + 1], data[i + 2]);
    counts.set(k, (counts.get(k) || 0) + 1);
  }
  let bg = 0, bc = -1; for (const [k, c] of counts) if (c > bc) { bc = c; bg = k; }
  const bgr = ((bg >> 8) & 15) * 17, bgg = ((bg >> 4) & 15) * 17, bgb = (bg & 15) * 17;
  for (let y = cy0; y < cy1; y++) for (let x = cx0; x < cx1; x++) {
    const i = (y * W + x) * 4;
    if (Math.abs(data[i] - bgr) + Math.abs(data[i + 1] - bgg) + Math.abs(data[i + 2] - bgb) > 150) inkC++;
  }
  return { W, H, dpr, bands, inkC };
});
check('(2b) shot spectrum plot is non-empty (trace/grid ink in the central region)', shot.inkC > 500,
  `inkPx=${shot.inkC}`);
check('shot left margin carries tick-label bands', shot.bands.length >= 2, `bands=${JSON.stringify(shot.bands)}`);

// (2c) Glyph metrics: the median band HEIGHT (digit glyph height - invariant to which
// tick values land in the scan window) must match live within 3% (or 1.5px, the
// discrete anti-aliased band-edge jitter); and at least one shot band's (h, w) pair
// must match a live band's within the same tolerance (the tick-label sets of the same
// magnitude range overlap, so identical label texts exist in both).
const median = (a) => { const s = a.slice().sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; };
const mLive = median(live.bands.map((b) => b.h));
const mShot = median(shot.bands.map((b) => b.h));
const hErr = mLive > 0 ? Math.abs(mShot - mLive) / mLive : 1;
check('(2c) shot tick-label glyph HEIGHT == live (re-rendered, not squashed; within 3% / 1.5px)',
  mLive > 0 && (hErr <= TOL || Math.abs(mShot - mLive) <= 1.5),
  `liveMedianH=${mLive}px shotMedianH=${mShot}px err=${(hErr * 100).toFixed(2)}%`);
let best = null;
for (const s of shot.bands) for (const l of live.bands) {
  const eh = Math.abs(s.h - l.h), ew = Math.abs(s.w - l.w);
  const rel = Math.max(l.h > 0 ? eh / l.h : 1, l.w > 0 ? ew / l.w : 1);
  if (!best || rel < best.rel) best = { rel, eh, ew, s, l };
}
check('(2c) some shot band matches a live band in BOTH h and w (not stretched; within 3% / 2px)',
  !!best && (best.rel <= TOL || (best.eh <= 1.5 && best.ew <= 2)),
  best ? `best pair shot=${JSON.stringify(best.s)} live=${JSON.stringify(best.l)} rel=${(best.rel * 100).toFixed(2)}%` : 'no bands');

check('no console/page errors', errs.length === 0, errs.join(' | '));

console.log(`fft-shot-probe: ${pass} passed, ${fail} failed`);
await browser.close();
server.close();
process.exit(fail ? 1 : 0);
