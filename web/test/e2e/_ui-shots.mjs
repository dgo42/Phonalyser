// Capture screenshots of the rendered web UI for the Java-parity audit. Temp (_-prefixed).
// Serves web/ over HTTP, loads it in headless Chromium (fake audio device), screenshots each
// top-level tab + the FFT/scope/freqresp settings panels + the dialogs/wizards.
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

const shot = (page, name) => page.screenshot({ path: join(__dirname, name) });
const ok = [];
const fail = [];

// Run one shot guarded: `prep` does the clicks/setup, then screenshot `name`.
async function capture(page, name, prep) {
  try {
    await prep();
    await page.waitForTimeout(300);
    await shot(page, name);
    ok.push(name);
  } catch (e) {
    fail.push(name + ': ' + e.message.split('\n')[0].slice(0, 90));
  }
}

const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 }, deviceScaleFactor: 1 });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 200)));

await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(2500);

// 3-pane overview (gen + scope + fft)
await capture(page, '_ui-multi.png', async () => {});

// FFT pane EXPANDED: click the FFT collapser header. The default 3-pane layout shows the
// FFT pane already; clicking the header toggles its collapsed state, so click twice if
// needed to land on the expanded view. We force-expand by ensuring the body is visible.
await capture(page, '_ui-fft.png', async () => {
  // Collapse then expand to guarantee the expanded state is captured deterministically.
  const header = page.locator('#fftPane .pane-header');
  // If currently expanded, a click collapses; click again to re-expand. End expanded.
  await header.click();
  await page.waitForTimeout(200);
  const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
  if (collapsed) { await header.click(); await page.waitForTimeout(200); }
});

// FFT tile-tab panels (scope to #fftPane so the FreqResp .fft-pane is never matched)
await capture(page, '_ui-fft-settings.png', () => page.click('#fftPane .tab[data-panel="fftSettings"]'));
await capture(page, '_ui-fft-thd.png', () => page.click('#fftPane .tab[data-panel="thdSettings"]'));
await capture(page, '_ui-fft-cal.png', () => page.click('#fftPane #fftLoadCal'));

// Scope tile-tab panels
await capture(page, '_ui-scope-trig.png', () => page.click('#scopeTabs .tab[data-panel="scopeTrig"]'));
await capture(page, '_ui-scope-left.png', () => page.click('#scopeTabs .tab[data-panel="scopeLeft"]'));

// Frequency-Response tab + its panels
await capture(page, '_ui-freqresp.png', async () => {
  await page.click('button[data-bs-target="#tab-fr"]');
  await page.waitForTimeout(800);
});
await capture(page, '_ui-fr-riaa.png', () => page.click('#frTabs .tab[data-panel="frRiaaPanel"]'));
await capture(page, '_ui-fr-cal.png', () => page.click('#frTabs .tab[data-panel="frCalPanel"]'));
await capture(page, '_ui-fr-presets.png', () => page.click('#frTabs .tab[data-panel="frPresetsPanel"]'));

// Preferences dialog: open the Tools dropdown first, then the Preferences item.
await capture(page, '_ui-prefs.png', async () => {
  await page.click('button[data-bs-target="#tab-multi"]');
  await page.waitForTimeout(300);
  await page.click('.menu-item[data-i18n="menu.tools"]');   // open Tools dropdown
  await page.waitForTimeout(300);
  await page.click('#menuPrefs');
  await page.waitForTimeout(600);
});
// Dismiss the Preferences modal before opening the next one.
await page.keyboard.press('Escape').catch(() => {});
await page.waitForTimeout(400);

// DAC-calibration dialog (shown via Bootstrap modal API)
await capture(page, '_ui-daccal.png', async () => {
  await page.evaluate(() => bootstrap.Modal.getOrCreateInstance(document.getElementById('dacCalModal')).show());
  await page.waitForTimeout(500);
});
await page.evaluate(() => { const m = bootstrap.Modal.getInstance(document.getElementById('dacCalModal')); if (m) m.hide(); }).catch(() => {});
await page.waitForTimeout(400);

// Predistortion wizard
await capture(page, '_ui-predist.png', async () => {
  await page.evaluate(() => bootstrap.Modal.getOrCreateInstance(document.getElementById('predistModal')).show());
  await page.waitForTimeout(500);
});
await page.evaluate(() => { const m = bootstrap.Modal.getInstance(document.getElementById('predistModal')); if (m) m.hide(); }).catch(() => {});
await page.waitForTimeout(400);

// FreqResp calibration wizard (.fr-wizard)
await capture(page, '_ui-frwizard.png', async () => {
  await page.evaluate(() => bootstrap.Modal.getOrCreateInstance(document.getElementById('frWizardModal')).show());
  await page.waitForTimeout(500);
});

console.log('SHOTS OK (' + ok.length + '):');
for (const n of ok) console.log('  +', n);
if (fail.length) { console.log('SHOTS FAILED (' + fail.length + '):'); for (const f of fail) console.log('  -', f); }
console.log('console/page errors:', errs.length);
for (const e of errs.slice(0, 12)) console.log('  ERR:', e);
await browser.close();
server.close();
