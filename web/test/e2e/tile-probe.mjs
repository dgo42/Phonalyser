// Reproduce "tiles 22px in browser, 11px on screenshot" (dpr=2). Measures a tab tile
// in the LIVE view and in the screenshot CLONE (offscreen shot host), to locate where
// the in-flow elements shrink (clone layout vs raster).
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

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

const fakeWav = makeFakeAudioWav(join(__dirname, 'fake-audio.wav'));
const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ headless: true, args: [
  '--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',
  '--autoplay-policy=no-user-gesture-required', `--use-file-for-fake-audio-capture=${fakeWav}` ] });
const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1400, height: 800 }, deviceScaleFactor: 2 });
await context.grantPermissions(['microphone'], { origin });
const page = await context.newPage();
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });
await page.waitForTimeout(400);
await page.click('.scope-pane .led-btn');
await page.waitForTimeout(1200);

const live = await page.evaluate(() => {
  const t = document.querySelector('.scope-pane .tiletabs .tile, .scope-pane .t-sub .tile, .scope-pane .tile');
  const b = document.querySelector('.scope-pane .lr-tools .lr');
  return { dpr: window.devicePixelRatio,
    tile: t ? { h: t.getBoundingClientRect().height, font: getComputedStyle(t).fontSize, text: t.textContent } : null,
    button: b ? b.getBoundingClientRect().height : null };
});
console.log('LIVE:', JSON.stringify(live));

// Trigger the in-app screenshot at native size so the clone is built in the offscreen host.
await page.evaluate(() => { const ap = HTMLAnchorElement.prototype; window.__oc = ap.click; ap.click = function(){}; });
await page.click('#scopeTabs .tab[data-panel="scopeUtility"]');
await page.waitForTimeout(150);
await page.click('#scopeShot');
await page.waitForTimeout(150);
await page.click('#shotSave');
await page.waitForTimeout(800);

const clone = await page.evaluate(() => {
  // The clone lives in the offscreen host (position:fixed; left:-10000px).
  const host = Array.from(document.querySelectorAll('div')).find((d) => d.style && d.style.left === '-10000px');
  if (!host) return { hostFound: false };
  const t = host.querySelector('.tiletabs .tile, .t-sub .tile, .tile');
  const b = host.querySelector('.lr-tools .lr');
  const hostBox = host.getBoundingClientRect();
  return { hostFound: true, hostW: hostBox.width, hostH: hostBox.height,
    tile: t ? { h: t.getBoundingClientRect().height, font: getComputedStyle(t).fontSize, text: t.textContent } : null,
    button: b ? b.getBoundingClientRect().height : null };
});
console.log('CLONE:', JSON.stringify(clone));
if (live.tile && clone.tile) {
  const ratio = clone.tile.h / live.tile.h;
  console.log(`TILE ratio clone/live = ${ratio.toFixed(3)} (live=${live.tile.h.toFixed(1)}px clone=${clone.tile.h.toFixed(1)}px)`);
}
if (live.button && clone.button) {
  console.log(`BUTTON ratio clone/live = ${(clone.button/live.button).toFixed(3)} (live=${live.button.toFixed(1)} clone=${clone.button.toFixed(1)})`);
}
await browser.close(); server.close();
