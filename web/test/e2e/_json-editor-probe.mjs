/*
 * Throwaway smoke probe (read-only): does the JSON config editor actually work in a browser?
 * Serves web/ exactly as scripts/capture-help-screenshots.mjs does, opens the app, drives the
 * Tools submenu, and checks the editor came up with the live document in it.
 */
import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const WEB = normalize(join(dirname(fileURLToPath(import.meta.url)), '..', '..'));
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.wasm': 'application/wasm',
  '.properties': 'text/plain', '.woff': 'font/woff', '.woff2': 'font/woff2', '.yaml': 'text/plain' };

const server = http.createServer((req, res) => {
  let p = decodeURIComponent((req.url || '/').split('?')[0]);
  if (p === '/') p = '/index.html';
  const fp = normalize(join(WEB, p));
  if (!fp.startsWith(WEB) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()] || 'application/octet-stream') + '; charset=utf-8' });
  createReadStream(fp).pipe(res);
});
const port = await new Promise((r) => server.listen(0, '127.0.0.1', () => r(server.address().port)));

const browser = await chromium.launch({ headless: true, args: ['--use-fake-ui-for-media-stream'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('response', (r) => { if (r.status() === 404) errors.push('404 ' + r.url()); });

await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'load' });
await page.waitForSelector('#scopePane', { timeout: 20000 }).catch(() => {});
await page.waitForTimeout(2500);

const menu = await page.evaluate(() => {
  const items = (id) => [...document.querySelectorAll(`#${id} .dropdown-item`)].map((b) => b.textContent.trim());
  return { prefs: items('menuCfgPreferencesItems'), devices: items('menuCfgDevicesItems') };
});

// Open Preferences ▸ Current through the real handler.
await page.evaluate(() => document.querySelector('#menuCfgPreferencesItems .dropdown-item').click());
await page.waitForSelector('#jsonConfigModal.show', { timeout: 8000 });
await page.waitForTimeout(1200);

const geom = await page.evaluate(() => {
  const content = document.querySelector('#jsonConfigModal .modal-content');
  const body = document.querySelector('#jsonConfigModal .modal-body');
  const ed = document.getElementById('jsonConfigEditor');
  const cs = getComputedStyle(content);
  const r = content.getBoundingClientRect();
  const br = body.getBoundingClientRect();
  const er = ed.getBoundingClientRect();
  return {
    widthPct: +(r.width / window.innerWidth * 100).toFixed(1),
    heightPct: +(r.height / window.innerHeight * 100).toFixed(1),
    resize: cs.resize,
    bodyPadding: getComputedStyle(body).padding,
    gutterLeft: +(er.left - br.left).toFixed(1),
    gutterTop: +(er.top - br.top).toFixed(1),
    gutterRight: +(br.right - er.right).toFixed(1),
  };
});
// Drag the window by its header and confirm it moved by exactly that much.
const before = await page.evaluate(() => {
  const r = document.querySelector('#jsonConfigModal .modal-content').getBoundingClientRect();
  return { x: r.left, y: r.top };
});
const hdr = await page.$('#jsonConfigModal .modal-header');
const hb = await hdr.boundingBox();
await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
await page.mouse.down();
await page.mouse.move(hb.x + hb.width / 2 - 120, hb.y + hb.height / 2 + 60, { steps: 8 });
await page.mouse.up();
await page.waitForTimeout(250);
const after = await page.evaluate(() => {
  const r = document.querySelector('#jsonConfigModal .modal-content').getBoundingClientRect();
  return { x: r.left, y: r.top };
});
console.log('--- drag ---');
console.log('moved by           :', Math.round(after.x - before.x), Math.round(after.y - before.y),
  '(asked for -120, +60)');

// A drag started on the EDITOR must not move the window (CodeMirror keeps its own mouse).
const edBox = await (await page.$('#jsonConfigEditor')).boundingBox();
await page.mouse.move(edBox.x + 200, edBox.y + 80);
await page.mouse.down();
await page.mouse.move(edBox.x + 60, edBox.y + 160, { steps: 5 });
await page.mouse.up();
const afterEditorDrag = await page.evaluate(() => {
  const r = document.querySelector('#jsonConfigModal .modal-content').getBoundingClientRect();
  return { x: r.left, y: r.top };
});
console.log('editor drag moved  :', Math.round(afterEditorDrag.x - after.x),
  Math.round(afterEditorDrag.y - after.y), '(must be 0 0)');

// --- WHAT IS ACTUALLY AT THE RESIZE GRIP? ---
// Put the window back where the stylesheet places it first: the drag above left it 60px lower,
// which on this viewport pushes its bottom edge off-screen - grabbing "the corner" would then
// be grabbing empty space below the window and would fail for a reason that is not the bug.
const grip = await page.evaluate(() => {
  const dlg = document.querySelector('#jsonConfigModal .modal-content');
  dlg.style.left = ''; dlg.style.top = '';
  const r = dlg.getBoundingClientRect();
  const probe = (dx, dy) => {
    const el = document.elementFromPoint(r.right - dx, r.bottom - dy);
    return el ? (el.id || el.className || el.tagName) : 'none';
  };
  return {
    dialogRect: { w: Math.round(r.width), h: Math.round(r.height) },
    at2px: probe(2, 2), at6px: probe(6, 6), at12px: probe(12, 12),
    dialogResize: getComputedStyle(dlg).resize,
    dialogOverflow: getComputedStyle(dlg).overflow,
    boxSizing: getComputedStyle(dlg).boxSizing,
    padBottom: getComputedStyle(dlg).paddingBottom,
    modalOverflow: getComputedStyle(document.getElementById('jsonConfigModal')).overflow,
  };
});
console.log('--- resize grip hit-test ---');
console.log('dialog resize/overflow:', grip.dialogResize, '/', grip.dialogOverflow,
  '| .modal overflow:', grip.modalOverflow);
console.log('element at corner -2/-6/-12 px:', grip.at2px, '|', grip.at6px, '|', grip.at12px);

// Try the real gesture: grab the very corner and drag it.
const dlgBox = await (await page.$('#jsonConfigModal .modal-content')).boundingBox();
const sizeBefore = { w: Math.round(dlgBox.width), h: Math.round(dlgBox.height) };
await page.mouse.move(dlgBox.x + dlgBox.width - 3, dlgBox.y + dlgBox.height - 3);
await page.mouse.down();
await page.mouse.move(dlgBox.x + dlgBox.width - 3 - 200, dlgBox.y + dlgBox.height - 3 - 150, { steps: 10 });
await page.mouse.up();
await page.waitForTimeout(250);
const sizeAfter = await page.evaluate(() => {
  const r = document.querySelector('#jsonConfigModal .modal-content').getBoundingClientRect();
  return { w: Math.round(r.width), h: Math.round(r.height) };
});
const inline = await page.evaluate(() => {
  const d = document.querySelector('#jsonConfigModal .modal-content');
  return { w: d.style.width || '(none)', h: d.style.height || '(none)' };
});
console.log('corner drag        : before', JSON.stringify(sizeBefore), '-> after', JSON.stringify(sizeAfter),
  '| delta', (sizeAfter.w - sizeBefore.w), (sizeAfter.h - sizeBefore.h), '(asked for -200, -150)');
console.log('inline set by grip :', JSON.stringify(inline));

console.log('--- geometry ---');
console.log('size vs viewport   :', geom.widthPct + '% w x ' + geom.heightPct + '% h');
console.log('resize             :', geom.resize);
console.log('modal-body padding :', geom.bodyPadding);
console.log('editor gutter l/t/r:', geom.gutterLeft, geom.gutterTop, geom.gutterRight);

const state = await page.evaluate(() => {
  const cm = document.querySelector('#jsonConfigEditor .cm-editor');
  const text = document.querySelector('#jsonConfigEditor .cm-content')?.innerText || '';
  return {
    editorPresent: !!cm,
    title: document.getElementById('jsonConfigTitle').textContent,
    key: document.getElementById('jsonConfigKey').textContent,
    okVisible: !document.getElementById('jsonConfigOk').classList.contains('d-none'),
    firstChars: text.slice(0, 60).replace(/\s+/g, ' '),
    looksLikeJson: text.trimStart().startsWith('{'),
    lineCount: text.split('\n').length,
  };
});

console.log('submenu items      :', JSON.stringify(menu));
console.log('editor present     :', state.editorPresent);
console.log('title / key        :', JSON.stringify(state.title), '/', JSON.stringify(state.key));
console.log('OK button visible  :', state.okVisible);
console.log('document looks JSON:', state.looksLikeJson, '| lines rendered:', state.lineCount);
console.log('first chars        :', state.firstChars);
console.log('page errors        :', errors.length ? errors.slice(0, 5) : 'none');

// ---- the other half: a quarantined copy appears, opens read-only, and offers no OK ----
// PATH A - a quarantined value that DOES parse (the maintainer's case: a hand-added entry,
// and also any store this app wrote, since it minifies). It must be pretty-printed.
await page.evaluate(() => {
  window.bootstrap.Modal.getInstance(document.getElementById('jsonConfigModal')).hide();
  localStorage.setItem('phonalyser.devices.corrupt',
    '{"formatVersion":1,"contentVersion":2,"audioDevices":[{"name":"Test ADC","match":["Test ADC"]}]}');
});
await page.waitForTimeout(500);
await page.evaluate(() => document.getElementById('menuPrefs').closest('.dropdown')
  .dispatchEvent(new Event('show.bs.dropdown')));
await page.evaluate(() => document.querySelectorAll('#menuCfgDevicesItems .dropdown-item')[1].click());
await page.waitForSelector('#jsonConfigModal.show', { timeout: 8000 });
await page.waitForTimeout(900);
const parses = await page.evaluate(() => {
  const txt = document.querySelector('#jsonConfigEditor .cm-content')?.innerText || '';
  return { lines: txt.split('\n').length, head: txt.split('\n').slice(0, 3).join(' | ') };
});
console.log('--- corrupt copy: PARSES ---');
console.log('lines rendered     :', parses.lines, '(1 = still unformatted)');
console.log('first lines        :', parses.head);

// PATH B - a value that does NOT parse. It is the only evidence of what went wrong, so it
// must be shown byte for byte, not repaired, truncated or blanked.
await page.evaluate(() => {
  window.bootstrap.Modal.getInstance(document.getElementById('jsonConfigModal')).hide();
  localStorage.setItem('phonalyser.devices.corrupt', '{ this never parsed');
});
await page.waitForTimeout(600);
// Rebuilt on menu open, which is the point - nothing was reloaded.
await page.evaluate(() => document.getElementById('menuPrefs').closest('.dropdown')
  .dispatchEvent(new Event('show.bs.dropdown')));
const afterQuarantine = await page.evaluate(() =>
  [...document.querySelectorAll('#menuCfgDevicesItems .dropdown-item')].map((b) => b.textContent.trim()));
await page.evaluate(() => document.querySelectorAll('#menuCfgDevicesItems .dropdown-item')[1].click());
await page.waitForSelector('#jsonConfigModal.show', { timeout: 8000 });
await page.waitForTimeout(900);
const corrupt = await page.evaluate(() => ({
  title: document.getElementById('jsonConfigTitle').textContent,
  key: document.getElementById('jsonConfigKey').textContent,
  okHidden: document.getElementById('jsonConfigOk').classList.contains('d-none'),
  text: (document.querySelector('#jsonConfigEditor .cm-content')?.innerText || '').trim(),
  readOnly: document.querySelector('#jsonConfigEditor .cm-content')?.getAttribute('contenteditable'),
}));
console.log('--- corrupt copy ---');
console.log('devices submenu    :', JSON.stringify(afterQuarantine));
console.log('title / key        :', JSON.stringify(corrupt.title), '/', JSON.stringify(corrupt.key));
console.log('OK hidden          :', corrupt.okHidden);
console.log('contenteditable    :', corrupt.readOnly, '(false = read-only)');
console.log('raw text shown     :', JSON.stringify(corrupt.text));

await browser.close();
server.close();
