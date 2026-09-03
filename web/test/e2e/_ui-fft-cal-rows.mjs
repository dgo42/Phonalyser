// Verifies the FFT multi-row calibration cascade UI: add appends a row (visible Remove),
// row 0 hides Remove, remove deletes a row. DOM-level (no .frc file needed).
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
const server = http.createServer((req, res) => {
  let p = decodeURIComponent((req.url || '/').split('?')[0]); if (p === '/') p = '/index.html';
  const fp = normalize(join(WEB_ROOT, p));
  if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(fp).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-store' });
  createReadStream(fp).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 200)));
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(1500);

let pass = 0, fail = 0;
const ok = (n, c) => { if (c) pass++; else { fail++; console.log('FAIL:', n); } };
const rowCount = () => page.locator('#fftCalRows .fft-cal-row').count();
const removeVisible = (i) => page.locator('#fftCalRows .fft-cal-row').nth(i).locator('.fcal-remove').evaluate((el) => getComputedStyle(el).visibility !== 'hidden');

// Expand the FFT pane (collapsed in the default 3-pane layout) before touching its tabs.
const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
if (collapsed) { await page.click('#fftPane .pane-header'); await page.waitForTimeout(300); }
await page.click('#fftLoadCal');                       // open the Calibration panel
await page.waitForTimeout(200);
ok('starts with 1 row', await rowCount() === 1);
ok('row 0 Remove hidden', !(await removeVisible(0)));
await page.click('#fftCalRows .fft-cal-row:first-child .fcal-add');
await page.waitForTimeout(150);
ok('add -> 2 rows', await rowCount() === 2);
ok('row 1 Remove visible', await removeVisible(1));
await page.click('#fftCalRows .fft-cal-row:nth-child(2) .fcal-remove');
await page.waitForTimeout(150);
ok('remove -> 1 row', await rowCount() === 1);
ok('no console errors', errs.length === 0);
if (errs.length) for (const e of errs.slice(0, 6)) console.log('  ERR:', e);

console.log(`\nfft-cal-rows: ${pass} passed, ${fail} failed`);
await browser.close(); server.close();
process.exit(fail === 0 ? 0 : 1);
