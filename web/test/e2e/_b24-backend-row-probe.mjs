// B24 - the Preferences ▸ Audio top row in the DESKTOP's order:
//   [Scan devices] [Servers...] Backend: [Custom preferences, only when shown] [combo, rest width]
// Temp probe (_-prefixed, like _b21/_b22b23): serves web/, opens the dialog at its 640 minimum,
// measures the row in both cases (settings button hidden / shown), re-checks B21's one-line
// property with a long server name in the combo, and shoots the enlarged dialog (B23) to show the
// combo absorbing the gained width.
//
// Run:  node test/e2e/_b24-backend-row-probe.mjs        (from web/)
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

/** The row, left to right: what is shown, where each control starts and ends (relative to the
 *  row's own content box), whether it is one line, and whether the combo reaches the right inset. */
const measureRow = () => {
  const row = document.querySelector('.prefs-panel[data-prefs-panel="audio"] .scan-devices');
  const rowBox = row.getBoundingClientRect();
  const style = getComputedStyle(row);
  const contentLeft = rowBox.left + parseFloat(style.paddingLeft);
  const contentRight = rowBox.right - parseFloat(style.paddingRight);
  const middle = rowBox.top + rowBox.height / 2;
  const items = [];
  let worst = 0;
  for (const el of row.children) {
    if (getComputedStyle(el).display === 'none') continue;
    const b = el.getBoundingClientRect();
    items.push({ what: el.id || (el.textContent || '').trim().slice(0, 12),
      left: Math.round(b.left - contentLeft), right: Math.round(b.right - contentLeft),
      width: Math.round(b.width) });
    worst = Math.max(worst, Math.abs((b.top + b.height / 2) - middle));
  }
  const combo = document.getElementById('backendSel').getBoundingClientRect();
  return { order: items.map((i) => i.what), items, oneLine: worst < 6,
    rowHeight: Math.round(rowBox.height),
    comboEndsAtInset: Math.round(contentRight - combo.right),
    rowWidth: Math.round(contentRight - contentLeft) };
};

/** The combo as a CONNECTED bench fills it - the option text that used to decide its width. */
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
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 2 });
await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(2500);
await page.evaluate(() => window.bootstrap.Modal.getOrCreateInstance(document.getElementById('prefsModal')).show());
await page.waitForSelector('#prefsModal.show', { timeout: 5000 });
await page.waitForTimeout(1200);

const shot = async (name) => {
  await page.locator('#prefsModal .modal-dialog').screenshot({ path: join(TMP, name) });
  return join(TMP, name);
};

// (a) a backend with no custom preferences - the WASAPI shot: label then combo, full width.
console.log('=== 640 minimum, settings button HIDDEN ===');
console.log(JSON.stringify(await page.evaluate(measureRow), null, 1));
console.log('shot ->', await shot('b24-row-plain.png'));

// (b) the QA40x case: refreshCustomPrefsButton reveals the middle cell (headless Chromium has no
// WebUSB to find a real analyzer, so the button is un-hidden here - nothing else differs).
await page.evaluate(() => document.getElementById('backendSettings').classList.remove('d-none'));
await page.waitForTimeout(300);
console.log('\n=== 640 minimum, settings button SHOWN (QA40x) ===');
console.log(JSON.stringify(await page.evaluate(measureRow), null, 1));
console.log('shot ->', await shot('b24-row-qa40x.png'));

// B21 still holds: a long server name no longer decides anything - the combo owns the slack.
await page.evaluate(withBenchOption);
await page.waitForTimeout(300);
console.log('\n=== ...with a connected bench in the combo (B21 re-check) ===');
console.log(JSON.stringify(await page.evaluate(measureRow), null, 1));
console.log('shot ->', await shot('b24-row-longname.png'));

// B23: the gained width goes to the combo.
const corner = await page.evaluate(() => {
  const b = document.querySelector('#prefsModal .modal-content').getBoundingClientRect();
  return { x: b.right - 4, y: b.bottom - 4 };
});
await page.mouse.move(corner.x, corner.y);
await page.mouse.down();
await page.mouse.move(corner.x + 220, corner.y + 120, { steps: 12 });
await page.mouse.up();
await page.waitForTimeout(400);
console.log('\n=== after enlarging the dialog by 220 px ===');
console.log(JSON.stringify(await page.evaluate(measureRow), null, 1));
console.log('shot ->', await shot('b24-row-enlarged.png'));

await browser.close();
server.close();
