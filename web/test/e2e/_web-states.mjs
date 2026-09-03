// Capture web-app screenshots for EACH state matching a Java help screenshot. Temp (_-prefixed).
// Modeled on _ui-shots.mjs: serves web/ over HTTP, loads it in headless Chromium (fake audio
// device), drives the UI to each state and screenshots it. Writes _web-*.png into this dir.
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

async function capture(page, name, prep) {
  try {
    await prep();
    await page.waitForTimeout(300);
    await shot(page, name);
    ok.push(name);
  } catch (e) {
    fail.push(name + ': ' + e.message.split('\n')[0].slice(0, 100));
  }
}

// Switch the generator signal form by driving the hidden #signalForm select (the custom
// combo syncs from it). Set value, fire native change (structural restart + row show/hide).
async function setSignalForm(page, formValue) {
  await page.evaluate((form) => {
    const sel = document.getElementById('signalForm');
    sel.value = form;
    sel.dispatchEvent(new Event('change'));
  }, formValue);
  await page.waitForTimeout(500);
}

const { server, port } = await startServer();
const origin = `http://127.0.0.1:${port}`;
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 }, deviceScaleFactor: 1, colorScheme: 'light' });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 200)));

await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(2500);

// ---- full app (multifunctional overview) ----
await capture(page, '_web-multifunctional.png', async () => {});

// ---- generator pane, default Sine ----
await capture(page, '_web-generator-pane.png', async () => { await setSignalForm(page, 'SINE'); });

// ---- generator: dual-tone form ----
await capture(page, '_web-gen-dualtone.png', async () => { await setSignalForm(page, 'DUAL_TONE'); });

// ---- generator: log-sweep form ----
await capture(page, '_web-gen-sweep.png', async () => { await setSignalForm(page, 'LOG_SWEEP'); });

// restore Sine before the pane/tab captures
await setSignalForm(page, 'SINE');

// ---- oscilloscope pane (overview shows it already) ----
await capture(page, '_web-oscilloscope-pane.png', async () => {});

// ---- each scope tile-tab ----
const scopeTabs = [
  ['scopeLeft', '_web-scope-left.png'],
  ['scopeRight', '_web-scope-right.png'],
  ['scopeHoriz', '_web-scope-horizontal.png'],
  ['scopeTrig', '_web-scope-trigger.png'],
  ['scopePresets', '_web-scope-presets.png'],
  ['scopeUtility', '_web-scope-utility.png'],
  ['scopeSavePanel', '_web-scope-saveto.png'],
  ['scopeLoadPanel', '_web-scope-loadsignal.png'],
];
for (const [panel, file] of scopeTabs) {
  await capture(page, file, () => page.click(`#scopeTabs .tab[data-panel="${panel}"]`));
}

// ---- FFT pane expanded ----
await capture(page, '_web-fft-pane.png', async () => {
  const header = page.locator('#fftPane .pane-header');
  await header.click();
  await page.waitForTimeout(200);
  const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
  if (collapsed) { await header.click(); await page.waitForTimeout(200); }
});

// ---- each FFT tile-tab ----
const fftTabs = [
  ['fftSettings', '_web-fft-settings.png'],
  ['thdSettings', '_web-fft-thd.png'],
  ['fftPresetsPanel', '_web-fft-presets.png'],
  ['fftUtility', '_web-fft-utility.png'],
  ['fftCalPanel', '_web-fft-loadcalibration.png'],
  ['fftSavePanel', '_web-fft-saveto.png'],
  ['fftLoadPanel', '_web-fft-loadfrom.png'],
];
for (const [panel, file] of fftTabs) {
  await capture(page, file, () => page.click(`#fftPane .tab[data-panel="${panel}"]`));
}

// ---- frequency-response tab pane ----
await capture(page, '_web-freqresp-pane.png', async () => {
  await page.click('button[data-bs-target="#tab-fr"]');
  await page.waitForTimeout(800);
});

// ---- each freqresp tile-tab ----
const frTabs = [
  ['frSettings', '_web-fr-settings.png'],
  ['frRiaaPanel', '_web-fr-riaaiec.png'],
  ['frPresetsPanel', '_web-fr-presets.png'],
  ['frUtility', '_web-fr-utility.png'],
  ['frCalPanel', '_web-fr-loadcalibration.png'],
  ['frSavePanel', '_web-fr-saveto.png'],
  ['frLoadPanel', '_web-fr-loadfrom.png'],
];
for (const [panel, file] of frTabs) {
  await capture(page, file, () => page.click(`#frTabs .tab[data-panel="${panel}"]`));
}

// ---- predistortion wizard open ----
await capture(page, '_web-predist.png', async () => {
  await page.click('button[data-bs-target="#tab-multi"]');
  await page.waitForTimeout(400);
  await page.evaluate(() => bootstrap.Modal.getOrCreateInstance(document.getElementById('predistModal')).show());
  await page.waitForTimeout(500);
});

console.log('SHOTS OK (' + ok.length + '):');
for (const n of ok) console.log('  +', n);
if (fail.length) { console.log('SHOTS FAILED (' + fail.length + '):'); for (const f of fail) console.log('  -', f); }
console.log('console/page errors:', errs.length);
for (const e of errs.slice(0, 20)) console.log('  ERR:', e);
await browser.close();
server.close();
