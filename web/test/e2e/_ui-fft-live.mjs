// Live-render probe for the THD table now painted ON the fft-view canvas (#spec).
// Loads the app, expands the FFT pane, starts an FFT recording against the Playwright
// fake-audio tone, lets it accumulate, then checks for render-path console errors and
// screenshots the FFT pane. The fft-pane render() wraps the view in try/catch and logs
// 'fft render error' on a throw, so any broken table-draw dependency surfaces here.
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.ico': 'image/x-icon', '.wasm': 'application/wasm', '.properties': 'text/plain' };

function startServer() {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const fp = normalize(join(WEB_ROOT, p));
    if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': MIME[extname(fp).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    createReadStream(fp).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, port: server.address().port })));
}

const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 }, deviceScaleFactor: 1 });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 300)));

await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(2000);

// Expand the FFT pane (default 3-pane shows it collapsed).
try {
  const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
  if (collapsed) { await page.click('#fftPane .pane-header'); await page.waitForTimeout(300); }
} catch (e) { errs.push('expand: ' + e.message.slice(0, 80)); }

// Start FFT recording against the fake-audio tone.
let recording = false;
try {
  await page.click('#fftPane .led-btn');
  await page.waitForTimeout(3500);   // let the cross-tick accumulator build a frame + paint the table
  recording = await page.locator('#fftPane .led-btn').evaluate((el) => el.classList.contains('rec'));
} catch (e) { errs.push('record: ' + e.message.slice(0, 80)); }

await page.screenshot({ path: join(__dirname, '_ui-fft-live.png'), clip: await page.locator('#fftPane').boundingBox() });

const renderErrs = errs.filter((e) => /fft render error|drawDistortion|drawImd|TypeError|undefined/i.test(e));
console.log('FFT live probe - recording:', recording);
console.log('total console errors:', errs.length, '| render-path errors:', renderErrs.length);
for (const e of errs.slice(0, 12)) console.log('  ERR:', e);
console.log(renderErrs.length === 0 ? 'PASS: no render-path errors' : 'FAIL: render-path errors present');
await browser.close();
server.close();
process.exit(renderErrs.length === 0 ? 0 : 1);
