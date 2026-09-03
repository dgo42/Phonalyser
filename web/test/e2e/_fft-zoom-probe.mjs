/*
 * Phonalyser web - FFT vertical pan / zoom regression probe (#11 / #12 / #16).
 *
 * Instruments the live app (fake mic, 16384 FFT) and asserts, against the Java
 * baseline (FftView.onMouseWheel / panMagnitude / zoomMagnitudeAroundCursor,
 * FftPane.clampRangesAndSave with Constants.MAG_FLOOR_DBFS = -300):
 *   1. prefs fftMagTop/Bottom/FreqMin/MaxHz stay CONSTANT across 5+ average ticks
 *      with no user input (there is NO auto zoom in any direction - #12);
 *   2. plain wheel down AND up both PAN (top+bottom move together, span constant)
 *      - including repeated wheel-down towards the floor (#16: down must never
 *      turn into a bottom-pinned zoom) - and the panned window PERSISTS across
 *      the next 2 average ticks;
 *   3. Ctrl+wheel ZOOMS (span changes) and the zoom persists across 2 ticks.
 *
 * Reads the app's prefs through the window.__fftPane debug hook (fft-pane.js).
 * Run: node test/e2e/_fft-zoom-probe.mjs   (exits non-zero on any FAIL)
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
const near = (a, b, eps = 1e-9) => Math.abs(a - b) <= eps;

const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const fakeWav = join(__dirname, 'fake-audio.wav');
makeFakeAudioWav(fakeWav);
const browser = await chromium.launch({ headless: true, args: ['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required',`--use-file-for-fake-audio-capture=${fakeWav}`] });
const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1400, height: 940 } });
await context.grantPermissions(['microphone'], { origin });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push('' + (e && e.stack || e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('.fft-pane .led-btn', { state: 'attached', timeout: 15000 });
await page.waitForTimeout(700);

// ---- start FFT recording (16384 FFT for a fast average cadence) ----
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
  await sleep(300);
});
const rec = await page.evaluate(() => document.querySelector('.fft-pane .led-btn').classList.contains('rec'));
ok(rec, 'FFT recording started');

// Count result ticks by wrapping the pane's setResult (the avg label saturates at
// the configured averages count, so it can't be used as a tick detector).
await page.evaluate(() => {
  const pane = window.__fftPane;
  const orig = pane.setResult.bind(pane);
  window.__tickCount = 0;
  pane.setResult = (r) => { window.__tickCount++; orig(r); };
});

const snap = () => page.evaluate(() => {
  const p = window.__fftPane.prefs;
  return {
    top: p.fftMagTop.get(), bot: p.fftMagBottom.get(),
    fMin: p.fftFreqMinHz.get(), fMax: p.fftFreqMaxHz.get(),
    tick: window.__tickCount || 0,
  };
});

// Wait until the next analysis result lands (setResult tick), up to timeoutMs.
async function nextTick(timeoutMs = 8000) {
  const start = (await snap()).tick;
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    await page.waitForTimeout(120);
    const s = await snap();
    if (s.tick > start) return s;
  }
  return null;
}

ok(await nextTick(15000) !== null, 'first average tick arrived');

// ---- (1) no-touch drift: prefs constant across 5 ticks (#12 baseline) ----
const base = await snap();
console.log(`baseline: top=${base.top} bot=${base.bot} fMin=${base.fMin} fMax=${base.fMax} (tick ${base.tick})`);
let drift = null;
for (let i = 0; i < 5; i++) {
  const s = await nextTick();
  ok(s !== null, `tick ${i + 1} arrived`);
  if (!s) break;
  if (!(near(s.top, base.top) && near(s.bot, base.bot) && near(s.fMin, base.fMin) && near(s.fMax, base.fMax))) {
    drift = s;
    console.log(`  drift at tick ${i + 1}: top=${s.top} bot=${s.bot} fMin=${s.fMin} fMax=${s.fMax}`);
  }
}
ok(drift === null, 'no-touch: fftMagTop/Bottom + fftFreqMin/Max CONSTANT across 5 average ticks (#12)');

// ---- (2) plain wheel = vertical PAN, both directions, persists (#16/#12) ----
const spec = await page.locator('#spec').boundingBox();
const cx = spec.x + spec.width * 0.6, cy = spec.y + spec.height * 0.5;
await page.mouse.move(cx, cy);

async function wheel(deltaY, ctrl = false) {
  if (ctrl) await page.keyboard.down('Control');
  await page.mouse.wheel(0, deltaY);
  if (ctrl) await page.keyboard.up('Control');
  await page.waitForTimeout(120);
  return snap();
}

const before = await snap();
const span0 = before.top - before.bot;
const down1 = await wheel(+100);                       // wheel DOWN -> pan down
const dDown = down1.top - before.top;
console.log(`wheel down: top ${before.top} -> ${down1.top}, bot ${before.bot} -> ${down1.bot} (span ${span0} -> ${down1.top - down1.bot})`);
ok(dDown < 0, `wheel down PANS down (top moved ${dDown.toFixed(2)} dB)`);
ok(near(down1.bot - before.bot, dDown, 1e-6), 'wheel down: bottom moved by the SAME step (pan, not zoom)');
ok(near(down1.top - down1.bot, span0, 1e-6), 'wheel down: span constant');

const up1 = await wheel(-100);                          // wheel UP -> pan up
const dUp = up1.top - down1.top;
console.log(`wheel up:   top ${down1.top} -> ${up1.top}, bot ${down1.bot} -> ${up1.bot}`);
ok(dUp > 0, `wheel up PANS up (top moved +${dUp.toFixed(2)} dB)`);
ok(near(up1.bot - down1.bot, dUp, 1e-6), 'wheel up: bottom moved by the SAME step');
ok(near(up1.top - up1.bot, span0, 1e-6), 'wheel up: span constant');

// #16 stress: 14 wheel-downs must stay a PAN all the way to the -300 dBFS floor
// (Java clips the step at Constants.MAG_FLOOR_DBFS = -300; the span NEVER shrinks).
let spanBroken = null, prev = await snap();
for (let i = 0; i < 14; i++) {
  const s = await wheel(+100);
  const span = s.top - s.bot, prevSpan = prev.top - prev.bot;
  if (!near(span, prevSpan, 1e-6)) { spanBroken = { i, prev, s }; break; }
  prev = s;
}
if (spanBroken) {
  console.log(`  span broke at wheel-down #${spanBroken.i + 1}: [${spanBroken.prev.top}, ${spanBroken.prev.bot}] -> [${spanBroken.s.top}, ${spanBroken.s.bot}]`);
}
ok(spanBroken === null, 'repeated wheel-down stays a PAN (span constant to the -300 floor, #16)');
const floorSnap = await snap();
ok(floorSnap.bot >= -300 - 1e-6, `pan floor respected (bot=${floorSnap.bot} >= -300)`);

// pan persists across 2 average ticks (#12)
const panned = await snap();
const t1 = await nextTick(); const t2 = await nextTick();
ok(t1 && near(t1.top, panned.top) && near(t1.bot, panned.bot), 'pan PERSISTS across average tick 1 (#12)');
ok(t2 && near(t2.top, panned.top) && near(t2.bot, panned.bot), 'pan PERSISTS across average tick 2 (#12)');

// ---- (3) Ctrl+wheel = vertical ZOOM, persists ----
const zBefore = await snap();
const zSpan0 = zBefore.top - zBefore.bot;
const zIn = await wheel(-100, true);                    // Ctrl + wheel up -> zoom in (0.8×)
const zSpan1 = zIn.top - zIn.bot;
console.log(`ctrl+wheel up: span ${zSpan0} -> ${zSpan1}`);
ok(zSpan1 < zSpan0 - 1e-6, `Ctrl+wheel ZOOMS (span ${zSpan0.toFixed(2)} -> ${zSpan1.toFixed(2)})`);
ok(near(zSpan1, zSpan0 * 0.8, 1e-6), 'Ctrl+wheel-up zoom factor is 0.8 (Java zoomMagnitudeAroundCursor)');
const z1 = await nextTick(); const z2 = await nextTick();
ok(z1 && near(z1.top, zIn.top) && near(z1.bot, zIn.bot), 'zoom PERSISTS across average tick 1 (#12)');
ok(z2 && near(z2.top, zIn.top) && near(z2.bot, zIn.bot), 'zoom PERSISTS across average tick 2 (#12)');

// ---- (4) V unit: log-voltage axis endpoints derive EXACTLY as Java (#11) ----
// Java FftView.onPaint (:1197-1198): magBot/magTop = prefs.convertFromDbFs(fftMag*, unit)
// = 10^((dBFS + dbvOffsetDb)/20) for V (Preferences.java:1377); the axis is then
// AxisSpec.log(magBot, magTop) (:1220-1224) - pure label/mapping change, the dBFS
// range itself must NOT move on a unit switch (FftView.java:584-589).
const uBefore = await snap();
await page.evaluate(() => {
  const sel = document.querySelector('#fftMagUnit');
  sel.value = 'V'; sel.dispatchEvent(new Event('change', { bubbles: true }));
});
await page.waitForTimeout(300);
const uAfter = await snap();
ok(near(uAfter.top, uBefore.top) && near(uAfter.bot, uBefore.bot),
  'switching mag unit to V leaves the canonical dBFS range untouched (#11)');
const vAxis = await page.evaluate(() => {
  const p = window.__fftPane.prefs;
  return { off: p.dbvOffsetDb, topV: p.convertFromDbFs(p.fftMagTop.get(), 'V'), botV: p.convertFromDbFs(p.fftMagBottom.get(), 'V') };
});
const expTopV = Math.pow(10, (uAfter.top + vAxis.off) / 20), expBotV = Math.pow(10, (uAfter.bot + vAxis.off) / 20);
console.log(`V axis: off=${vAxis.off.toFixed(2)} dB, top=${vAxis.topV.toExponential(3)} V, bot=${vAxis.botV.toExponential(3)} V`);
ok(near(vAxis.topV, expTopV, 1e-12) && near(vAxis.botV, expBotV, 1e-12),
  'V-axis endpoints = 10^((dBFS + dbvOffset)/20) (Java Preferences.convertFromDbFs)');
await page.screenshot({ path: join(__dirname, '_fft-zoom-vunit.png'), clip: (await page.locator('#spec').boundingBox()) });

ok(errors.length === 0, `no console/page errors (${errors.length})${errors.length ? ' :: ' + errors[0] : ''}`);

await browser.close();
server.close();
console.log(`\nfft-zoom-probe: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
