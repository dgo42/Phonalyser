// B21 - the Preferences ▸ Audio toolbar on ONE line. Temp probe (_-prefixed, like the other
// layout probes in this tree): serves web/, opens the Preferences dialog at its real 640px width
// with the WIDEST case forced (the per-backend "Custom preferences" button visible - the QA40x
// case of the maintainer's screenshot), measures where the four controls actually sit, and saves
// the proof shot.
//
// Run:  node test/e2e/_b21-toolbar-probe.mjs        (from web/)
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const TMP = normalize(join(WEB_ROOT, '..', 'tmp'));
const MIME = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.wasm': 'application/wasm',
  '.yaml': 'text/yaml', '.properties': 'text/plain' };

function startServer() {
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const fp = normalize(join(WEB_ROOT, p));
    if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': MIME[extname(fp).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-store' });
    createReadStream(fp).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, port: server.address().port })));
}

/** Where the four controls sit, and whether their row overflows. ONE LINE is judged on each
 *  control's vertical CENTRE against the row's own: the select is a few px shorter than the
 *  buttons, so equal top edges would be the wrong test. */
const measure = () => {
  const ids = ['scan', 'netServers', 'backendSettings', 'backendSel'];
  const row = document.querySelector('.prefs-panel[data-prefs-panel="audio"] .scan-devices');
  const rowBox = row.getBoundingClientRect();
  const middle = rowBox.top + rowBox.height / 2;
  const boxes = {};
  let worst = 0;
  for (const id of ids) {
    const el = document.getElementById(id);
    const b = el.getBoundingClientRect();
    boxes[id] = { left: Math.round(b.left), right: Math.round(b.right), width: Math.round(b.width),
      text: (el.textContent || '').trim().slice(0, 28) };
    worst = Math.max(worst, Math.abs((b.top + b.height / 2) - middle));
  }
  return { boxes, oneLine: worst < 6, offCentreBy: Math.round(worst),
    rowClientWidth: row.clientWidth, rowScrollWidth: row.scrollWidth,
    rowHeight: Math.round(rowBox.height) };
};

/** The combo as a CONNECTED bench fills it: the backend row a server contributes carries the
 *  server's name, and that option is what makes the group too wide to share the line. */
const withBenchOption = () => {
  const sel = document.getElementById('backendSel');
  if (![...sel.options].some((o) => o.value === 'net:WASAPI')) {
    const option = document.createElement('option');
    option.value = 'net:WASAPI';
    option.textContent = 'Phonalyser-Bench-Workstation -> WASAPI';
    sel.appendChild(option);
  }
  sel.value = 'net:WASAPI';
};

const { server, port } = await startServer();
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, deviceScaleFactor: 2 });
await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(2500);

async function openPrefs() {
  await page.evaluate(() => window.bootstrap.Modal.getOrCreateInstance(document.getElementById('prefsModal')).show());
  await page.waitForSelector('#prefsModal.show', { timeout: 5000 });
  await page.waitForTimeout(1200);
  // The WIDEST case: refreshCustomPrefsButton reveals this button only for a backend whose
  // manager has custom preferences (the QA40x), and headless Chromium has no WebUSB to find one
  // - so the button is unhidden here. Nothing else about the row differs in that case.
  await page.evaluate(() => document.getElementById('backendSettings').classList.remove('d-none'));
  await page.waitForTimeout(300);
}

async function shot(name) {
  await page.locator('#prefsModal .modal-dialog').screenshot({ path: join(TMP, name) });
  return join(TMP, name);
}

await openPrefs();
console.log('EN, local backends only', JSON.stringify(await page.evaluate(measure), null, 1));

// The maintainer's case: a server is connected, so the combo carries its backend row.
await page.evaluate(withBenchOption);
await page.waitForTimeout(300);
const en = await page.evaluate(measure);
console.log('EN, bench connected', JSON.stringify(en, null, 1));
console.log('shot ->', await shot('b21-toolbar-inline.png'));

// The longest button texts in the bundle - the case that decides whether the row still fits.
await page.evaluate(async () => {
  const i18n = await import('/js/i18n/i18n.js');
  await i18n.setLocale('de');
  document.querySelectorAll('[data-i18n]').forEach((el) => {
    el.textContent = i18n.t(el.getAttribute('data-i18n')).replace(/&/g, '').replace(/\.\.\.$/, '...');
  });
});
await page.waitForTimeout(400);
await page.evaluate(withBenchOption);
await page.waitForTimeout(200);
const de = await page.evaluate(measure);
console.log('DE, bench connected', JSON.stringify(de, null, 1));
console.log('shot ->', await shot('b21-toolbar-inline-de.png'));

await browser.close();
server.close();
