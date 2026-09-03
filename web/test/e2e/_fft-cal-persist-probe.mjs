/*
 * Phonalyser web - FFT calibration-row persistence probe (#5).
 *
 * Reproduces the user's "after app reload all gone": loads a fake .frc through the
 * SAME code path the real load handler uses (the .fcal-load click -> io.openFile ->
 * a stubbed window.showOpenFilePicker returning a File with .frc text), toggles
 * Active, then RELOADS the page and asserts the row is restored with its path,
 * Active state, and the applied cascade (cal tab chip).
 *
 * Run: node test/e2e/_fft-cal-persist-probe.mjs   (exits non-zero on any FAIL)
 */
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
const server = http.createServer((req, res) => {
  let p = decodeURIComponent((req.url || '/').split('?')[0]); if (p === '/') p = '/index.html';
  const fp = normalize(join(WEB_ROOT, p));
  if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()] || 'application/octet-stream') + '; charset=utf-8', 'Cache-Control': 'no-store' });
  createReadStream(fp).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;

// A REALISTIC .frc (frc.js loadFrc: '#' comments + 5-column CSV data rows): a
// Nyquist/2 sweep at 192 kHz ≈ 96 000 points -> ~4 MB of text. This is the size
// class the user actually loads - it exposed the localStorage quota failure
// (a small 3-row file fit and hid the bug). FRC_ROWS=3 runs the small variant.
const N_ROWS = parseInt(process.env.FRC_ROWS || '96000', 10);
const frcRows = ['# kind=filter_calibration', '# format_version=1',
  'freq_hz,mag_left_db,mag_right_db,phase_left_deg,phase_right_deg'];
for (let i = 1; i <= N_ROWS; i++) {
  frcRows.push(`${(i * 96000 / N_ROWS).toFixed(6)},-1.000000,-1.000000,0.0000,0.0000`);
}
const FRC_TEXT = frcRows.join('\n');
console.log(`probe .frc: ${N_ROWS} rows, ${(FRC_TEXT.length / 1048576).toFixed(2)} MB`);

const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
const context = await browser.newContext({ viewport: { width: 1680, height: 1000 } });
// Stub the FS-Access open picker BEFORE any module evaluates (file-picker.js samples
// window.showOpenFilePicker at import time) so .fcal-load runs the real handler.
await context.addInitScript(`
  window.showOpenFilePicker = async () => [{
    getFile: async () => new File([${JSON.stringify(FRC_TEXT)}], 'probe.frc', { type: 'text/plain' }),
  }];
`);
const page = await context.newPage();
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 300)));

let pass = 0, fail = 0;
const ok = (n, c) => { if (c) { pass++; console.log('PASS:', n); } else { fail++; console.log('FAIL:', n); } };

const openCalPanel = async () => {
  const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
  if (collapsed) { await page.click('#fftPane .pane-header'); await page.waitForTimeout(300); }
  await page.click('#fftLoadCal');
  await page.waitForTimeout(200);
};
const rowState = () => page.evaluate(() => {
  const row = document.querySelector('#fftCalRows .fft-cal-row');
  return row ? {
    path: row.querySelector('.fcal-path').value,
    active: row.querySelector('.fcal-active').checked,
    activeEnabled: !row.querySelector('.fcal-active').disabled,
    noise: row.querySelector('.fcal-noise').checked,
  } : null;
});
// The cal rows persist through the IndexedDB half of file-store.js (#5 fix - the
// old localStorage store silently dropped multi-MB .frc texts on quota).
const storedRec = () => page.evaluate(() => new Promise((resolve) => {
  const open = indexedDB.open('phonalyser.filestore', 1);
  open.onupgradeneeded = () => open.result.createObjectStore('files');
  open.onerror = () => resolve(null);
  open.onsuccess = () => {
    const db = open.result;
    const req = db.transaction('files', 'readonly').objectStore('files').get('fftcal.0');
    req.onerror = () => { db.close(); resolve(null); };
    req.onsuccess = () => {
      const rec = req.result;
      db.close();
      resolve(rec == null ? null : { name: rec.name, data: JSON.parse(rec.data) });
    };
  };
}));

await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(1500);
await openCalPanel();

// ---- load the fake .frc through the REAL handler ----
await page.click('#fftCalRows .fft-cal-row:first-child .fcal-load');
await page.waitForTimeout(600);
let st = await rowState();
ok('row 0 shows probe.frc after load', st && st.path === 'probe.frc');
ok('Active enabled after load', st && st.activeEnabled);
let rec = await storedRec();
ok('fftcal.0 persisted after load', rec != null && rec.name === 'probe.frc' && typeof rec.data.frc === 'string');

// ---- toggle Active, expect the persisted state to follow ----
await page.check('#fftCalRows .fft-cal-row:first-child .fcal-active');
await page.waitForTimeout(200);
rec = await storedRec();
ok('fftcal.0 records active=true', rec != null && rec.data.active === true);
const chipBefore = await page.locator('#calTabSub').innerText().catch(() => '');
ok('cal tab chip shows a loaded file', /\S/.test(chipBefore));

// ---- RELOAD: the row must come back with path + Active + applied cascade ----
await page.reload({ waitUntil: 'load' });
await page.waitForTimeout(1500);
await openCalPanel();
st = await rowState();
console.log('after reload rowState =', JSON.stringify(st));
console.log('after reload stored  =', JSON.stringify(await storedRec()));
ok('row restored with probe.frc after reload', st && st.path === 'probe.frc');
ok('Active restored checked after reload', st && st.active === true);
ok('Active enabled after reload', st && st.activeEnabled);
const chipAfter = await page.locator('#calTabSub').innerText().catch(() => '');
ok('cal tab chip restored (cascade reapplied)', /\S/.test(chipAfter));

ok('no console/page errors', errs.length === 0);
if (errs.length) for (const e of errs.slice(0, 8)) console.log('  ERR:', e);

console.log(`\nfft-cal-persist: ${pass} passed, ${fail} failed`);
await browser.close(); server.close();
process.exit(fail === 0 ? 0 : 1);
