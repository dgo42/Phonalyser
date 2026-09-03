// B22 (fields left-indented like the desktop) + B23 (the dialog is resizable, 640×480 its
// minimum). Temp probe, sibling of _b21-toolbar-probe.mjs: serves web/, opens Preferences, walks
// all five tabs at the minimum size, then drags the resize corner and checks the tab area really
// takes the space - with the shots the maintainer asked for.
//
// Run:  node test/e2e/_b22b23-prefs-probe.mjs        (from web/)
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
const TABS = ['audio', 'lookAndFeel', 'oscilloscope', 'fft', 'freqResp'];

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

/** The dialog's box, the tab area's, and whether the shown panel has to scroll. */
const geometry = () => {
  const content = document.querySelector('#prefsModal .modal-content');
  const body = document.querySelector('#prefsModal .modal-body');
  const panel = [...document.querySelectorAll('#prefsModal .prefs-panel')]
    .find((p) => !p.classList.contains('d-none'));
  const box = (el) => { const b = el.getBoundingClientRect(); return { w: Math.round(b.width), h: Math.round(b.height) }; };
  const style = getComputedStyle(content);
  return { content: box(content), body: box(body), panel: box(panel),
    panelScrolls: panel.scrollHeight > panel.clientHeight + 1,
    panelScrollHeight: panel.scrollHeight,
    resize: style.resize, minWidth: style.minWidth, minHeight: style.minHeight };
};

/** Where the label column ends and the value column starts, per row, on the shown panel. */
const columns = () => {
  const panel = [...document.querySelectorAll('#prefsModal .prefs-panel')]
    .find((p) => !p.classList.contains('d-none'));
  const out = [];
  for (const grid of panel.querySelectorAll('.prefs-grid')) {
    const gridLeft = Math.round(grid.getBoundingClientRect().left);
    const kids = [...grid.children];
    const rows = [];
    for (let i = 0; i + 1 < kids.length; i += 2) {
      const label = kids[i].getBoundingClientRect();
      const value = kids[i + 1].getBoundingClientRect();
      if (kids[i].classList.contains('card-ranges-label')) { i -= 1; continue; }   // full-span row
      rows.push({ label: (kids[i].textContent || '').trim().slice(0, 26),
        labelLeft: Math.round(label.left - gridLeft), valueLeft: Math.round(value.left - gridLeft),
        valueRight: Math.round(value.right - gridLeft), valueW: Math.round(value.width) });
    }
    out.push({ gridLeft, rows });
  }
  return out;
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
const showTab = async (tab) => {
  await page.click(`#prefsTabs .nav-link[data-prefs-panel="${tab}"]`);
  await page.waitForTimeout(350);
};

// ---- B22: every tab at the MINIMUM size -----------------------------------
console.log('=== minimum size (640×480) ===');
for (const tab of TABS) {
  await showTab(tab);
  const g = await page.evaluate(geometry);
  const cols = await page.evaluate(columns);
  console.log(`\n[${tab}] panel ${g.panel.w}×${g.panel.h} scrolls=${g.panelScrolls} (content ${g.panelScrollHeight})`);
  for (const grid of cols) {
    console.log('  grid rows:', JSON.stringify(grid.rows.slice(0, 12)));
  }
  console.log('  shot ->', await shot(`b22-tab-${tab}.png`));
}
await showTab('audio');
console.log('\nAudio (the maintainer\'s shot) ->', await shot('b22-audio-min.png'));
const min = await page.evaluate(geometry);
console.log('min geometry', JSON.stringify(min));

// ---- B23: drag the resize corner ------------------------------------------
const corner = await page.evaluate(() => {
  const b = document.querySelector('#prefsModal .modal-content').getBoundingClientRect();
  return { x: b.right - 4, y: b.bottom - 4 };
});
async function dragCorner(dx, dy) {
  await page.mouse.move(corner.x, corner.y);
  await page.mouse.down();
  await page.mouse.move(corner.x + dx, corner.y + dy, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(400);
}

await dragCorner(200, 200);
const bigger = await page.evaluate(geometry);
console.log('\n=== after dragging the corner +200/+200 ===');
console.log('geometry', JSON.stringify(bigger));
console.log('grew:', bigger.content.w - min.content.w, '×', bigger.content.h - min.content.h,
  '| tab area grew:', bigger.panel.w - min.panel.w, '×', bigger.panel.h - min.panel.h);
console.log('shot ->', await shot('b23-resized.png'));

// ---- B23: the minimum clamps ----------------------------------------------
const corner2 = await page.evaluate(() => {
  const b = document.querySelector('#prefsModal .modal-content').getBoundingClientRect();
  return { x: b.right - 4, y: b.bottom - 4 };
});
await page.mouse.move(corner2.x, corner2.y);
await page.mouse.down();
await page.mouse.move(corner2.x - 500, corner2.y - 500, { steps: 20 });
await page.mouse.up();
await page.waitForTimeout(400);
const clamped = await page.evaluate(geometry);
console.log('\n=== after dragging the corner 500px SMALLER than the minimum ===');
console.log('geometry', JSON.stringify(clamped));
console.log('shot ->', await shot('b23-min-clamp.png'));

await browser.close();
server.close();
