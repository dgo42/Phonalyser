// Reproduce "scope EMPTY after load (no recording)": load the app, do NOT click
// record, and report console errors, the #scope canvas (blank?), the button count,
// and whether render() ran. Saves a screenshot.
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html':'text/html','.mjs':'text/javascript','.js':'text/javascript','.css':'text/css','.json':'application/json','.webmanifest':'application/manifest+json','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.wasm':'application/wasm','.properties':'text/plain' };
function startServer() {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent((req.url || '/').split('?')[0]); if (p === '/') p = '/index.html';
    const fp = normalize(join(WEB_ROOT, p));
    if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()]||'application/octet-stream')+'; charset=utf-8', 'Cache-Control':'no-store' });
    createReadStream(fp).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, port: server.address().port })));
}

const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
const page = await ctx.newPage();
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + (e && e.stack || e)));
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 }).catch(()=>{});
await page.waitForTimeout(1500);   // settle; do NOT click record

const info = await page.evaluate(() => {
  const c = document.getElementById('scope');
  const out = { hasCanvas: !!c };
  if (c) {
    const ctx = c.getContext('2d');
    const W = c.width, H = c.height;
    const img = ctx.getImageData(0, 0, W, H).data;
    const seen = new Set();
    for (let i = 0; i < img.length; i += 64) seen.add((img[i]<<16)|(img[i+1]<<8)|img[i+2]);
    out.canvas = { W, H, clientW: c.clientWidth, clientH: c.clientHeight, distinctColours: seen.size };
  }
  out.lrButtons = document.querySelectorAll('.scope-pane .lr-tools .lr').length;
  out.overlayLabels = document.querySelectorAll('.scope-pane .scope-overlay .scope-ovl-label, .scope-pane .scope-overlay > *').length;
  out.overlayPresent = !!document.querySelector('.scope-pane .scope-overlay');
  return out;
});
console.log('ERRORS:', errors.length ? JSON.stringify(errors.slice(0, 5), null, 1) : 'none');
console.log('INFO:', JSON.stringify(info));
console.log('blank canvas?', info.canvas ? (info.canvas.distinctColours <= 1) : 'no canvas',
            '(distinctColours=' + (info.canvas ? info.canvas.distinctColours : 'n/a') + ')');
await page.locator('.scope-pane').screenshot({ path: 'test/e2e/_noload.png' }).catch(()=>{});
await browser.close(); server.close();
