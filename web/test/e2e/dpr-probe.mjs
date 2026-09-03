// One-off rendering probe: reproduce the user's "non-smooth trace without sin x/x"
// case - sin x/x OFF, a few samples per pixel - and screenshot it to confirm the
// lerpAt (linear-interp-per-column) port renders smooth, not stair-stepped. Also
// verifies DPR crispness. Not part of the suite.
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
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
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
await page.waitForTimeout(500);
await page.click('.scope-pane .led-btn');
await page.waitForTimeout(1200);

// DPR crispness
const m = await page.evaluate(() => { const c = document.getElementById('scope');
  return { dpr: devicePixelRatio, backW: c.width, expectW: Math.round(c.clientWidth*devicePixelRatio) }; });
console.log('DPR crisp?', m.backW === m.expectW, JSON.stringify(m));

// sin x/x OFF for both channels + a few samples/pixel (~3 ms/div @ 48 kHz ≈ 1.4 s/px window)
await page.click('#scopeTabs .tab[data-panel="scopeHoriz"]');
await page.waitForTimeout(150);
// Step t/div UP a few 1-2-5 notches (default 1 ms -> 2 -> 5 ms) so a few samples
// fall per pixel (lerpAt territory), and turn sin x/x OFF on both channels.
const up = page.locator('.numfield:has(#scopeTdiv) .nf-step button').first();
for (let k = 0; k < 2; k++) { await up.click(); await page.waitForTimeout(120); }
await page.evaluate(() => {
  for (const id of ['#scopeLeftSinc', '#scopeRightSinc']) {
    const el = document.querySelector(id);
    if (el && el.checked) { el.checked = false; el.dispatchEvent(new Event('change', { bubbles: true })); }
  }
});
await page.waitForTimeout(1200);
const tdivCheck = await page.evaluate(() => document.querySelector('#scopeTdiv')?.value);
console.log('t/div field value after stepping:', tdivCheck);

// Smoothness metric: trace the single yellow/cyan pixel per column, measure how
// many adjacent columns repeat the SAME y (stair-step runs) vs total columns.
const smooth = await page.evaluate(() => {
  const c = document.getElementById('scope'); const ctx = c.getContext('2d');
  const W = c.width, H = c.height; const img = ctx.getImageData(0,0,W,H).data;
  const colY = []; // mean y of trace pixels in each column
  for (let x = 0; x < W; x++) {
    let sy = 0, cnt = 0;
    for (let y = 0; y < H; y++) { const i=(y*W+x)*4; const r=img[i],g=img[i+1],b=img[i+2];
      const yellow = r>180&&g>160&&b<90, cyan = b>160&&g>160&&r<150;
      if (yellow||cyan) { sy+=y; cnt++; } }
    colY.push(cnt? sy/cnt : null);
  }
  const ys = colY.filter(v=>v!=null);
  let dupes=0, steps=0;
  for (let i=1;i<colY.length;i++){ if(colY[i]==null||colY[i-1]==null) continue; steps++; if(Math.round(colY[i])===Math.round(colY[i-1])) dupes++; }
  return { tracedCols: ys.length, steps, dupeFrac: steps? +(dupes/steps).toFixed(3):1 };
});
console.log('SIN-OFF few-samples/px trace:', JSON.stringify(smooth));
await page.locator('#scope').screenshot({ path: 'test/e2e/_trace-sinoff.png' });
console.log('screenshot -> test/e2e/_trace-sinoff.png');

await browser.close();
server.close();
