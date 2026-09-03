// Measures the natural (non-scrolling) height of the predistortion modal content at the
// new 600px default width - feeds the exact numbers to the CSS phase (retest #10a). Temp.
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
const page = await browser.newPage({ viewport: { width: 1680, height: 1200 }, deviceScaleFactor: 1, colorScheme: 'light' });
await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(2000);
await page.evaluate(() => window.bootstrap.Modal.getOrCreateInstance(document.getElementById('predistModal')).show());
await page.waitForTimeout(500);
const m = await page.evaluate(() => {
  // Populate the live blocks the way the 100 ms poll does (3 FFT-info lines +
  // 9 metric lines in the 2-col grid) so the measured height is the LIVE height.
  document.getElementById('dpdFftInfo').innerHTML = 'FFT size: 65536 (0.7324 Hz/bin)<br>Sample rate: 48000 Hz<br>Window: HFT248D';
  document.getElementById('dpdMetrics').innerHTML = Array.from({ length: 9 },
    (_, i) => `<div class="dpd-line">Metric ${i}:  0.00000000 %</div>`).join('');
  const content = document.querySelector('#predistModal .modal-content');
  content.style.width = '600px';
  content.style.height = 'auto';
  content.style.maxHeight = 'none';
  const body = content.querySelector('.modal-body');
  const r = content.getBoundingClientRect();
  return { contentH: Math.ceil(r.height), bodyScrollH: body.scrollHeight, bodyClientH: body.clientHeight };
});
console.log(JSON.stringify(m));
await browser.close();
server.close();
