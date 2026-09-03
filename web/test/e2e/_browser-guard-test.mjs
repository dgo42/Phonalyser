// Verify the Chromium-engine gate: a simulated non-Chromium engine must show the blocking
// overlay and NOT initialise the app; real Chromium must run normally (no overlay).
// Run: node test/e2e/_browser-guard-test.mjs
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

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
const browser = await chromium.launch({ headless: true });
let ok = true;

// --- A) simulated NON-Chromium: strip the Chromium signals before any app code runs.
{
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    try { Object.defineProperty(navigator, 'userAgentData', { configurable: true, value: undefined }); } catch (_) {}
    try { delete window.MediaStreamTrackProcessor; } catch (_) {}
    if ('MediaStreamTrackProcessor' in window) { try { Object.defineProperty(window, 'MediaStreamTrackProcessor', { configurable: true, value: undefined }); } catch (_) {} }
  });
  await page.goto(origin + '/index.html', { waitUntil: 'load' });
  let shown = false;
  try { await page.waitForSelector('#unsupportedBrowser', { timeout: 8000 }); shown = true; } catch (_) {}
  const r = await page.evaluate(() => ({
    overlay: !!document.getElementById('unsupportedBrowser'),
    inSelOpts: document.querySelectorAll('#inSel option').length,   // app must NOT have initialised
    text: (document.querySelector('#unsupportedBrowser .ub-title') || {}).textContent || '',
  }));
  const pass = shown && r.overlay && r.inSelOpts === 0;
  console.log(`A) non-Chromium: overlay=${r.overlay} appInit(#inSel opts)=${r.inSelOpts} title="${r.text.trim()}" -> ${pass ? 'PASS' : 'FAIL'}`);
  ok = ok && pass;
  await ctx.close();
}

// --- B) real Chromium: app runs, no overlay.
{
  const ctx = await browser.newContext({ permissions: ['microphone'] });
  await ctx.grantPermissions(['microphone'], { origin });
  const page = await ctx.newPage();
  await page.goto(origin + '/index.html', { waitUntil: 'load' });
  await page.waitForTimeout(1500);
  const r = await page.evaluate(() => ({ overlay: !!document.getElementById('unsupportedBrowser'), hasSpec: !!document.getElementById('spec') }));
  const pass = !r.overlay && r.hasSpec;
  console.log(`B) Chromium: overlay=${r.overlay} appRan=${r.hasSpec} -> ${pass ? 'PASS' : 'FAIL'}`);
  ok = ok && pass;
  await ctx.close();
}

await browser.close();
server.close();
process.exit(ok ? 0 : 1);
