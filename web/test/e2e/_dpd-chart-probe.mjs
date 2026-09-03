// Visual probe for the predistortion convergence chart port (retest #10). Temp (_-prefixed).
// Serves web/ over HTTP, opens the predistortion modal in headless Chromium, then drives a
// FRESH PredistortionWizard instance's _drawChart() against the live #dpdChart canvas with
// synthetic per-round history - verifying the HiDPI backing store (no CSS stretch) and the
// Java paintChart geometry (decade grid + 1e±NN labels, red dashed target, dark-blue trace).
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
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
// deviceScaleFactor 2 exercises the DPR backing-store path (the #27-class bug).
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 2, colorScheme: 'light' });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 200)));

await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(2000);

// Open the modal (bootstrap show - adds .dpd-resizable via the app's show hook).
await page.evaluate(() => window.bootstrap.Modal.getOrCreateInstance(document.getElementById('predistModal')).show());
await page.waitForTimeout(500);

const res = await page.evaluate(async () => {
  const mod = await import('/js/shell/predistortion-wizard.js');
  // Minimal stub deps - _drawChart touches only history/state/running/pdEngine/getResult/host.
  const w = new mod.PredistortionWizard({}, {}, {}, { modal: null, getResult: () => null, saveFile: null, onApply: null });
  const cv = document.getElementById('dpdChart');

  // 1) EMPTY: border + centered em-dash placeholder.
  w._drawChart();
  const dpr = window.devicePixelRatio || 1;
  const rect = cv.getBoundingClientRect();
  const emptyOk = cv.width === Math.round(rect.width * dpr) && cv.height === Math.round(rect.height * dpr);

  // 2) DATA: 5 rounds falling 0.05% -> 0.0003% with growing averages + a target.
  document.getElementById('dpdTarget').value = '0.0001';
  w.history.dist.push(0.05, 0.01, 0.002, 0.0007, 0.0003);
  w.history.avg.push(16, 16, 32, 64, 128);
  w._drawChart();

  // Pixel assertions on the backing store (device px).
  const g = cv.getContext('2d');
  const img = g.getImageData(0, 0, cv.width, cv.height);
  const count = (r, gg, b) => {
    let n = 0;
    for (let i = 0; i < img.data.length; i += 4) {
      if (img.data[i] === r && img.data[i + 1] === gg && img.data[i + 2] === b) n++;
    }
    return n;
  };
  return {
    emptyOk,
    cssW: Math.round(rect.width), cssH: Math.round(rect.height),
    backW: cv.width, backH: cv.height, dpr,
    bluePx: count(0, 0, 128),      // dark-blue trace + markers
    redPx: count(255, 0, 0),       // red dashed target line
    grayPx: count(192, 192, 192),  // gray grid + border
  };
});

await page.screenshot({ path: join(__dirname, '_dpd-chart-probe.png'), clip: await page.locator('#dpdChart').boundingBox() });
await browser.close();
server.close();

let fails = 0;
const ok = (cond, msg) => { console.log((cond ? 'ok: ' : 'FAIL: ') + msg); if (!cond) fails++; };
ok(res.emptyOk, `backing store = CSS px × dpr (${res.cssW}x${res.cssH} css @ dpr ${res.dpr} -> ${res.backW}x${res.backH})`);
ok(res.bluePx > 50, `dark-blue trace drawn (${res.bluePx} px of #000080)`);
ok(res.redPx > 20, `red dashed target line drawn (${res.redPx} px of #ff0000)`);
ok(res.grayPx > 200, `gray decade grid + border drawn (${res.grayPx} px of #c0c0c0)`);
ok(errs.length === 0, `no console/page errors (${errs.length})${errs.length ? ' :: ' + errs.join(' | ') : ''}`);
console.log(`dpd-chart-probe: ${fails === 0 ? 'PASS' : 'FAIL'}`);
process.exit(fails === 0 ? 0 : 1);
