// One-off: reproduce the FFT record path the user takes - expand the FFT pane via
// its header, then click the record LED. Run: node test/e2e/_fft-inspect.mjs
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
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('.fft-pane .led-btn', { state: 'attached', timeout: 15000 });
await page.waitForTimeout(700);

const out = await page.evaluate(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const log = {};
  const fft = document.querySelector('.fft-pane');
  log.fftCollapsedDefault = fft.classList.contains('collapsed');
  // REAL expand: the header listens on mousedown.
  const hdr = fft.querySelector('.pane-header');
  hdr.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  await sleep(500);
  log.fftCollapsedAfterHeaderClick = fft.classList.contains('collapsed');
  const led = fft.querySelector('.led-btn');
  const b = led.getBoundingClientRect();
  log.ledRect = { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) };
  // What element is actually at the LED's centre (is it covered)?
  if (b.width > 0) {
    const el = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2);
    log.elementAtLedCentre = el ? (el.className || el.tagName) : null;
    log.elementIsLed = el === led || (el && led.contains(el));
  }
  // REAL click on the LED.
  log.recBefore = led.classList.contains('rec');
  led.click();
  await sleep(1200);
  log.recAfter = led.classList.contains('rec');
  return log;
});
console.log('RESULT:', JSON.stringify(out, null, 2));
console.log('pageerrors:', errors.length ? errors.join(' | ') : 'none');
await browser.close();
server.close();
