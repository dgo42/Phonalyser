/*
 * Phonalyser web - THD-tab NumericStepField probe (#4): the four THD steppers are
 * real .numfield widgets wired through commitThdSettings. Asserts the UI path:
 * ▼ on "Max harmonic for THD" updates the THD tile chip (H9 -> H8); checking
 * "Enable distortion LP" enables its stepper (unchecked = greyed); the LP field
 * displays the auto-ranged unit (20000 Hz -> "20" + kHz).
 *
 * Run: node test/e2e/_thd-stepper-probe.mjs
 */
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.wasm': 'application/wasm', '.properties': 'text/plain' };
const server = http.createServer((req, res) => {
  let p = decodeURIComponent((req.url || '/').split('?')[0]); if (p === '/') p = '/index.html';
  const fp = normalize(join(WEB_ROOT, p));
  if (!fp.startsWith(WEB_ROOT) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()] || 'application/octet-stream') + '; charset=utf-8', 'Cache-Control': 'no-store' });
  createReadStream(fp).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 } });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 200)));
await page.goto(origin + '/index.html', { waitUntil: 'load' });
await page.waitForTimeout(1500);

let pass = 0, fail = 0;
const ok = (n, c) => { if (c) pass++; else { fail++; console.log('FAIL:', n); } };

const collapsed = await page.locator('#fftPane').evaluate((el) => el.classList.contains('collapsed'));
if (collapsed) { await page.click('#fftPane .pane-header'); await page.waitForTimeout(300); }
await page.click('#fftTabs .tab[data-panel="thdSettings"]');
await page.waitForTimeout(200);

// LP field: seeded 20000 Hz -> auto-ranged display "20" + kHz; disabled until its checkbox is on.
const lp = () => page.evaluate(() => {
  const input = document.querySelector('#thdDistMax');
  // The unit is part of the input text now (Java single-Text parity, no .nf-unit label).
  return { text: input.value.trim(), disabled: input.disabled };
});
let s = await lp();
ok('LP shows auto-ranged 20 kHz', s.text === '20 kHz');
ok('LP disabled while unchecked', s.disabled);
await page.check('#thdDistMaxEn');
await page.waitForTimeout(150);
s = await lp();
ok('LP enabled by its checkbox', !s.disabled);

// ▼ on "Max harmonic for THD" (9 -> 8) must flow through commitThdSettings -> the H chip.
const chip = () => page.locator('#thdTabSub').innerText();
ok('tile chip starts at H9', /H9/.test(await chip()));
await page.click('#thdMaxH ~ .nf-step button:last-child');
await page.waitForTimeout(150);
ok('▼ steps the chip to H8', /H8/.test(await chip()));
ok('field shows 8', (await page.inputValue('#thdMaxH')).trim() === '8');

// restore (the page persists prefs in localStorage)
await page.click('#thdMaxH ~ .nf-step button:first-child');
await page.uncheck('#thdDistMaxEn');
await page.waitForTimeout(150);

ok('no console/page errors', errs.length === 0);
if (errs.length) for (const e of errs.slice(0, 6)) console.log('  ERR:', e);
console.log(`\nthd-stepper: ${pass} passed, ${fail} failed`);
await browser.close(); server.close();
process.exit(fail === 0 ? 0 : 1);
