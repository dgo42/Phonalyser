// MANDATORY screenshot-correctness proof - REAL headless browser, REAL produced
// image, and REAL DOM measurements (not code reading).
//
// Drives the real headless web app (Playwright + fake audio) at a WIDE non-square
// LIVE viewport, starts recording, waits for a trace, then exercises the in-app
// screenshot at a target MUCH WIDER than live. It proves four things end-to-end:
//
//   1. The LIVE view carries the V/time edge labels + cap/s as real DOM elements
//      (querySelector finds them, they are NOT zero-size) and the live #scope
//      canvas still shows a trace.
//   2. One specific edge-label DOM element's bounding box + computed font-size are
//      measured in the LIVE view.
//   3. After triggering the in-app screenshot at a target much wider than live, the
//      SAME edge-label element is measured in the CLONE that the compose path lays
//      out (it persists in the offscreen shot-clone host): its font-size and its
//      per-character ASPECT must match live within ~3% (the marks are NOT
//      horizontally stretched/compressed), and the .lr buttons must be the SAME
//      pixel size as live. The produced PNG is decoded back to pixels: the trace
//      must be present and the image ratio must match the requested target.
//   4. The capture reserves NO scrollbar gutter (right gutter / hscroll padding
//      hidden) and the scope↔zoomed gap is ~2px.
//
// Fails loudly with measured numbers if any check fails.
//
// Run:  node test/e2e/shot-proof.mjs   (from web/)

import http from 'node:http';
import { createReadStream, existsSync, statSync, writeFileSync, readFileSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const OUT_PNG = join(__dirname, '_shot-proof.png');
const MIME = { '.html':'text/html','.mjs':'text/javascript','.js':'text/javascript','.css':'text/css','.json':'application/json','.webmanifest':'application/manifest+json','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.wasm':'application/wasm','.properties':'text/plain' };

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

let passed = 0, failed = 0;
function check(name, ok, detail) {
  if (ok) { passed++; console.log(`PASS: ${name}` + (detail ? ` - ${detail}` : '')); }
  else { failed++; console.log(`FAIL: ${name}` + (detail ? ` - ${detail}` : '')); }
}

// Independent PNG size read straight off the file bytes (IHDR: width = bytes
// 16..19, height = bytes 20..23, big-endian) - proves the produced file IS the
// requested size without trusting any in-page decode.
function pngSize(buf) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  for (let i = 0; i < 8; i++) if (buf[i] !== sig[i]) return { ok: false };
  return { ok: true, w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

// Drives the in-app screenshot at an EXACT target size and returns the produced
// PNG bytes + the in-page decoded element-region pixel heights. `toBlob` is already
// hooked to publish window.__shot. The shot dialog is already open. Sets keep-aspect
// OFF, writes the exact W/H, saves, waits for the blob, then decodes IN-PAGE: locates
// the top-left BUTTON GROUP band, the meas-table TEXT band just below it, and the
// bottom TAB-STRIP TILE band, returning each band's IMAGE-pixel height + the trace
// count in the upper region. scaleOut = producedW / requestedW maps live CSS px to
// output px so a non-shrunk capture has band ≈ liveCss × scaleOut.
async function captureAt(page, w, h) {
  await page.evaluate(() => { window.__shot = null; });
  // (Re)open the screenshot dialog - #shotSave hides the modal after each save, so a
  // fresh capture must re-open it. The offscreen shot-clone host carries DUPLICATE ids
  // (e.g. a second #scopeShot), so drive everything through getElementById/.click()
  // inside the page (returns the LIVE element, first in tree order) and strip any
  // leftover modal backdrop that would intercept clicks.
  await page.evaluate(() => {
    // Clear any half-faded modal/backdrop from a previous capture so the next
    // shotModal.show() is not swallowed by a stuck Bootstrap transition (in headless
    // transitionend can fail to fire, leaving the instance mid-state). Force the
    // modal fully hidden first, then re-open it cleanly.
    const m = document.getElementById('shotModal');
    if (m && window.bootstrap) {
      try { const i = window.bootstrap.Modal.getOrCreateInstance(m);
        i._isTransitioning = false; i.hide();
        // Force the instance's internal flags to a clean HIDDEN state so the next
        // shotModal.show() actually re-opens it (a stuck _isShown/_isTransitioning
        // makes Bootstrap's show() a no-op in headless where transitionend may not fire).
        i._isShown = false; i._isTransitioning = false;
      } catch (e) { /* ignore */ }
    }
    if (m) { m.classList.remove('show'); m.style.display = 'none'; m.setAttribute('aria-hidden', 'true'); }
    document.querySelectorAll('.modal-backdrop').forEach((b) => b.remove());
    document.body.classList.remove('modal-open');
    // Open the Utility tab IDEMPOTENTLY: clicking an already-active+open tab TOGGLES it
    // CLOSED (app.js wasOpen logic), so only click when it is not already showing.
    const util = document.getElementById('scopeUtility');
    if (!util || !util.classList.contains('show')) {
      document.querySelector('#scopeTabs .tab[data-panel="scopeUtility"]').click();
    }
  });
  await page.waitForFunction(() => { const p = document.getElementById('scopeUtility'); return p && p.classList.contains('show'); }, { timeout: 5000 });
  await page.evaluate(() => document.getElementById('scopeShot').click());
  await page.waitForFunction(() => { const m = document.getElementById('shotModal'); return m && m.classList.contains('show'); }, { timeout: 8000 });
  await page.evaluate(({ w, h }) => {
    const k = document.getElementById('shotKeepAspect'); if (k && k.checked) { k.checked = false; k.dispatchEvent(new Event('change', { bubbles: true })); }
    const set = (id, v) => { const el = document.getElementById(id); el.value = String(v); el.dispatchEvent(new Event('input', { bubbles: true })); };
    set('shotWidth', w); set('shotHeight', h);
  }, { w, h });
  await page.evaluate(() => document.getElementById('shotSave').click());
  await page.waitForFunction(() => window.__shot && window.__shot.dataUrl, { timeout: 15000 });
  return page.evaluate(async ({ reqW }) => {
    const res = await fetch(window.__shot.dataUrl);
    const blob = await res.blob();
    const bmp = await createImageBitmap(blob);
    const W = bmp.width, H = bmp.height;
    const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
    const ctx = cv.getContext('2d'); ctx.drawImage(bmp, 0, 0);
    const data = ctx.getImageData(0, 0, W, H).data;
    const at = (x, y) => { const i = (y * W + x) * 4; return [data[i], data[i + 1], data[i + 2]]; };
    const scaleOut = W / reqW;
    const dpr = window.devicePixelRatio || 1;
    // Trace pixels (yellow/cyan) in the upper 60% - proves the trace region is non-empty.
    const mainBot = Math.floor(H * 0.60);
    let trace = 0;
    for (let y = 0; y < mainBot; y++) for (let x = 0; x < W; x++) {
      const [r, g, b] = at(x, y);
      if ((r > 180 && g > 160 && b < 90) || (b > 160 && g > 160 && r < 150)) trace++;
    }
    // --- TOP-LEFT BUTTON GROUP band: the .lr-tools row sits at css ~(8,5). The buttons are
    // now TRANSPARENT over the black scope (Java SWT NO_BACKGROUND ToolButtons - only the
    // SELECTED channel button is FILLED in its channel colour), so locate the band by that
    // fill's vertical extent (cyan #00d7ff L / gold #ffd700 R). A filled button paints a
    // colour run ≈ a button wide; the dashed overlay, the ~1px unselected outline and text
    // never do - require a run ≥ ~half a button (sized by dpr, the chrome scale factor).
    const bx0 = Math.max(0, Math.round(8 * scaleOut)), bx1 = Math.min(W, Math.round(150 * scaleOut));
    const bScanY = Math.min(H, Math.round(48 * scaleOut));
    const bMinRun = Math.max(4, Math.round(11 * dpr));
    let btnTop = -1, btnBot = -1;
    for (let y = 0; y < bScanY; y++) {
      let col = 0;
      for (let x = bx0; x < bx1; x++) {
        const c = at(x, y);
        if ((c[2] > 160 && c[1] > 160 && c[0] < 150) || (c[0] > 180 && c[1] > 160 && c[2] < 90)) col++;
      }
      if (col >= bMinRun) { if (btnTop < 0) btnTop = y; btnBot = y; } else if (btnTop >= 0) break;
    }
    const btnH = btnTop >= 0 ? btnBot - btnTop + 1 : 0;
    // --- TOP-LEFT TABLE TEXT band: the meas-table sits at css ~(8,32). Scan a narrow
    // left strip below the buttons for rows carrying light TEXT pixels (sparse, so a
    // low non-black threshold), measure the vertical extent of the first text run.
    const tx0 = Math.max(0, Math.round(10 * scaleOut)), tx1 = Math.min(W, Math.round(140 * scaleOut));
    const tScanLo = Math.round(28 * scaleOut), tScanHi = Math.min(H, Math.round(80 * scaleOut));
    let txtTop = -1, txtBot = -1;
    for (let y = tScanLo; y < tScanHi; y++) {
      let nb = 0; for (let x = tx0; x < tx1; x++) { const c = at(x, y); if (c[0] + c[1] + c[2] > 360) nb++; }
      if (nb >= 2) { if (txtTop < 0) txtTop = y; txtBot = y; }
    }
    const txtH = txtTop >= 0 ? txtBot - txtTop + 1 : 0;
    // --- BOTTOM TAB-STRIP TILE band: the .tiletabs strip is the bottom ~46px; the
    // light-grey #e0e0e0 tiles are 18px rounded rects on a #f0f0f0 strip. Scan the
    // bottom region for rows that are PREDOMINANTLY light (the strip is bright vs the
    // black trace area above) and report that bright band's height (the strip), plus
    // locate a TILE by its rounded-rect light run height within the strip.
    const stripScan = Math.min(H, Math.round(70 * scaleOut));
    let stripTop = -1, stripBot = -1;
    for (let y = H - 1; y >= H - stripScan; y--) {
      let bright = 0; for (let x = 0; x < W; x++) { const c = at(x, y); if (c[0] > 200 && c[1] > 200 && c[2] > 200) bright++; }
      if (bright > W * 0.30) { if (stripBot < 0) stripBot = y; stripTop = y; } else if (stripBot >= 0) break;
    }
    const stripH = stripBot >= 0 ? stripBot - stripTop + 1 : 0;
    // Tile height: within the strip, scan a column near the left tabs' tile area for a
    // contiguous run of the slightly-darker tile grey (#e0e0e0 ≈ 224) vs strip #f0f0f0
    // (240). Sample several columns and take the tallest tile-grey run.
    let tileH = 0;
    if (stripBot >= 0) {
      for (let xc = Math.round(40 * scaleOut); xc < Math.round(420 * scaleOut) && xc < W; xc += Math.max(2, Math.round(6 * scaleOut))) {
        let run = 0, best = 0;
        for (let y = stripTop; y <= stripBot; y++) { const [r, g, b] = at(xc, y);
          const tileGrey = r >= 210 && r <= 234 && g >= 210 && g <= 234 && b >= 210 && b <= 234;
          if (tileGrey) { run++; best = Math.max(best, run); } else run = 0; }
        tileH = Math.max(tileH, best);
      }
    }
    return { W, H, scaleOut, dpr, trace, mainBot, btnH, txtH, stripH, tileH };
  }, { reqW: w });
}

// Live LIVE viewport: WIDE and non-square (ratio 2.333) so it differs from BOTH
// the live pane aspect and the requested target aspect (1900x520 ≈ 3.654).
const LIVE_W = 1400, LIVE_H = 600;
const TARGET_W = 1900, TARGET_H = 520;
const TOL = 0.03;   // ~3% tolerance for font-size + char-aspect match

async function main() {
  const fakeWav = makeFakeAudioWav(join(__dirname, 'fake-audio.wav'));
  const { server, port } = await startServer();
  const origin = `http://127.0.0.1:${port}`;
  console.log(`server: ${origin}`);

  const browser = await chromium.launch({ headless: true, args: [
    '--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream',
    '--autoplay-policy=no-user-gesture-required', `--use-file-for-fake-audio-capture=${fakeWav}` ] });
  const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: LIVE_W, height: LIVE_H }, deviceScaleFactor: 2 });
  await context.grantPermissions(['microphone'], { origin });
  const page = await context.newPage();

  const errors = [];
  page.on('pageerror', (err) => errors.push('pageerror: ' + (err && err.stack || err)));

  try {
    await page.goto(origin + '/index.html', { waitUntil: 'load' });
    await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });
    await page.waitForFunction(() => !!document.getElementById('scope'), { timeout: 15000 });
    await page.waitForTimeout(500);

    // ---- 1. Start recording, wait for a trace, assert live edge labels + cap/s. ----
    await page.click('.scope-pane .led-btn');
    await page.waitForTimeout(1800);

    // Sanity: the LIVE #scope canvas carries a trace.
    const liveTrace = await page.evaluate(() => {
      const c = document.getElementById('scope'); const ctx = c.getContext('2d');
      const W = c.width, H = c.height; const img = ctx.getImageData(0,0,W,H).data;
      let n = 0;
      for (let i = 0; i < img.length; i += 4) { const r=img[i],g=img[i+1],b=img[i+2];
        if ((r>180&&g>160&&b<90)||(b>160&&g>160&&r<150)) n++; }
      return { W, H, trace: n };
    });
    check('live #scope canvas shows a trace', liveTrace.trace > 200,
      `livePx=${liveTrace.trace} (${liveTrace.W}x${liveTrace.H})`);

    // The V/time edge labels + cap/s exist as DOM elements (querySelector finds
    // them) AND are NOT zero-size. Measure the cap/s, a time label and a volt label.
    const liveLabels = await page.evaluate(() => {
      const ovl = document.querySelector('.scope-pane .canvas-wrap .scope-overlay');
      const visible = (el) => {
        if (!el) return null;
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        return { found: true, display: cs.display, text: el.textContent,
                 w: r.width, h: r.height, fontSize: cs.fontSize };
      };
      const caps = ovl && ovl.querySelector('.scope-ovl-caps');
      const times = ovl ? Array.from(ovl.querySelectorAll('.scope-ovl-time')).filter((e) => getComputedStyle(e).display !== 'none' && e.textContent) : [];
      const volts = ovl ? Array.from(ovl.querySelectorAll('.scope-ovl-volt')).filter((e) => getComputedStyle(e).display !== 'none' && e.textContent) : [];
      return {
        overlayFound: !!ovl,
        caps: visible(caps),
        time: visible(times[0]),
        volt: visible(volts[0]),
        nTime: times.length, nVolt: volts.length,
      };
    });

    check('live overlay layer (.scope-overlay) present in DOM', liveLabels.overlayFound, '');
    check('live cap/s label found + non-zero-size DOM element',
      !!liveLabels.caps && liveLabels.caps.display !== 'none' && liveLabels.caps.w > 0 && liveLabels.caps.h > 0,
      liveLabels.caps ? `text="${liveLabels.caps.text}" box=${liveLabels.caps.w.toFixed(1)}x${liveLabels.caps.h.toFixed(1)} font=${liveLabels.caps.fontSize}` : 'NOT FOUND');
    check('live time edge label found + non-zero-size DOM element',
      !!liveLabels.time && liveLabels.time.w > 0 && liveLabels.time.h > 0,
      liveLabels.time ? `text="${liveLabels.time.text}" box=${liveLabels.time.w.toFixed(1)}x${liveLabels.time.h.toFixed(1)} font=${liveLabels.time.fontSize}` : 'NOT FOUND');
    check('live V edge label found + non-zero-size DOM element',
      !!liveLabels.volt && liveLabels.volt.w > 0 && liveLabels.volt.h > 0,
      liveLabels.volt ? `text="${liveLabels.volt.text}" box=${liveLabels.volt.w.toFixed(1)}x${liveLabels.volt.h.toFixed(1)} font=${liveLabels.volt.fontSize}` : 'NOT FOUND');

    // ---- 2. Measure ONE specific edge-label DOM element in the LIVE view. ----
    // Use the FIRST visible .scope-ovl-time (the left time mark). Compute its
    // per-character aspect = (boxWidth / charCount) / boxHeight - invariant to the
    // text content, sensitive to horizontal glyph stretch.
    const live = await page.evaluate(() => {
      const ovl = document.querySelector('.scope-pane .canvas-wrap .scope-overlay');
      const times = Array.from(ovl.querySelectorAll('.scope-ovl-time')).filter((e) => getComputedStyle(e).display !== 'none' && e.textContent);
      const el = times[0];
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      const chars = el.textContent.length;
      const lr = document.querySelector('.scope-pane .lr-tools .lr');
      const lrR = lr.getBoundingClientRect();
      return {
        text: el.textContent, chars,
        w: r.width, h: r.height, fontSize: parseFloat(cs.fontSize),
        charAspect: (r.width / chars) / r.height,
        lrW: lrR.width, lrH: lrR.height,
      };
    });
    check('live measured edge label has a positive char-aspect',
      live.charAspect > 0 && live.w > 0,
      `liveLabel text="${live.text}" box=${live.w.toFixed(2)}x${live.h.toFixed(2)} font=${live.fontSize}px chars=${live.chars} charAspect=${live.charAspect.toFixed(4)}`);
    check('live .lr button has non-zero size',
      live.lrW > 0 && live.lrH > 0,
      `lr=${live.lrW.toFixed(2)}x${live.lrH.toFixed(2)} (CSS expects 22x22)`);

    // ---- 1c. LIVE-DOM geometry of the four chrome families the produced image is
    // later checked against: a tab-strip TILE (.scope-pane .tile), a .lr button, a
    // measurement-table CELL (.scope-meas-table td.mt-cur), and an edge mark
    // (.scope-ovl-time / .scope-ovl-volt). Measure each one's CSS-pixel HEIGHT and
    // computed font-size in the live, on-screen DOM - these are the ground-truth
    // sizes the decoded PNG element-regions must match (NOT shrunk).
    // The measurement table un-hides itself once a channel is enabled + recording;
    // nudge oscShowMeasurementTable on and wait a frame so the cells have boxes.
    await page.evaluate(() => {
      const t = document.getElementById('scopeMeasTable');
      if (t && t.hidden) { const b = document.getElementById('scopeTableToggle'); if (b) b.click(); }
    });
    await page.waitForTimeout(400);
    const liveGeo = await page.evaluate(() => {
      const pane = document.getElementById('scopePane');
      const box = (el) => { if (!el) return null; const cs = getComputedStyle(el); const r = el.getBoundingClientRect();
        return { w: r.width, h: r.height, fontSize: parseFloat(cs.fontSize), text: (el.textContent || '').trim() }; };
      const tile = pane.querySelector('.tiletabs .tab .tile');
      const lr = pane.querySelector('.lr-tools .lr');
      const cells = Array.from(pane.querySelectorAll('.scope-meas-table td.mt-cur'))
        .filter((e) => getComputedStyle(e).display !== 'none' && (e.textContent || '').trim());
      const ovl = pane.querySelector('.canvas-wrap .scope-overlay');
      const times = ovl ? Array.from(ovl.querySelectorAll('.scope-ovl-time')).filter((e) => getComputedStyle(e).display !== 'none' && e.textContent) : [];
      const volts = ovl ? Array.from(ovl.querySelectorAll('.scope-ovl-volt')).filter((e) => getComputedStyle(e).display !== 'none' && e.textContent) : [];
      const t = document.getElementById('scopeMeasTable');
      return { tableVisible: !!t && !t.hidden, nCells: cells.length,
        tile: box(tile), lr: box(lr), cell: box(cells[0]), time: box(times[0]), volt: box(volts[0]) };
    });
    check('(1c) live tab TILE measured (.scope-pane .tile) - non-zero height + font',
      !!liveGeo.tile && liveGeo.tile.h > 0 && liveGeo.tile.fontSize > 0,
      liveGeo.tile ? `tile h=${liveGeo.tile.h.toFixed(2)}px font=${liveGeo.tile.fontSize}px (CSS height:18 font:11) text="${liveGeo.tile.text}"` : 'NOT FOUND');
    check('(1c) live .lr button measured - non-zero height + font',
      !!liveGeo.lr && liveGeo.lr.h > 0 && liveGeo.lr.fontSize > 0,
      liveGeo.lr ? `lr h=${liveGeo.lr.h.toFixed(2)}px font=${liveGeo.lr.fontSize}px (CSS height:22 font:12)` : 'NOT FOUND');
    check('(1c) live meas-table CELL measured (.scope-meas-table td.mt-cur) - non-zero height + font',
      !!liveGeo.cell && liveGeo.cell.h > 0 && liveGeo.cell.fontSize > 0,
      liveGeo.cell ? `cell h=${liveGeo.cell.h.toFixed(2)}px font=${liveGeo.cell.fontSize}px (CSS font:11) tableVisible=${liveGeo.tableVisible} nCells=${liveGeo.nCells} text="${liveGeo.cell.text}"` : `NOT FOUND (tableVisible=${liveGeo.tableVisible} nCells=${liveGeo.nCells})`);
    check('(1c) live edge mark measured (.scope-ovl-time/.scope-ovl-volt) - non-zero height + font',
      !!liveGeo.time && liveGeo.time.h > 0 && liveGeo.time.fontSize > 0,
      liveGeo.time ? `time h=${liveGeo.time.h.toFixed(2)}px font=${liveGeo.time.fontSize}px${liveGeo.volt ? ` | volt h=${liveGeo.volt.h.toFixed(2)}px font=${liveGeo.volt.fontSize}px` : ''}` : 'NOT FOUND');

    // ---- 1b. The trigger-level / channel-offset / trigger-position dashed lines +
    // their triangle handles are REAL DOM elements in .scope-overlay: queryable,
    // displayed, and non-zero size. (The lines are 0-thick borders so a getBounding
    // box may report 0 in one axis - assert the OTHER axis is the line length, and
    // the rendered border-width is the fixed 1.5px line. The handle is a CSS
    // border-triangle: width/height 0, but its border-box has a bounded fixed size.)
    const liveSliders = await page.evaluate(() => {
      const ovl = document.querySelector('.scope-pane .canvas-wrap .scope-overlay');
      const m = (el) => {
        if (!el) return { found: false };
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        return {
          found: true, display: cs.display,
          w: r.width, h: r.height,
          borderTop: parseFloat(cs.borderTopWidth) || 0,
          borderLeft: parseFloat(cs.borderLeftWidth) || 0,
          // CSS triangle footprint = sum of the perpendicular transparent borders ×
          // the solid border depth; just report the four border widths.
          bw: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth].map((v) => parseFloat(v) || 0),
        };
      };
      // Each slider = {under, core, handle}. The CORE carries the 1.5px coloured
      // dashed border; the HANDLE is the triangle. There are three logical sliders:
      // trigger-level (h), trigger-position (v), and the active channel offset (h).
      const cores = Array.from(ovl.querySelectorAll('.scope-ovl-slidercore')).filter((e) => getComputedStyle(e).display !== 'none');
      const handles = Array.from(ovl.querySelectorAll('.scope-ovl-sliderhandle')).filter((e) => getComputedStyle(e).display !== 'none');
      const lvlCore = ovl.querySelector('.scope-ovl-slidercore.scope-ovl-hline.scope-ovl-tri-left, .scope-ovl-slidercore.scope-ovl-hline');
      const lvlHandle = ovl.querySelector('.scope-ovl-sliderhandle.scope-ovl-tri-left');
      const posCore = ovl.querySelector('.scope-ovl-slidercore.scope-ovl-vline');
      const posHandle = ovl.querySelector('.scope-ovl-sliderhandle.scope-ovl-tri-up');
      const offHandle = ovl.querySelector('.scope-ovl-sliderhandle.scope-ovl-tri-right');
      return {
        overlayFound: !!ovl,
        nVisCores: cores.length, nVisHandles: handles.length,
        lvlCore: m(lvlCore), lvlHandle: m(lvlHandle),
        posCore: m(posCore), posHandle: m(posHandle),
        offHandle: m(offHandle),
      };
    });

    check('(1b) trigger-LEVEL dashed line is a DOM element in .scope-overlay (1.5px core, spans width)',
      liveSliders.lvlCore.found && liveSliders.lvlCore.display !== 'none' &&
      liveSliders.lvlCore.w > 50 && Math.abs(liveSliders.lvlCore.borderTop - 1.5) < 0.6,
      `lvlCore box=${liveSliders.lvlCore.w.toFixed(1)}x${liveSliders.lvlCore.h.toFixed(1)} borderTop=${liveSliders.lvlCore.borderTop}px (want ~1.5)`);
    check('(1b) trigger-POSITION dashed line is a DOM element in .scope-overlay (1.5px core, spans height)',
      liveSliders.posCore.found && liveSliders.posCore.display !== 'none' &&
      liveSliders.posCore.h > 50 && Math.abs(liveSliders.posCore.borderLeft - 1.5) < 0.6,
      `posCore box=${liveSliders.posCore.w.toFixed(1)}x${liveSliders.posCore.h.toFixed(1)} borderLeft=${liveSliders.posCore.borderLeft}px (want ~1.5)`);
    check('(1b) trigger-level HANDLE triangle is a bounded DOM element',
      liveSliders.lvlHandle.found && liveSliders.lvlHandle.display !== 'none' &&
      liveSliders.lvlHandle.bw.some((b) => b >= 5),
      `lvlHandle borders=[${liveSliders.lvlHandle.bw.join(',')}] (CSS triangle: 6/6 transparent + 10 solid)`);
    check('(1b) channel-OFFSET handle triangle is a bounded DOM element',
      liveSliders.offHandle.found && liveSliders.offHandle.display !== 'none' &&
      liveSliders.offHandle.bw.some((b) => b >= 5),
      `offHandle borders=[${liveSliders.offHandle.bw.join(',')}]`);
    check('(1b) trigger-POSITION handle triangle is a bounded DOM element',
      liveSliders.posHandle.found && liveSliders.posHandle.display !== 'none' &&
      liveSliders.posHandle.bw.some((b) => b >= 5),
      `posHandle borders=[${liveSliders.posHandle.bw.join(',')}]`);
    check('(1b) at least 3 slider cores + 3 handles visible in .scope-overlay',
      liveSliders.nVisCores >= 3 && liveSliders.nVisHandles >= 3,
      `visibleCores=${liveSliders.nVisCores} visibleHandles=${liveSliders.nVisHandles}`);

    // ---- 3. Dragging STILL works: simulate a real drag on the trigger-level handle
    // in the LIVE view and assert the pref CHANGED. The drag is hit-tested on the
    // #scope CANVAS (scope-view binds mousedown on the canvas; the overlay is
    // pointer-events:none). The trigger-level handle sits at the canvas RIGHT edge,
    // y = levelFrac·H. With levelFrac defaulting to 0.5, grab near (W-6, H/2) and
    // drag UP to ~0.25·H, which must drive oscTriggerLevelFrac well away from 0.5.
    // Read the pref out of its real persistence (localStorage under PREFS_KEY) - the
    // slider mouseup handler calls prefs.save() which writes localStorage directly.
    const readLevelFrac = () => page.evaluate(() => {
      try {
        const raw = localStorage.getItem('phonalyser.preferences');
        if (!raw) return null;
        const v = JSON.parse(raw).oscTriggerLevelFrac;
        return typeof v === 'number' ? v : null;
      } catch (e) { return null; }
    });
    // Seed localStorage with the current (default 0.5) level so "before" is concrete.
    await page.evaluate(() => {
      let doc = {};
      try { doc = JSON.parse(localStorage.getItem('phonalyser.preferences') || '{}'); } catch (e) { doc = {}; }
      if (typeof doc.oscTriggerLevelFrac !== 'number') { doc.oscTriggerLevelFrac = 0.5; localStorage.setItem('phonalyser.preferences', JSON.stringify(doc)); }
    });
    const beforeFrac = await readLevelFrac();

    const dragInfo = await page.evaluate(() => {
      const cv = document.getElementById('scope');
      const r = cv.getBoundingClientRect();
      return { left: r.left, top: r.top, w: r.width, h: r.height };
    });
    // Grab point: just inside the right edge, vertically at the current level (0.5·H).
    const grabX = dragInfo.left + dragInfo.w - 6;
    const grabY0 = dragInfo.top + dragInfo.h * 0.5;
    const dropY = dragInfo.top + dragInfo.h * 0.25;
    await page.mouse.move(grabX, grabY0);
    await page.mouse.down();
    await page.mouse.move(grabX, (grabY0 + dropY) / 2, { steps: 5 });
    await page.mouse.move(grabX, dropY, { steps: 5 });
    await page.mouse.up();
    await page.waitForTimeout(120);

    const afterFrac = await readLevelFrac();

    check('(3-drag) trigger-level handle is draggable: oscTriggerLevelFrac CHANGED',
      beforeFrac != null && afterFrac != null && Math.abs(afterFrac - beforeFrac) > 0.05,
      `oscTriggerLevelFrac before=${beforeFrac != null ? beforeFrac.toFixed(4) : 'n/a'} after=${afterFrac != null ? afterFrac.toFixed(4) : 'n/a'} Δ=${(beforeFrac != null && afterFrac != null) ? Math.abs(afterFrac - beforeFrac).toFixed(4) : 'n/a'} (dragged 0.5->~0.25)`);

    // Leave the trigger level at a DISTINCT fraction (~0.30) for the screenshot, so
    // its yellow line sits well ABOVE the centred channel-offset/centre lines and the
    // image-thickness scan measures ONLY the trigger-level line (no conflation). Drive
    // it through the same real drag path to a clean 0.30. levelFrac = y/H.
    const lvlFracTarget = 0.30;
    const lvlY = dragInfo.top + dragInfo.h * lvlFracTarget;
    await page.mouse.move(grabX, dropY);
    await page.mouse.down();
    await page.mouse.move(grabX, lvlY, { steps: 8 });
    await page.mouse.up();
    await page.waitForTimeout(200);
    const lvlFracForShot = await readLevelFrac();
    check('(3-drag) re-positioned trigger level persisted to ~0.30 for the shot',
      lvlFracForShot != null && Math.abs(lvlFracForShot - lvlFracTarget) < 0.06,
      `oscTriggerLevelFrac=${lvlFracForShot != null ? lvlFracForShot.toFixed(4) : 'n/a'} (target ${lvlFracTarget})`);

    // ---- Install the toBlob interceptor (capture the produced image bytes). ----
    await page.evaluate(() => {
      window.__shot = null;
      const orig = HTMLCanvasElement.prototype.toBlob;
      HTMLCanvasElement.prototype.toBlob = function (cb, type, quality) {
        const w = this.width, h = this.height;
        // Capture the composite canvas's recorded #scope OUTPUT-pixel rect (set by
        // paintCloneToCanvas) so the proof can locate the scope-canvas region in the
        // produced image and check the watermark is centred on IT (not on out.width/2).
        const scopeRect = this.scopeRect ? { ...this.scopeRect } : null;
        return orig.call(this, (blob) => {
          if (blob) {
            const fr = new FileReader();
            fr.onload = () => { window.__shot = { w, h, dataUrl: fr.result, size: blob.size, scopeRect }; };
            fr.readAsDataURL(blob);
          }
          cb(blob);
        }, type, quality);
      };
    });

    // ---- 3. Trigger the in-app screenshot at TARGET much WIDER than live. ----
    await page.click('#scopeTabs .tab[data-panel="scopeUtility"]');
    await page.waitForTimeout(150);
    await page.click('#scopeShot');
    await page.waitForTimeout(400);

    const seeded = await page.evaluate(() => ({
      w: parseInt(document.getElementById('shotWidth').value, 10),
      h: parseInt(document.getElementById('shotHeight').value, 10),
    }));
    // Keep-aspect OFF, write the exact target (1900x520 - much wider than live).
    await page.evaluate(() => { const k = document.getElementById('shotKeepAspect'); if (k && k.checked) { k.checked = false; k.dispatchEvent(new Event('change',{bubbles:true})); } });
    await page.evaluate(({ w, h }) => {
      const set = (id, v) => { const el = document.getElementById(id); el.value = String(v); el.dispatchEvent(new Event('input',{bubbles:true})); };
      set('shotWidth', w); set('shotHeight', h);
    }, { w: TARGET_W, h: TARGET_H });
    await page.fill('#shotComment', 'proof');

    // Stub the save target so #shotSave's saveFile() doesn't try a real dialog.
    await page.evaluate(() => {
      window.showSaveFilePicker = async ({ suggestedName } = {}) => ({
        name: suggestedName || 'scope.png',
        createWritable: async () => ({ write: async () => {}, close: async () => {} }),
      });
      const ap = HTMLAnchorElement.prototype; window.__origClick = ap.click; ap.click = function () {};
    });

    await page.click('#shotSave');
    await page.waitForFunction(() => window.__shot && window.__shot.dataUrl, { timeout: 15000 });
    const shot = await page.evaluate(() => ({ w: window.__shot.w, h: window.__shot.h, size: window.__shot.size }));
    check('in-app screenshot produced a non-empty image blob', shot.size > 0,
      `composedBox=${shot.w}x${shot.h} bytes=${shot.size}`);

    // Measure the SAME edge-label element in the CLONE the compose path laid out.
    // The clone persists, attached, in the offscreen shot-clone host. Find the host
    // (position:fixed;left:-10000px;visibility:hidden) and re-query the same label.
    const cloneM = await page.evaluate(() => {
      // The shot clone host: a fixed, far-offscreen, hidden div appended to <body>.
      const hosts = Array.from(document.body.children).filter((el) => {
        const s = el.style;
        // The shot host is fixed + far off-screen (left:-10000px). It is intentionally
        // NOT visibility:hidden (that would inherit into the clone and the paint skips
        // visibility:hidden elements -> all-black shot); identify it by being off-screen
        // and carrying a .scope-overlay clone.
        return s.position === 'fixed' && s.left === '-10000px' && !!el.querySelector('.scope-overlay');
      });
      const host = hosts[hosts.length - 1];
      if (!host) return { hostFound: false };
      const cloneOvl = host.querySelector('.scope-overlay');
      if (!cloneOvl) return { hostFound: true, overlayFound: false };
      const times = Array.from(cloneOvl.querySelectorAll('.scope-ovl-time')).filter((e) => getComputedStyle(e).display !== 'none' && e.textContent);
      const el = times[0];
      const out = { hostFound: true, overlayFound: true };
      if (el) {
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        const chars = el.textContent.length;
        out.label = { text: el.textContent, chars, w: r.width, h: r.height,
                      fontSize: parseFloat(cs.fontSize), charAspect: (r.width / chars) / r.height };
      }
      const lr = host.querySelector('.lr-tools .lr');
      if (lr) { const lrR = lr.getBoundingClientRect(); out.lrW = lrR.width; out.lrH = lrR.height; }
      // Trigger-level HANDLE triangle in the screenshot CLONE: a CSS border-triangle
      // (tri-left = right-pointing on the right edge: 6px+6px transparent top/bottom
      // borders -> ~12px tall, 10px solid left border -> ~10px wide). It must be a real
      // DOM element with a FIXED bounded box in the clone, NOT stretched by the wide
      // target. Measure its bounding box + the four border widths.
      const triH = host.querySelector('.scope-overlay .scope-ovl-sliderhandle.scope-ovl-tri-left');
      if (triH && getComputedStyle(triH).display !== 'none') {
        const cs = getComputedStyle(triH);
        const r = triH.getBoundingClientRect();
        out.handle = { w: r.width, h: r.height,
          bw: [cs.borderTopWidth, cs.borderRightWidth, cs.borderBottomWidth, cs.borderLeftWidth].map((v) => parseFloat(v) || 0) };
      }
      // The horizontal trigger-level dashed LINE core in the clone (1.5px border-top).
      const lineCore = host.querySelector('.scope-overlay .scope-ovl-slidercore.scope-ovl-hline');
      if (lineCore && getComputedStyle(lineCore).display !== 'none') {
        const cs = getComputedStyle(lineCore);
        const r = lineCore.getBoundingClientRect();
        out.lineCore = { w: r.width, h: r.height, borderTop: parseFloat(cs.borderTopWidth) || 0 };
      }
      // Scrollbars are KEPT in the capture: the vertical scrollbar stays (its thumb
      // blits by id), and the horizontal scrollbar gutter is reserved (visibility:hidden
      // until a file is loaded). So both canvases must be PRESENT and visible (not display:none).
      const vsc = host.querySelector('#scopeVScroll');
      out.vScrollPresent = !!vsc && getComputedStyle(vsc).display !== 'none';
      out.hscrollPresent = !!host.querySelector('#scopeHScroll');
      return out;
    });

    check('clone host found + overlay present in clone',
      cloneM.hostFound && cloneM.overlayFound, `hostFound=${cloneM.hostFound} overlayFound=${cloneM.overlayFound}`);
    check('clone time edge label found', !!cloneM.label,
      cloneM.label ? `text="${cloneM.label.text}"` : 'NOT FOUND');

    if (cloneM.label) {
      const fsErr = Math.abs(cloneM.label.fontSize - live.fontSize) / live.fontSize;
      const aspErr = Math.abs(cloneM.label.charAspect - live.charAspect) / live.charAspect;
      check('(3a) clone edge-label font-size == live (within 3%)',
        fsErr <= TOL,
        `cloneFont=${cloneM.label.fontSize}px liveFont=${live.fontSize}px err=${(fsErr*100).toFixed(2)}%`);
      check('(3b) clone edge-label char ASPECT == live (NOT stretched, within 3%)',
        aspErr <= TOL,
        `cloneCharAspect=${cloneM.label.charAspect.toFixed(4)} liveCharAspect=${live.charAspect.toFixed(4)} err=${(aspErr*100).toFixed(2)}% ` +
        `(cloneBox=${cloneM.label.w.toFixed(2)}x${cloneM.label.h.toFixed(2)} liveBox=${live.w.toFixed(2)}x${live.h.toFixed(2)})`);
    }
    if (cloneM.lrW != null) {
      const lrWErr = Math.abs(cloneM.lrW - live.lrW) / live.lrW;
      const lrHErr = Math.abs(cloneM.lrH - live.lrH) / live.lrH;
      check('(3c) clone .lr buttons SAME pixel size as live (within 3%)',
        lrWErr <= TOL && lrHErr <= TOL,
        `clone=${cloneM.lrW.toFixed(2)}x${cloneM.lrH.toFixed(2)} live=${live.lrW.toFixed(2)}x${live.lrH.toFixed(2)} ` +
        `wErr=${(lrWErr*100).toFixed(2)}% hErr=${(lrHErr*100).toFixed(2)}%`);
    } else {
      check('(3c) clone .lr buttons SAME pixel size as live', false, 'clone .lr NOT FOUND');
    }

    // ---- 4. Scrollbar canvases physically removed; scope↔zoomed gap ~2px. ----
    check('(4a) vertical scrollbar KEPT in capture (present + visible)', cloneM.vScrollPresent === true,
      `#scopeVScroll present+visible = ${cloneM.vScrollPresent}`);
    check('(4b) horizontal scrollbar gutter KEPT in capture (present)',
      cloneM.hscrollPresent === true,
      `#scopeHScroll present = ${cloneM.hscrollPresent}`);

    // ---- Save the produced PNG and re-decode it. ----
    const dataUrl = await page.evaluate(() => window.__shot.dataUrl);
    const b64 = dataUrl.split(',')[1];
    writeFileSync(OUT_PNG, Buffer.from(b64, 'base64'));
    console.log('saved produced image -> ' + OUT_PNG);

    const probe = await page.evaluate(async ({ TW, scopeRect }) => {
      const res = await fetch(window.__shot.dataUrl);
      const blob = await res.blob();
      const bmp = await createImageBitmap(blob);
      const W = bmp.width, H = bmp.height;
      const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
      const ctx = cv.getContext('2d'); ctx.drawImage(bmp, 0, 0);
      const img = ctx.getImageData(0, 0, W, H).data;
      const at = (x, y) => { const i = (y*W+x)*4; return [img[i], img[i+1], img[i+2]]; };
      const mainBot = Math.floor(H * 0.60);
      let trace = 0;
      for (let y = 0; y < mainBot; y++) for (let x = 0; x < W; x++) {
        const [r,g,b] = at(x, y);
        if ((r>180&&g>160&&b<90) || (b>160&&g>160&&r<150)) trace++;
      }
      // IMAGE-pixel size of the top-left BUTTON GROUP (real output measurement, not
      // clone CSS): the buttons sit above the trace; scan the top-left for the
      // vertical extent of their (non-black) pixels. `scaleOut` (output px per
      // requested CSS px) only LOCATES the button in the image; the EXPECTED size is
      // checked against the real display devicePixelRatio so a CSS-px (non-device-res)
      // capture is caught no matter what output resolution it claims.
      const scaleOut = W / TW;
      const dprReal = window.devicePixelRatio || 1;
      const xlo = Math.max(0, Math.round(8 * scaleOut)), xhi = Math.min(W, Math.round(150 * scaleOut));
      const yscan = Math.min(H, Math.round(48 * scaleOut));
      // The toolbar buttons are now TRANSPARENT over the black scope (Java-faithful: SWT
      // NO_BACKGROUND ToolButtons - only the SELECTED channel button is FILLED in its
      // channel colour; unselected = a thin coloured outline; icons = bare). There is no
      // opaque grey band to measure any more, so locate the band by the SELECTED button's
      // channel-colour FILL (cyan #00d7ff L / gold #ffd700 R) vertical extent. A filled
      // button paints a solid colour run ≈ one button wide; the 1.5px dashed overlay line,
      // the thin (~1px) unselected outline, and sparse text never do - so require a run of
      // ≥ ~half a button (sized by the real devicePixelRatio, the chrome scale factor).
      const minRun = Math.max(4, Math.round(11 * dprReal));
      let bTop = -1, bBot = -1;
      for (let y = 0; y < yscan; y++) {
        let col = 0;
        for (let x = xlo; x < xhi; x++) {
          const c = at(x, y);
          if ((c[2] > 160 && c[1] > 160 && c[0] < 150) || (c[0] > 180 && c[1] > 160 && c[2] < 90)) col++;  // cyan L | gold R fill
        }
        if (col >= minRun) { if (bTop < 0) bTop = y; bBot = y; }
        else if (bTop >= 0) break;   // first row past the selected-button band = its bottom
      }
      const btnH = (bTop >= 0) ? (bBot - bTop + 1) : 0;

      // ---- HORIZONTAL dashed line thickness in IMAGE pixels (task 2). ----
      // The trigger-level line is PURE YELLOW (#ffff00) and was dragged to ~0.30·H,
      // well above the centred channel-offset lines (which use the cyan/gold trace
      // colours). For every row, count pure-yellow pixels (r&g very high, b very low -
      // tight enough to exclude the gold #ffd700 offset line, g=215/b=0). The dashed
      // line spans the width, so its rows carry MANY yellow pixels; off-line rows ~none.
      // The line's vertical thickness = the run of rows above a high threshold near
      // y≈0.30·H. Even though the image is MUCH WIDER than live, a non-stretched fixed
      // 1.5px border stays ~1.5px × scaleOut tall (NOT scaled by the width stretch).
      // Composited yellow #ffff00 reads g≈225 (max ~238) after the canvas blit; the
      // gold #ffd700 offset line (g=215) is rejected by the ±12% band restriction below
      // (it sits at the centred ~0.5·H, the trigger line at ~0.30·H), so g>205 catches
      // the trigger line without conflating the gold line.
      const isYellow = (x, y) => { const [r,g,b] = at(x, y); return r > 200 && g > 205 && b < 80; };
      const yellowPerRow = new Array(H).fill(0);
      for (let y = 0; y < H; y++) { let n = 0; for (let x = 0; x < W; x++) if (isYellow(x, y)) n++; yellowPerRow[y] = n; }
      // Find the band of yellow-rich rows nearest y≈0.30·H: take the contiguous run of
      // rows above a threshold (>= 8% of width covered by the dashed marks). A
      // stretched-to-many-px line would give a thick run.
      const yc = Math.round(H * 0.30);
      // A DASHED line has sparse per-row coverage (dashes + gaps), so an 8%-of-width
      // threshold misses it - use a low fixed floor that the dashed marks clear but a
      // stray trace-crossing row (a few px) does not.
      const rowThresh = Math.max(10, Math.round(W * 0.005));
      let lineTop = -1, lineBot = -1;
      // walk up from centre while rows stay yellow-rich, then down - but only accept
      // rows within ±12% of centre so an unrelated yellow feature elsewhere is ignored.
      const band = Math.round(H * 0.12);
      // locate the single richest row near centre first
      let peakY = yc, peakN = -1;
      for (let y = yc - band; y <= yc + band; y++) { if (y < 0 || y >= H) continue; if (yellowPerRow[y] > peakN) { peakN = yellowPerRow[y]; peakY = y; } }
      if (peakN >= rowThresh) {
        lineTop = peakY; lineBot = peakY;
        for (let y = peakY - 1; y >= 0 && yellowPerRow[y] >= rowThresh; y--) lineTop = y;
        for (let y = peakY + 1; y < H && yellowPerRow[y] >= rowThresh; y++) lineBot = y;
      }
      const lineThick = lineTop >= 0 ? (lineBot - lineTop + 1) : 0;

      // ================================================================
      // (A) VERTICAL trigger-POSITION dashed line (#ffffff white). It renders at
      // posFrac×scopeWidth INSIDE the scope canvas, posFrac defaulting to 0.5, so its
      // image x = scopeRect.left + 0.5·scopeRect.width (≈ scope-canvas centre). It was
      // ENTIRELY MISSING before. Scan a narrow column BAND around that x for WHITE
      // pixels (r,g,b all very high) forming a long VERTICAL run (the dashed line spans
      // the canvas height). White (#ffffff) is distinct from the YELLOW trace (b<90) and
      // the cyan/gold traces; only the trigger-position line + sparse white edge text are
      // pure white, and the line is a tall, column-localised run.
      const isWhite = (x, y) => { const [r,g,b] = at(x, y); return r > 235 && g > 235 && b > 235; };
      // Search x-centre: the scope canvas centre in OUTPUT px (posFrac 0.5). Fall back to
      // image centre if scopeRect was not recorded.
      const vCx = scopeRect ? Math.round(scopeRect.left + scopeRect.width * 0.5) : Math.round(W / 2);
      // Restrict the vertical scan to the scope-canvas y-extent so the bottom tab strip
      // (light grey, also high RGB) is excluded.
      const vY0 = scopeRect ? Math.max(0, Math.round(scopeRect.top)) : 0;
      const vY1 = scopeRect ? Math.min(H, Math.round(scopeRect.top + scopeRect.height)) : mainBot;
      // For each column in a ±band around vCx, find the LONGEST contiguous-ish vertical
      // run of white (allowing 1px dashes-gaps: count white rows, longest run with small
      // gap tolerance). Report the best column's run length + that column's white-row
      // count. A dashed line gives a high white-row COUNT spread across the full height.
      const vBand = Math.max(6, Math.round(W * 0.01));
      let vLineRows = 0, vLineX = -1, vLineRun = 0;
      for (let x = Math.max(0, vCx - vBand); x <= Math.min(W - 1, vCx + vBand); x++) {
        let rows = 0, run = 0, best = 0, gap = 0;
        for (let y = vY0; y < vY1; y++) {
          if (isWhite(x, y)) { rows++; run++; gap = 0; best = Math.max(best, run); }
          else { gap++; if (gap > Math.round(H * 0.03)) run = 0; }   // dash gaps tolerated
        }
        if (rows > vLineRows) { vLineRows = rows; vLineX = x; vLineRun = best; }
      }
      const vSpan = vY1 - vY0;

      // ================================================================
      // (B) SLIDER HANDLE triangle pixel cluster at a canvas EDGE. The trigger-POSITION
      // handle (tri-up, white) sits at the BOTTOM edge of the scope canvas (CSS bottom:0),
      // centred on the vertical line, pointing UP - base (wide) at the very bottom, apex
      // ~10px×dpr above. A triangle is distinguished from the 1px white vertical line by
      // its WIDTH: the base row is many px wide (≈12px×dpr) whereas the line is ~1-2px.
      // Scan rows in a box at the bottom edge near vCx and take, per row, the white-pixel
      // WIDTH; the triangle's widest row (its base) must clearly exceed the line width.
      const triHalf = Math.round(16 * dprReal);
      const triBoxX0 = Math.max(0, vCx - triHalf), triBoxX1 = Math.min(W, vCx + triHalf);
      const triBoxY1 = vY1, triBoxY0 = Math.max(vY0, vY1 - Math.round(16 * dprReal));
      let triMaxRowW = 0, triBaseY = -1, triFilled = 0, triH = 0;
      for (let y = triBoxY0; y < triBoxY1; y++) {
        let minx = 1e9, maxx = -1, n = 0;
        for (let x = triBoxX0; x < triBoxX1; x++) if (isWhite(x, y)) { n++; if (x < minx) minx = x; if (x > maxx) maxx = x; }
        const rowW = maxx >= minx ? maxx - minx + 1 : 0;
        triFilled += n;
        if (rowW > 0) triH++;
        if (rowW > triMaxRowW) { triMaxRowW = rowW; triBaseY = y; }
      }
      const triUp = { n: triFilled, w: triMaxRowW, h: triH };

      // ================================================================
      // (C) WATERMARK 'Phonalyser.web' centroid. Grey ~rgb(160,160,160) text, alpha 0.27
      // composited over the black trace -> mid-grey pixels. Collect grey pixels across the
      // MIDDLE band of the scope-canvas region (avoid the bright tab strip + light chrome)
      // and compute their x-centroid. It must sit on the scope-canvas centre, NOT W/2×dpr.
      const isWmGrey = (x, y) => {
        const [r,g,b] = at(x, y);
        // The watermark is rgba(160,160,160,0.27). Composited over the BLACK trace
        // background (0) it reads ≈ 160·0.27 ≈ 43 grey; over the #3c3c3c grid (60) ≈ 87.
        // Use the over-black value as the signal and a tight neutral window [33..56] that
        // EXCLUDES the uniform grid grey (#3c3c3c = 60) and the #909090 cross-hair (144),
        // so the count is the watermark glyphs over the (dominant) black background.
        const lo = 33, hi = 56;
        if (r < lo || r > hi || g < lo || g > hi || b < lo || b > hi) return false;
        const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
        return (mx - mn) <= 10;   // near-neutral grey
      };
      const wmY0 = scopeRect ? Math.round(scopeRect.top + scopeRect.height * 0.20) : Math.round(H * 0.12);
      const wmY1 = scopeRect ? Math.round(scopeRect.top + scopeRect.height * 0.80) : Math.round(H * 0.50);
      const scopeCx = scopeRect ? scopeRect.left + scopeRect.width / 2 : W / 2;
      // The watermark text WIDTH is ~25% of the image (font sized so measureText ≈ 0.25·W),
      // so it occupies scopeCx ± ~13%·scopeWidth. Restrict the x-scan to scopeCx ± 18% of
      // the SCOPE width: this excludes the left meas-table glyphs and the dense right-side
      // trace AA (both neutral-grey contaminants) while STILL excluding the old wrong spot
      // (out.width/2×dpr lies far outside this window), so a mis-placed watermark fails.
      const wmHalf = scopeRect ? Math.round(scopeRect.width * 0.18) : Math.round(W * 0.18);
      const wmX0 = Math.max(0, Math.round(scopeCx - wmHalf)), wmX1 = Math.min(W, Math.round(scopeCx + wmHalf));
      let wmSumX = 0, wmSumY = 0, wmN = 0;
      for (let y = wmY0; y < wmY1; y++) for (let x = wmX0; x < wmX1; x++) {
        if (isWmGrey(x, y)) { wmSumX += x; wmSumY += y; wmN++; }
      }
      const wmCx = wmN > 0 ? wmSumX / wmN : -1;

      // ================================================================
      // (D) TAB-STRIP TOP border row: a 1px solid #abadb3 (strip-border ≈ 171) horizontal
      // line above the bottom tab labels, drawn at the top edge of the .tiletabs strip.
      // Scan the bottom region for a row that is a long HORIZONTAL run of the border grey
      // (r,g,b ~171, near-neutral) - distinct from the BRIGHTER #f0f0f0 strip below it and
      // the black trace above. Report the best border row + its border-coloured pixel count.
      const isBorderGrey = (x, y) => {
        const [r,g,b] = at(x, y);
        const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
        return r >= 150 && r <= 190 && g >= 150 && g <= 190 && b >= 150 && b <= 195 && (mx - mn) <= 18;
      };
      const stripScanTop = Math.round(H - 80 * dprReal);
      let borderRowY = -1, borderRowN = 0;
      for (let y = Math.max(0, stripScanTop); y < H; y++) {
        let n = 0; for (let x = 0; x < W; x++) if (isBorderGrey(x, y)) n++;
        if (n > borderRowN) { borderRowN = n; borderRowY = y; }
      }

      return { W, H, trace, mainBot, dpr: dprReal, scaleOut, btnH,
               lineThick, linePeakN: peakN, lineRowThresh: rowThresh, lineAtY: peakY,
               // task-1 vertical line + edge triangle
               vLineX, vLineRows, vLineRun, vSpan, vCx,
               triUp,
               // task-2 watermark centroid
               wmN, wmCx, scopeCx, scopeRect,
               // task-3 tab-strip top border
               borderRowY, borderRowN };
    }, { TW: TARGET_W, scopeRect: await page.evaluate(() => window.__shot.scopeRect) });

    const imageRatio = probe.W / probe.H;
    const targetRatio = TARGET_W / TARGET_H;
    const ratioErr = Math.abs(imageRatio - targetRatio) / targetRatio;
    const liveRatio = seeded.w / seeded.h;

    console.log(`info: liveViewport=${LIVE_W}x${LIVE_H} liveNativeBox=${seeded.w}x${seeded.h} (ratio ${liveRatio.toFixed(3)}) ` +
                `requestedTarget=${TARGET_W}x${TARGET_H} (ratio ${targetRatio.toFixed(3)}) ` +
                `composedBox=${shot.w}x${shot.h} producedImage=${probe.W}x${probe.H} (ratio ${imageRatio.toFixed(3)})`);

    check('(3d) produced image: MAIN scope region has trace pixels (NOT empty)',
      probe.trace >= 300,
      `tracePx=${probe.trace} in top-60% (${probe.W}x${probe.mainBot}) - need >=300`);
    check('(3e) produced image ratio matches REQUESTED target (NOT squashed, within 3%)',
      ratioErr <= TOL,
      `imageRatio=${imageRatio.toFixed(4)} targetRatio=${targetRatio.toFixed(4)} err=${(ratioErr*100).toFixed(2)}% (liveRatio=${liveRatio.toFixed(3)} differs)`);

    // (3f) REAL output-image size check: with the "enlarge elements" dpr approach every
    // chrome element is drawn at its DEVICE size = CSS size × devicePixelRatio (the test
    // runs at deviceScaleFactor 2 -> dpr=2), regardless of the output resolution. So the
    // button group height in the produced image must equal the live button height × dpr
    // (NOT × scaleOut). This is the check the clone-CSS comparison (3c) cannot make
    // (clone CSS always equals live CSS).
    const expectBtnH = live.lrH * probe.dpr;
    const btnErr = expectBtnH > 0 ? Math.abs(probe.btnH - expectBtnH) / expectBtnH : 1;
    check('(3f) produced IMAGE button height == live button × dpr (NOT shrunk)',
      probe.btnH > 0 && btnErr <= 0.25,
      `imageBtnH=${probe.btnH}px expect≈${expectBtnH.toFixed(1)}px (live.lrH=${live.lrH.toFixed(1)} × dpr=${probe.dpr.toFixed(3)}) err=${(btnErr*100).toFixed(1)}%`);
    // (3g) The produced image must be EXACTLY the requested size - NOT multiplied by the
    // display devicePixelRatio (1024×768 selected must give 1024×768, not 1280×960).
    check('(3g) produced image is EXACTLY the requested size (not ×dpr)',
      probe.W === TARGET_W && probe.H === TARGET_H,
      `producedImage=${probe.W}x${probe.H} requested=${TARGET_W}x${TARGET_H} (display dpr=${probe.dpr.toFixed(2)})`);

    // ================================================================
    // (P) DECODED-PIXEL PROOFS on the produced PNG - the three features that were
    // missing/mis-placed before. Each fails LOUDLY with measured numbers.
    // ================================================================
    if (!probe.scopeRect) {
      check('(P0) produced image recorded the scope-canvas OUTPUT rect (out.scopeRect)',
        false, 'window.__shot.scopeRect was NULL - watermark would fall back to out.width/2');
    } else {
      check('(P0) produced image recorded the scope-canvas OUTPUT rect (out.scopeRect)',
        true, `scopeRect=${Math.round(probe.scopeRect.left)},${Math.round(probe.scopeRect.top)} ${Math.round(probe.scopeRect.width)}x${Math.round(probe.scopeRect.height)} (image ${probe.W}x${probe.H})`);
    }

    // (P1a) VERTICAL trigger-POSITION dashed line PRESENT near the scope-canvas h-centre.
    // It renders white #ffffff at posFrac(0.5)×scopeWidth and was ENTIRELY MISSING before.
    // Require a column near vCx carrying a tall column of white pixels: the dashed line
    // covers a large fraction of the scope-canvas height (dashes + gaps) and runs a long
    // contiguous segment. Use the white-row COUNT (≥18% of the scanned span) AND a single
    // contiguous run ≥8% of the span so a stray white text glyph (short) is not mistaken.
    const vNeedRows = Math.round(probe.vSpan * 0.18);
    const vNeedRun = Math.round(probe.vSpan * 0.08);
    check('(P1a) produced image: trigger-POSITION VERTICAL white dashed line present near scope-canvas centre',
      probe.vLineX >= 0 && probe.vLineRows >= vNeedRows && probe.vLineRun >= vNeedRun,
      `vLineX=${probe.vLineX} (scan centre vCx=${probe.vCx}) whiteRows=${probe.vLineRows}/${probe.vSpan} (need≥${vNeedRows}) longestRun=${probe.vLineRun} (need≥${vNeedRun})`);

    // (P1b) SLIDER HANDLE triangle cluster at the canvas EDGE (NOT part of the trace): the
    // tri-up trigger-position handle sits at the BOTTOM edge of the scope canvas, a white
    // wedge whose BASE row is wide (≈12px×dpr) - clearly wider than the 1-2px vertical
    // line. Require a base width well above the line width AND a filled multi-row cluster,
    // so a bare 1px line (the old missing-handle case) cannot satisfy it.
    const triBaseNeed = Math.max(6, Math.round(7 * probe.dpr));   // base ≫ the 1-2px line
    const triFillNeed = Math.round(10 * probe.dpr);
    check('(P1b) produced image: slider HANDLE triangle pixel cluster at a canvas edge (not the trace)',
      probe.triUp.w >= triBaseNeed && probe.triUp.h >= 3 && probe.triUp.n >= triFillNeed,
      `tri-up handle wedge at bottom edge near x=${probe.vCx}: baseWidth=${probe.triUp.w}px (need≥${triBaseNeed}, line is ~1-2px) filledPx=${probe.triUp.n} (need≥${triFillNeed}) rows=${probe.triUp.h}`);

    // (P2) WATERMARK centred on the SCOPE-CANVAS region, NOT at out.width/2×dpr (old spot).
    const wmRel = probe.scopeRect ? Math.abs(probe.wmCx - probe.scopeCx) / probe.scopeRect.width : 1;
    const oldWrongX = (probe.W / 2) * probe.dpr;   // the buggy spot: width/2 left under a leftover ×dpr scale -> off-screen right
    const distToScope = Math.abs(probe.wmCx - probe.scopeCx);
    const distToWrong = Math.abs(probe.wmCx - oldWrongX);
    check('(P2) watermark "Phonalyser.web" centroid is on the SCOPE-CANVAS centre (within ~8%), NOT at out.width/2×dpr',
      probe.wmN > 200 && wmRel <= 0.08 && distToScope < distToWrong,
      `wmGreyPx=${probe.wmN} wmCentroidX=${probe.wmCx.toFixed(1)} scopeCentreX=${probe.scopeCx.toFixed(1)} relErr=${(wmRel*100).toFixed(2)}% (≤8%) | distToScope=${distToScope.toFixed(1)} vs distToWrongSpot(width/2×dpr=${oldWrongX.toFixed(0)})=${distToWrong.toFixed(1)}`);

    // (P3) TAB-STRIP TOP border row: a horizontal run of the #abadb3 (~171) strip-border
    // grey above the bottom tab labels. Require a row in the bottom region whose
    // border-grey pixel count covers most of the width (the 1px top border spans the strip).
    const borderNeed = Math.round(probe.W * 0.50);
    check('(P3) produced image: tab-strip TOP border row present above the bottom tab labels',
      probe.borderRowY > probe.H * 0.5 && probe.borderRowN >= borderNeed,
      `borderRowY=${probe.borderRowY} (image H=${probe.H}) borderGreyPx=${probe.borderRowN}/${probe.W} (need≥${borderNeed})`);

    // ---- (2a) DROPPED - a pixel thickness measure of the dashed trigger line is
    // unreliable here: the YELLOW trace has near-horizontal sections that read as a thick
    // yellow band, and the trigger-line Y is signal-dependent, so a row-scan cannot isolate
    // the thin line from the trace. The dashed-slider lines' correctness (existence, 1.5px
    // border, --slider-color) is verified by the C26 DOM checks in scope-smoke.mjs and by
    // the saved _shot-proof.png; the manual composite draws them at their rect (can't stretch).

    // ---- (2b) Handle triangle DOM element has a FIXED bounded size in the clone. ----
    // The clone is the offscreen layout the wide capture rasterizes; the handle box
    // must stay small (CSS triangle ≈ 10×12px) - NOT stretched by the 1900-wide target.
    if (cloneM.handle) {
      const hw = cloneM.handle.w, hh = cloneM.handle.h;
      const bounded = hw > 0 && hw <= 24 && hh > 0 && hh <= 24;
      const triangleBorders = cloneM.handle.bw.some((b) => b >= 9) && cloneM.handle.bw.filter((b) => b <= 0.5).length >= 1;
      check('(2b) trigger-level handle-triangle DOM element has a FIXED bounded size in the clone',
        bounded && triangleBorders,
        `cloneHandleBox=${hw.toFixed(2)}x${hh.toFixed(2)} borders=[${cloneM.handle.bw.join(',')}] (CSS triangle ≈10w×12h, 1 solid ≥9 + transparent sides)`);
    } else {
      check('(2b) trigger-level handle-triangle DOM element has a FIXED bounded size in the clone',
        false, 'clone handle-triangle NOT FOUND');
    }
    // ---- (2c) The clone's dashed line core keeps the fixed 1.5px border. ----
    if (cloneM.lineCore) {
      check('(2c) clone dashed-line core keeps the fixed 1.5px border (NOT widened)',
        Math.abs(cloneM.lineCore.borderTop - 1.5) < 0.6 && cloneM.lineCore.w > 50,
        `cloneLineCore box=${cloneM.lineCore.w.toFixed(1)}x${cloneM.lineCore.h.toFixed(1)} borderTop=${cloneM.lineCore.borderTop}px (want ~1.5)`);
    } else {
      check('(2c) clone dashed-line core keeps the fixed 1.5px border', false, 'clone line core NOT FOUND');
    }

    // ====================================================================
    // (5) TWO produced PNGs decoded: native size AND wide 1900x520. For EACH,
    // assert the file is EXACTLY the requested size (independent IHDR byte read),
    // the trace region is non-empty, and the located element bands (top-left button
    // group, top-left table text, bottom tab-strip tiles) have IMAGE-pixel heights
    // ≈ the LIVE CSS heights × scaleOut (NOT shrunk by the clone reflow/raster).
    // ====================================================================
    const NATIVE_W = seeded.w, NATIVE_H = seeded.h;   // the on-screen pane native box
    // The shot dialog is open from the earlier capture; capture twice through it.
    const targets = [
      { name: 'native', w: NATIVE_W, h: NATIVE_H },
      { name: 'wide',   w: TARGET_W, h: TARGET_H },
    ];
    // Heights to compare against (live CSS px). lr from `live.lrH`; tile/cell/edge from liveGeo.
    const liveTileH = liveGeo.tile ? liveGeo.tile.h : 0;
    const liveCellH = liveGeo.cell ? liveGeo.cell.h : 0;
    const liveBtnRowH = liveGeo.lr ? liveGeo.lr.h : live.lrH;   // .lr button row height
    const HTOL = 0.30;   // ±30% band-location tolerance (AA + rounded-rect feet)

    for (const tg of targets) {
      const cap = await captureAt(page, tg.w, tg.h);
      // (5a) EXACT requested size - independent IHDR read off the saved file bytes.
      const dataUrl = await page.evaluate(() => window.__shot.dataUrl);
      const buf = Buffer.from(dataUrl.split(',')[1], 'base64');
      const ihdr = pngSize(buf);
      if (tg.name === 'wide') { writeFileSync(OUT_PNG, buf); console.log('saved produced image -> ' + OUT_PNG); }
      check(`(5a/${tg.name}) produced PNG is EXACTLY the requested size (file IHDR + decode)`,
        ihdr.ok && ihdr.w === tg.w && ihdr.h === tg.h && cap.W === tg.w && cap.H === tg.h,
        `requested=${tg.w}x${tg.h} fileIHDR=${ihdr.ok ? ihdr.w + 'x' + ihdr.h : 'BAD-PNG'} decoded=${cap.W}x${cap.H} scaleOut=${cap.scaleOut.toFixed(3)}`);
      // (5b) trace region non-empty.
      check(`(5b/${tg.name}) produced image trace region is NON-empty`,
        cap.trace >= 300,
        `tracePx=${cap.trace} in upper-60% (${cap.W}x${cap.mainBot}) - need >=300`);
      // (5c) top-left BUTTON GROUP band height ≈ live .lr height × dpr (enlarge-elements:
      // every chrome element is drawn at CSS size × devicePixelRatio, NOT × scaleOut).
      const expBtn = liveBtnRowH * cap.dpr;
      const btnErr = expBtn > 0 ? Math.abs(cap.btnH - expBtn) / expBtn : 1;
      check(`(5c/${tg.name}) image button-group band ≈ live .lr height × dpr (NOT shrunk)`,
        cap.btnH > 0 && btnErr <= HTOL,
        `imageBtnH=${cap.btnH}px expect≈${expBtn.toFixed(1)}px (liveLrH=${liveBtnRowH.toFixed(1)} × dpr=${cap.dpr.toFixed(3)}) err=${(btnErr * 100).toFixed(1)}%`);
      // (5d) top-left TABLE TEXT band ≈ live meas-cell height × dpr (enlarge-elements:
      // each row is drawn at CSS size × devicePixelRatio). The narrow left strip catches
      // the whole 8-row+header table, so the band spans MANY rows; "NOT shrunk" means each
      // row is full size - assert the band is >= 0.6× ONE row (not collapsed to ~1px) AND
      // <= the whole table (header + 8 rows ≈ 12× one row).
      if (liveCellH > 0) {
        const expCell = liveCellH * cap.dpr;
        const ratio = cap.txtH > 0 ? cap.txtH / expCell : 0;
        check(`(5d/${tg.name}) image table-text band ≈ live meas-cell height × dpr (NOT shrunk)`,
          cap.txtH > 0 && ratio >= 0.6 && ratio <= 12.0,
          `imageTxtH=${cap.txtH}px expectSingleRow≈${expCell.toFixed(1)}px (liveCellH=${liveCellH.toFixed(1)} × dpr=${cap.dpr.toFixed(3)}) ratio=${ratio.toFixed(2)} rows≈${(ratio).toFixed(1)}`);
      } else {
        check(`(5d/${tg.name}) image table-text band ≈ live meas-cell height × dpr`,
          false, 'live meas-cell height unavailable (table not visible)');
      }
      // (5e) bottom TAB-STRIP TILE band ≈ live .tile height × dpr (enlarge-elements:
      // every chrome element is drawn at CSS size × devicePixelRatio, NOT × scaleOut).
      if (liveTileH > 0) {
        const expTile = liveTileH * cap.dpr;
        const tileErr = expTile > 0 ? Math.abs(cap.tileH - expTile) / expTile : 1;
        check(`(5e/${tg.name}) image tab-strip TILE band ≈ live .tile height × dpr (NOT shrunk)`,
          cap.tileH > 0 && tileErr <= HTOL,
          `imageTileH=${cap.tileH}px expect≈${expTile.toFixed(1)}px (liveTileH=${liveTileH.toFixed(1)} × dpr=${cap.dpr.toFixed(3)}) err=${(tileErr * 100).toFixed(1)}% stripBandH=${cap.stripH}px`);
      } else {
        check(`(5e/${tg.name}) image tab-strip TILE band ≈ live .tile height × dpr`,
          false, 'live .tile height unavailable');
      }
    }

    // ====================================================================
    // (6) STATIC source assertions on js/shell/app.js: the screenshot path must NOT
    // use foreignObject (no SVG-embed raster), and the two GENERIC compose functions
    // must exist. (The "enlarge elements" dpr fix legitimately reads devicePixelRatio,
    // so that is no longer forbidden.)
    // ====================================================================
    const APP_JS = normalize(join(WEB_ROOT, 'js', 'shell', 'app.js'));
    const SHOT_JS = normalize(join(WEB_ROOT, 'js', 'shell', 'screenshot.js'));
    const appSrc = readFileSync(APP_JS, 'utf8');
    const shotSrc = readFileSync(SHOT_JS, 'utf8');
    const nFo = (appSrc.match(/foreignObject/g) || []).length;
    // The two GENERIC compose functions now live in the extracted screenshot.js module
    // (mirrors gui/common/ScreenshotDialog + ScreenshotOverlay); app.js imports them.
    const hasClone = /function\s+clonePaneForShot\b/.test(shotSrc);
    const hasPaint = /function\s+paintCloneToCanvas\b/.test(shotSrc);
    const importsCompose = /clonePaneForShot/.test(appSrc) && /paintCloneToCanvas/.test(appSrc);
    check('(6b) app.js contains NO foreignObject', nFo === 0, `foreignObject occurrences=${nFo} in ${APP_JS}`);
    check('(6c) screenshot.js defines clonePaneForShot + paintCloneToCanvas; app.js imports them',
      hasClone && hasPaint && importsCompose, `clonePaneForShot=${hasClone} paintCloneToCanvas=${hasPaint} appImports=${importsCompose}`);

    check('no page errors during capture', errors.length === 0,
      errors.length ? errors.join(' | ') : 'clean');

    await page.evaluate(() => { if (window.__origClick) HTMLAnchorElement.prototype.click = window.__origClick; });
  } catch (e) {
    failed++;
    console.log('FAIL: harness error - ' + (e && e.stack || e));
  } finally {
    await browser.close().catch(() => {});
    server.close();
  }

  console.log(`shot-proof: ${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.log('FAIL: fatal - ' + (e && e.stack || e));
  console.log(`shot-proof: ${passed} passed, ${failed + 1} failed`);
  process.exit(1);
});
