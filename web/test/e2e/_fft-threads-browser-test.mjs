/*
 * Phonalyser web - threads>1 FFT path in a real browser (nested worker pool).
 *
 * Regression test for "Using more than one thread for FFT makes entire UI almost
 * unresponsive": the old code coordinated the pool ON the main thread (and with
 * frameCount < W ran the whole prelude+accumulate+finalize there, every tick).
 * Now fft-worker.js owns the pool; the main thread posts one message per tick.
 *
 * Asserts with threads=4: recording runs, results arrive (avg count climbs),
 * the main thread's event loop stays free (max blocking gap sane), and no
 * console/page errors (worker + nested pool spawn cleanly from dist-sibling URLs).
 *
 * Run: node test/e2e/_fft-threads-browser-test.mjs   (exits non-zero on any FAIL)
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
const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1400, height: 940 } });
await context.grantPermissions(['microphone'], { origin });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push('' + (e && e.stack || e)));
// The resource URL rides along: a "Failed to load resource" line names nothing on its own. The
// app's server-detection probe (GET /info, servedByServer) is EXPECTED to 404 on this static
// host - that answer is how the page learns it is not served by a Phonalyser server.
page.on('console', (m) => {
  if (m.type() !== 'error') return;
  const url = (m.location() || {}).url || '?';
  if (/\/info$/.test(url)) return;
  errors.push(`${m.text()} @ ${url}`);
});
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('.fft-pane .led-btn', { state: 'attached', timeout: 15000 });
await page.waitForTimeout(700);

const out = await page.evaluate(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const log = {};
  // Expand the FFT pane (header listens on mousedown).
  const fft = document.querySelector('.fft-pane');
  if (fft.classList.contains('collapsed')) {
    fft.querySelector('.pane-header').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await sleep(400);
  }
  // Small deterministic FFT length so the window fills fast on the 48 k fake mic.
  const fs = document.querySelector('#fftSize');
  log.fftSizeBefore = fs ? fs.value : null;
  if (fs) { fs.value = '16384'; fs.dispatchEvent(new Event('change', { bubbles: true })); await sleep(300); }
  // threads = 4 BEFORE starting (the change handler re-reads config / restarts).
  const th = document.querySelector('#threads');
  log.threadsOptions = th ? th.options.length : 0;
  if (th) { th.value = '4'; th.dispatchEvent(new Event('change', { bubbles: true })); await sleep(300); }
  log.threadsSet = th ? th.value : null;

  const led = fft.querySelector('.led-btn');
  led.click();
  await sleep(500);
  log.rec = led.classList.contains('rec');

  // Sample main-thread event-loop gaps for ~4 s of live threads=4 analysis: a
  // 25 ms heartbeat records how late it fires. Main-thread coordination would
  // show as long gaps; the worker path leaves the loop free.
  let maxGap = 0, last = performance.now();
  const timer = setInterval(() => { const now = performance.now(); const gap = now - last - 25; if (gap > maxGap) maxGap = gap; last = now; }, 25);
  await sleep(4000);
  clearInterval(timer);
  log.maxGapMs = Math.round(maxGap);

  log.avgText = (document.querySelector('#avgLbl') || {}).textContent || '';
  led.click();   // stop
  await sleep(200);
  return log;
});

console.log(`threads options=${out.threadsOptions} set=${out.threadsSet} rec=${out.rec} avg="${out.avgText}" maxGap=${out.maxGapMs}ms`);
ok(out.threadsSet === '4', `threads select set to 4 (got ${out.threadsSet})`);
ok(out.rec === true, 'FFT recording started (LED rec)');
ok(/\d/.test(out.avgText) && !/^0\b/.test(out.avgText.trim()), `results arrived with threads=4 - avg label "${out.avgText}"`);
ok(out.maxGapMs < 500, `main thread stayed responsive: max event-loop gap ${out.maxGapMs} ms < 500 ms`);
ok(errors.length === 0, `no console/page errors (${errors.length})${errors.length ? ' :: ' + errors[0] : ''}`);

await browser.close();
server.close();
console.log(`\nfft-threads-browser: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
