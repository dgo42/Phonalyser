// CSS-phase probe (temp, _-prefixed): opens the scope/FFT/FR load+save panels, the FFT
// measurement float window, the THD tab, the cal rows and the predist modal; dumps computed
// border styles of the load-from/save-to icon buttons and takes DPR-2 crops for a visual check.
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

const { server, port } = await startServer();
const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
const page = await browser.newPage({ viewport: { width: 1680, height: 1000 }, deviceScaleFactor: 2 });
const errs = [];
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 200)));
await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(2500);

const styleOf = (sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return 'MISSING';
  const c = getComputedStyle(el);
  return `${c.borderTopWidth}/${c.borderRightWidth}/${c.borderBottomWidth}/${c.borderLeftWidth} ` +
    `top:${c.borderTopStyle} ${c.borderTopColor} bg:${c.backgroundColor} w:${el.offsetWidth} h:${el.offsetHeight}`;
}, sel);

const crop = async (sel, name, pad = 10) => {
  const el = page.locator(sel).first();
  const b = await el.boundingBox();
  if (!b) { console.log('NO BOX for', sel); return; }
  await page.screenshot({ path: join(__dirname, name), clip: {
    x: Math.max(0, b.x - pad), y: Math.max(0, b.y - pad),
    width: Math.min(1680 - Math.max(0, b.x - pad), b.width + 2 * pad),
    height: Math.min(1000 - Math.max(0, b.y - pad), b.height + 2 * pad) } });
  console.log('shot', name);
};

// generator rows (always visible)
await crop('#genFilePath', '_css-gen-load.png', 14);

// scope panels
await page.click('#scopeTabs .tab[data-panel="scopeSavePanel"]'); await page.waitForTimeout(250);
await crop('#scopeSavePanel', '_css-scope-save.png');
await page.click('#scopeTabs .tab[data-panel="scopeLoadPanel"]'); await page.waitForTimeout(250);
await crop('#scopeLoadPanel', '_css-scope-load.png');
console.log('scope save browse ', await styleOf('#scopeSaveBrowse'));
console.log('scope load label  ', await styleOf('label[for="scopeLoadGo"]'));
// close the scope panel again and COLLAPSE the scope pane so the FFT strip regains height
await page.click('#scopeTabs .tab[data-panel="scopeLoadPanel"]'); await page.waitForTimeout(250);
await page.click('#scopePane .pane-header, .scope-pane .pane-header'); await page.waitForTimeout(400);

// FFT panels
await page.click('#fftPane .tab[data-panel="fftSavePanel"]', { force: true }); await page.waitForTimeout(250);
await crop('#fftSavePanel', '_css-fft-save.png');
await page.click('#fftPane .tab[data-panel="fftLoadPanel"]', { force: true }); await page.waitForTimeout(250);
await crop('#fftLoadPanel', '_css-fft-load.png');
console.log('fft save btn      ', await styleOf('#fftSaveBtn'));
console.log('fft load btn      ', await styleOf('#fftLoadBtn'));

// FFT cal rows: add a 2nd row so the 8px gap + aligned button columns show
await page.click('#fftPane #fftLoadCal', { force: true }); await page.waitForTimeout(250);
await page.evaluate(() => document.querySelector('#fftCalRows .fcal-add')?.click());
await page.waitForTimeout(250);
await crop('#fftCalRows', '_css-fft-calrows.png');
console.log(await page.evaluate(() => {
  const rows = document.querySelectorAll('#fftCalRows .fft-cal-row');
  if (rows.length < 2) return 'only ' + rows.length + ' rows';
  const a = rows[0].getBoundingClientRect(), b = rows[1].getBoundingClientRect();
  return 'cal row gap px: ' + (b.top - a.bottom);
}));

// THD tab
await page.click('#fftPane .tab[data-panel="thdSettings"]', { force: true }); await page.waitForTimeout(250);
await crop('#thdSettings', '_css-fft-thd.png');
console.log(await page.evaluate(() => {
  const ids = ['thdDistMin', 'thdDistMax', 'fftManualFund', 'thdMaxH', 'thdCalcMaxH'];
  return ids.map((id) => { const f = document.getElementById(id)?.closest('.numfield');
    return `${id}: ${f ? f.offsetWidth + 'x' + f.offsetHeight : 'MISSING'}`; }).join('  ');
}));

// FFT measurement float window (force open; JS normally paints the canvas)
await page.evaluate(() => document.getElementById('fftMeasWindow')?.classList.add('open'));
await page.waitForTimeout(250);
await crop('#fftMeasWindow', '_css-fft-measwin.png', 18);
console.log('measwin', await styleOf('#fftMeasWindow'), '| body', await styleOf('#fftMeasWindow .fft-meas-body'));
await page.evaluate(() => document.getElementById('fftMeasWindow')?.classList.remove('open'));

// FR panels
await page.click('button[data-bs-target="#tab-fr"]'); await page.waitForTimeout(800);
await page.click('#frTabs .tab[data-panel="frSavePanel"]'); await page.waitForTimeout(250);
await crop('#frSavePanel', '_css-fr-save.png');
await page.click('#frTabs .tab[data-panel="frLoadPanel"]'); await page.waitForTimeout(250);
await crop('#frLoadPanel', '_css-fr-load.png');
console.log('fr save btn       ', await styleOf('#frSaveBtn'));
console.log('fr load btn       ', await styleOf('#frLoadBtn'));

// predist modal (back on the multi tab)
await page.click('button[data-bs-target="#tab-multi"]'); await page.waitForTimeout(400);
await page.evaluate(() => bootstrap.Modal.getOrCreateInstance(document.getElementById('predistModal')).show());
await page.waitForTimeout(600);
console.log('predist', await page.evaluate(() => {
  const c = document.querySelector('#predistModal .modal-content');
  const body = document.querySelector('#predistModal .modal-body');
  return `content ${c.offsetWidth}x${c.offsetHeight} bodyScroll=${body.scrollHeight} bodyClient=${body.clientHeight}` +
    ` scrolls=${body.scrollHeight > body.clientHeight}`;
}));
await page.screenshot({ path: join(__dirname, '_css-predist.png') });

console.log('console/page errors:', errs.length);
for (const e of errs.slice(0, 8)) console.log('  ERR:', e);
await browser.close();
server.close();
