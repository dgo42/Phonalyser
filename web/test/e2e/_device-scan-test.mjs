// Verify the improved input-device probe: each listed input must carry a DETERMINED native
// rate (frame-verified) and #inRate must default to it. Run: node test/e2e/_device-scan-test.mjs
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html':'text/html','.mjs':'text/javascript','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.properties':'text/plain' };
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
const context = await browser.newContext({ permissions: ['microphone'] });
await context.grantPermissions(['microphone'], { origin });
const page = await context.newPage();
page.on('pageerror', (e) => console.log('PAGEERROR', e.message));
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForSelector('#inSel', { state: 'attached', timeout: 15000 });
// Trigger a scan (covers both auto-scan-on-load and explicit Scan button).
await page.evaluate(() => { const b = document.getElementById('scan'); if (b) b.click(); });
await page.waitForFunction(() => document.querySelectorAll('#inSel option').length > 0, { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1500);

const r = await page.evaluate(() => {
  const opts = [...document.querySelectorAll('#inSel option')].map((o) => ({ text: o.textContent, rate: o.getAttribute('data-rate') }));
  const inRateOpts = [...document.querySelectorAll('#inRate option')].map((o) => o.value);
  const outRateOpts = [...document.querySelectorAll('#outRate option')].map((o) => o.value);
  return { inputCount: opts.length, opts, inRate: document.getElementById('inRate').value,
    inRateOptionCount: inRateOpts.length, inRateOpts,
    outHasLow: ['8000', '11025', '22050'].every((x) => outRateOpts.includes(x)) };
});
console.log('RESULT:', JSON.stringify(r, null, 2));
const allDetermined = r.opts.length > 0 && r.opts.every((o) => parseInt(o.rate, 10) > 0 && /-\s*\d+\s*Hz/.test(o.text));
const inRateOnlyNative = r.inRateOptionCount === 1 && parseInt(r.inRate, 10) > 0;   // ONLY the native rate
console.log(allDetermined && inRateOnlyNative && r.outHasLow
  ? `PASS - ${r.inputCount} determined input(s), #inRate has ONLY ${r.inRate} (1 option), #outRate has low rates`
  : `FAIL - determined=${allDetermined} inRateOnlyNative=${inRateOnlyNative}(count=${r.inRateOptionCount}) outHasLow=${r.outHasLow}`);
await browser.close();
server.close();
process.exit(allDetermined && inRateOnlyNative && r.outHasLow ? 0 : 1);
