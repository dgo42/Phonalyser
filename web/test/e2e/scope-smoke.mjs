// Real end-to-end smoke test for the Phonalyser web scope.
//
// This drives a REAL headless Chromium (Playwright) against the actual web/ app,
// served over HTTP by a tiny static file server. It exists because pure code
// audits keep "passing" while the rendered scope is broken - only a real browser
// rendering the canvas + running the audio/render loop can catch:
//   - uncaught console/page errors at load,
//   - a blank scope canvas (no trace),
//   - the trigger-mode-switch wipe (canvas goes blank after AUTO->NORMAL/SINGLE),
//   - the measurement-table freeze (rows stop updating),
//   - unequal scope tab-panel heights (layout regression).
//
// Run:  node test/e2e/scope-smoke.mjs   (from web/)
// Exits non-zero if any assertion fails. Prints 'e2e: N passed, M failed'.

import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));   // web/

// ---- MIME map (only what the app loads) -----------------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.wasm': 'application/wasm',
  '.properties': 'text/plain; charset=utf-8',
};

// ---- tiny static server ----------------------------------------------------
function startServer() {
  const server = http.createServer((req, res) => {
    let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (urlPath === '/') urlPath = '/index.html';
    // Resolve safely under WEB_ROOT (no path traversal).
    const filePath = normalize(join(WEB_ROOT, urlPath));
    if (!filePath.startsWith(WEB_ROOT)) { res.writeHead(403); res.end('forbidden'); return; }
    if (!existsSync(filePath) || !statSync(filePath).isFile()) {
      res.writeHead(404); res.end('not found: ' + urlPath); return;
    }
    const mime = MIME[extname(filePath).toLowerCase()] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': mime, 'Cache-Control': 'no-store' });
    createReadStream(filePath).pipe(res);
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

// ---- assertion bookkeeping -------------------------------------------------
let passed = 0, failed = 0;
function check(name, ok, detail) {
  if (ok) { passed++; console.log(`PASS: ${name}` + (detail ? ` - ${detail}` : '')); }
  else { failed++; console.log(`FAIL: ${name}` + (detail ? ` - ${detail}` : '')); }
}

// Read the live #scope canvas pixels and return distinct-colour count + variance.
// Runs in page context. Returns {colours, variance, w, h}.
async function canvasStats(page) {
  return page.evaluate(() => {
    const c = document.getElementById('scope');
    if (!c) return { error: 'no #scope canvas' };
    const ctx = c.getContext('2d');
    const w = c.width, h = c.height;
    const img = ctx.getImageData(0, 0, w, h).data;
    const seen = new Set();
    let sum = 0, sumSq = 0, n = 0;
    // Sample every 4th pixel to keep this cheap on a 1200x240 canvas.
    for (let i = 0; i < img.length; i += 16) {
      const r = img[i], g = img[i + 1], b = img[i + 2];
      seen.add((r << 16) | (g << 8) | b);
      const lum = (r + g + b) / 3;
      sum += lum; sumSq += lum * lum; n++;
    }
    const mean = sum / n;
    const variance = sumSq / n - mean * mean;
    return { colours: seen.size, variance, w, h };
  });
}

// Read REAL #scope pixels along each of the three on-canvas dashed slider lines
// (C26) and report the bright-colour counts + the max sampled component so the
// harness can assert BRIGHT YELLOW / WHITE / channel-colour are drawn (FAIL if
// dark). The three lines are first separated to distinct fracs by the caller
// (drag) so they don't occlude one another: trigger-level -> levelFrac, trigger-
// position -> posFrac, channel-offset stays at offsetFrac. Each line spans its
// whole row/column, so we scan the full row/col band ±1 px (the 2px bright core).
async function dashedLineColours(page, levelFrac, posFrac, offsetFrac) {
  return page.evaluate(({ levelFrac, posFrac, offsetFrac }) => {
    const c = document.getElementById('scope');
    if (!c) return { error: 'no #scope canvas' };
    const ctx = c.getContext('2d');
    const W = c.width, H = c.height;
    const img = ctx.getImageData(0, 0, W, H).data;
    const px = (x, y) => { const i = (y * W + x) * 4; return [img[i], img[i + 1], img[i + 2]]; };
    const levelY = Math.round(levelFrac * H);
    const posX = Math.round(posFrac * W);
    const offsetY = Math.round(offsetFrac * H);

    // ---- Trigger LEVEL (#ffff00): full-width horizontal dashed line at levelY.
    // Count bright-yellow (R>200,G>200,B<80); track the max component sampled.
    let yellow = 0, levelMax = 0;
    for (let dy = -1; dy <= 1; dy++) {
      const y = levelY + dy; if (y < 0 || y >= H) continue;
      for (let x = 0; x < W; x++) {
        const [r, g, b] = px(x, y);
        levelMax = Math.max(levelMax, r, g, b);
        if (r > 200 && g > 200 && b < 80) yellow++;
      }
    }

    // ---- Trigger POSITION (#ffffff): full-height vertical dashed cursor at posX.
    // Count white (R,G,B all >220); track the min-of-RGB max (white needs all hi).
    let white = 0, posMax = 0;
    for (let dx = -1; dx <= 1; dx++) {
      const x = posX + dx; if (x < 0 || x >= W) continue;
      for (let y = 0; y < H; y++) {
        const [r, g, b] = px(x, y);
        posMax = Math.max(posMax, Math.min(r, g, b));
        if (r > 220 && g > 220 && b > 220) white++;
      }
    }

    // ---- Channel OFFSET line (active = L, cyan 0x00d7ff = 0,215,255): dashed
    // horizontal line at offsetY spanning x in [SLIDER_TRI_LONG+4 .. W]. Look for
    // the cyan signature (B>180,G>180,R<130) - distinct from the yellow level
    // line. Track the max channel-component so we FAIL on a dark/olive read
    // (task: max channel-component must be >180).
    // The coloured core is 1.5 px (C26b) so the cyan anti-aliases - its peak no
    // longer hits the crisp-2px thresholds (b,g>180,r<130). Use cyan-DOMINANT
    // thresholds that still reject grey (r==g==b ~192 fails r<150) and olive
    // (low b): b>160 && g>160 && r<150, scanning a 2-px band for the AA spread.
    let chan = 0, chanMax = 0;
    for (let dy = -2; dy <= 2; dy++) {
      const y = offsetY + dy; if (y < 0 || y >= H) continue;
      for (let x = 14; x < W; x++) {     // SLIDER_TRI_LONG (10) + 4
        const [r, g, b] = px(x, y);
        if (b > 160 && g > 160 && r < 150) { chan++; chanMax = Math.max(chanMax, b, g); }
      }
    }

    return { W, H, levelY, posX, offsetY, yellow, levelMax, white, posMax, chan, chanMax };
  }, { levelFrac, posFrac, offsetFrac });
}

// Drag an on-canvas slider handle to a target CSS fraction of the canvas. The
// canvas maps client-pixel offsets straight to canvas coords (devicePixelRatio=1
// in headless), and _updateSliderFromMouse writes frac = my/H (level/offset) or
// mx/W (position). Handles: trigger-level -> right edge at mid-height; trigger-
// position -> bottom edge at mid-width.
async function dragSlider(page, which, targetFrac) {
  const r = await page.evaluate(() => {
    const c = document.getElementById('scope');
    const b = c.getBoundingClientRect();
    return { left: b.left, top: b.top, w: b.width, h: b.height };
  });
  if (which === 'LEVEL') {
    await page.mouse.move(r.left + r.w - 6, r.top + r.h * 0.5);
    await page.mouse.down();
    await page.mouse.move(r.left + r.w - 6, r.top + r.h * targetFrac, { steps: 5 });
    await page.mouse.up();
  } else if (which === 'POSITION') {
    await page.mouse.move(r.left + r.w * 0.5, r.top + r.h - 6);
    await page.mouse.down();
    await page.mouse.move(r.left + r.w * targetFrac, r.top + r.h - 6, { steps: 5 });
    await page.mouse.up();
  }
  await page.waitForTimeout(500);
}

async function main() {
  const { chromium } = await import('playwright');
  const { server, port } = await startServer();
  const origin = `http://127.0.0.1:${port}`;
  console.log(`server: ${origin} (root ${WEB_ROOT})`);

  // Chromium's built-in fake audio device delivers pure SILENCE on this platform,
  // which makes the scope trace flat and the measurement values a constant 0 -
  // untestable. Feed a real, amplitude-modulated tone via
  // --use-file-for-fake-audio-capture so the scope sees a changing waveform.
  const fakeWav = join(__dirname, 'fake-audio.wav');
  makeFakeAudioWav(fakeWav);
  console.log(`fake audio: ${fakeWav}`);

  const browser = await chromium.launch({
    headless: true,
    args: [
      '--use-fake-device-for-media-stream',
      '--use-fake-ui-for-media-stream',
      '--autoplay-policy=no-user-gesture-required',
      `--use-file-for-fake-audio-capture=${fakeWav}`,
      // Expose a real (quantized) performance.memory.usedJSHeapSize so the
      // heap-growth assertion can sample actual heap usage, not the bucketed
      // default; the test still falls back to history/node-count if it's absent.
      '--enable-precise-memory-info',
    ],
  });

  const context = await browser.newContext({ permissions: ['microphone'] });
  await context.grantPermissions(['microphone'], { origin });
  const page = await context.newPage();

  // Collect uncaught errors. Ignore benign favicon/sw 404s and network noise that
  // are not application JS faults.
  const errors = [];
  // Separately collect "Missing i18n key" warnings (C31): t() does console.warn
  // for an unresolved key, so a duplicate/invented web.* key surfaces here.
  const missingKeys = [];
  page.on('console', (msg) => {
    const t = msg.text();
    if (msg.type() === 'warning' && /Missing i18n key/i.test(t)) { missingKeys.push(t); return; }
    if (msg.type() === 'error') {
      if (/favicon|service worker|Failed to load resource/i.test(t)) return;
      errors.push('console.error: ' + t);
    }
  });
  page.on('pageerror', (err) => errors.push('pageerror: ' + (err && err.stack || err)));

  try {
    await page.goto(origin + '/index.html', { waitUntil: 'load' });
    // Wait for the app to wire up: the scope record LED must exist and i18n applied.
    await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });
    await page.waitForFunction(() => {
      const btn = document.querySelector('#scopeTabs .tab[data-panel="scopeTrig"]');
      return !!btn && !!document.getElementById('scope');
    }, { timeout: 15000 });
    // Give init()'s async device scan a beat to settle.
    await page.waitForTimeout(500);

    // --- start recording: click the scope record LED -----------------------
    await page.click('.scope-pane .led-btn');
    // Wait for the fake media stream to fill the ring and the render loop to draw.
    await page.waitForTimeout(1500);

    // ===== ASSERT 1: no console / page errors at/after load =================
    check('no console/page errors', errors.length === 0, errors.length ? errors.join(' | ') : 'clean');

    // confirm recording actually engaged (LED has .rec) - diagnostic, not fatal.
    const recOn = await page.evaluate(() =>
      !!document.querySelector('.scope-pane .led-btn.rec'));
    console.log(`info: scope LED recording = ${recOn}`);

    // ===== ASSERT 2: scope canvas is NOT blank (trace present) =============
    const s2 = await canvasStats(page);
    const notBlank = (st) => st && !st.error && st.colours > 1 && st.variance > 1;
    check('scope canvas not blank (initial/AUTO)', notBlank(s2),
      s2.error || `colours=${s2.colours} var=${s2.variance.toFixed(2)} ${s2.w}x${s2.h}`);

    // ===== ASSERT 2b: no unbounded heap / DOM growth while recording =======
    // The scope is recording and free-running in AUTO; the render loop runs
    // ~60 fps and the measurement table + rolling stats history update every
    // frame. Sample the heap (precise) + the #scopeMeasTable tbody node count at
    // t≈1s and again ≈5s later. The fixes make the measurement-table reuse a fixed
    // set of nodes (constant tbody node count) and bound the rolling history on
    // push, so neither the node count nor the heap should keep climbing. FAIL if
    // the heap grows by more than HEAP_BUDGET or the tbody node count increases.
    const HEAP_BUDGET_BYTES = 50 * 1024 * 1024;   // 50 MB
    const measSample = () => page.evaluate(() => {
      const t = document.getElementById('scopeMeasTable');
      const nodes = t ? t.querySelectorAll('tbody *').length : 0;
      const heap = (performance.memory && performance.memory.usedJSHeapSize) || 0;
      return { nodes, heap };
    });
    await page.waitForTimeout(1000);
    const heap1 = await measSample();
    await page.waitForTimeout(5000);   // five more seconds of free-running capture
    const heap2 = await measSample();
    const heapGrowth = heap2.heap - heap1.heap;
    const nodeGrowth = heap2.nodes - heap1.nodes;
    check('scope measurement-table tbody node count does not grow (t=1s -> t=6s)',
      heap2.nodes > 0 && nodeGrowth <= 0,
      `nodes ${heap1.nodes} -> ${heap2.nodes} (Δ${nodeGrowth})`);
    // performance.memory may be 0 if the flag didn't take - only assert when real.
    const heapKnown = heap1.heap > 0 && heap2.heap > 0;
    check('scope heap does not grow beyond budget while recording (t=1s -> t=6s)',
      !heapKnown || heapGrowth < HEAP_BUDGET_BYTES,
      heapKnown ? `heap ${(heap1.heap / 1e6).toFixed(1)}MB -> ${(heap2.heap / 1e6).toFixed(1)}MB (Δ${(heapGrowth / 1e6).toFixed(1)}MB, budget ${(HEAP_BUDGET_BYTES / 1e6)}MB)`
                : 'performance.memory unavailable (skipped heap check)');

    // ===== ASSERT 3: trigger mode switch does not wipe the canvas ==========
    // Mode buttons live in #scopeTrigMode with data-value AUTO/NORMAL/SINGLE.
    // Need the Trigger tab panel shown to click them.
    await page.click('#scopeTabs .tab[data-panel="scopeTrig"]');
    await page.waitForTimeout(150);

    for (const mode of ['NORMAL', 'SINGLE']) {
      await page.click(`#scopeTrigMode .sq-toggle[data-value="${mode}"]`);
      // let the render loop run after the mode change so any wipe would manifest
      await page.waitForTimeout(900);
      const st = await canvasStats(page);
      check(`scope canvas not blank after trigger Mode=${mode}`, notBlank(st),
        st.error || `colours=${st.colours} var=${st.variance.toFixed(2)}`);
    }
    // Restore AUTO so the measurement-freeze test gets a free-running trace.
    await page.click('#scopeTrigMode .sq-toggle[data-value="AUTO"]');
    await page.waitForTimeout(300);

    // ===== ASSERT 4: measurement table updates over ~1s ====================
    // oscShowMeasurementTable defaults ON, so once recording started the table
    // un-hides itself (renderMeasurementTable is signal-gated, not toggle-gated).
    // We do NOT click #scopeTableToggle - that would turn it OFF. If it is not
    // visible here, ensure it is on via the toggle as a fallback.
    const tableOnNow = await page.evaluate(() => {
      const t = document.getElementById('scopeMeasTable');
      return t && !t.hidden;
    });
    if (!tableOnNow) {
      await page.click('#scopeTableToggle');   // fallback: turn it on
      await page.waitForTimeout(400);
    }

    const readMeasNums = () => page.evaluate(() => {
      const cells = Array.from(
        document.querySelectorAll('#scopeMeasTable tbody td.mt-cur'));
      return cells.map((td) => td.textContent.trim());
    });

    const visible = await page.evaluate(() => {
      const t = document.getElementById('scopeMeasTable');
      return !!t && !t.hidden && t.querySelectorAll('tbody tr').length > 0;
    });

    if (!visible) {
      check('measurement table has numeric rows', false, 'table hidden or empty after toggle');
    } else {
      const first = await readMeasNums();
      const hasNumeric = first.some((s) => /[0-9]/.test(s));
      check('measurement table has numeric rows', hasNumeric,
        `rows=${first.length} sample=[${first.slice(0, 3).join(', ')}]`);

      // Sample again ~1s later - values must change (catches the freeze) even
      // with no fresh trigger event.
      await page.waitForTimeout(1100);
      const second = await readMeasNums();
      let changed = false;
      const n = Math.min(first.length, second.length);
      for (let i = 0; i < n; i++) { if (first[i] !== second[i]) { changed = true; break; } }
      check('measurement table values change over ~1s', changed,
        changed ? 'updating' : `frozen: [${first.slice(0, 4).join(', ')}] == [${second.slice(0, 4).join(', ')}]`);
    }

    // ===== ASSERT 4b: reset-stats keeps the table (C27 guard) ==============
    // Clicking reset-statistics must ONLY restart the accumulated avg/min/max/σ
    // from the current frame - it must NOT blank the whole table. Assert the table
    // still shows its 8 numeric rows IMMEDIATELY after the click (no empty flash),
    // matching Java (stats cleared, current value still shown).
    const resetVisible = await page.evaluate(() => {
      const b = document.getElementById('scopeStatsReset');
      return !!b && b.offsetParent !== null;
    });
    if (!resetVisible) {
      check('reset-stats keeps table populated (C27)', false, '#scopeStatsReset not visible');
    } else {
      await page.click('#scopeStatsReset');
      // Read IMMEDIATELY (no render-loop wait) - the table must not flash empty.
      const afterReset = await page.evaluate(() => {
        const t = document.getElementById('scopeMeasTable');
        if (!t || t.hidden) return { rows: 0, numeric: 0 };
        const rows = t.querySelectorAll('tbody tr').length;
        const cur = Array.from(t.querySelectorAll('tbody td.mt-cur'))
          .map((td) => td.textContent.trim());
        const numeric = cur.filter((s) => /[0-9]/.test(s)).length;
        return { rows, numeric };
      });
      check('reset-stats keeps 8 numeric rows (C27, no blank flash)',
        afterReset.rows === 8 && afterReset.numeric === 8,
        `rows=${afterReset.rows} numericCur=${afterReset.numeric}`);
    }

    // ===== ASSERT 5: scope tab-panel heights are equal =====================
    // Open each tab and record its panel's rendered height; assert all equal
    // within a few px (the CSS reserved-height contract).
    const panels = ['scopeLeft', 'scopeRight', 'scopeHoriz', 'scopeTrig',
      'scopePresets', 'scopeUtility', 'scopeSavePanel', 'scopeLoadPanel'];
    const heights = [];
    for (const p of panels) {
      await page.click(`#scopeTabs .tab[data-panel="${p}"]`);
      await page.waitForTimeout(80);
      const h = await page.evaluate((id) => {
        const el = document.getElementById(id);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return Math.round(r.height);
      }, p);
      heights.push({ p, h });
    }
    const valid = heights.filter((x) => x.h != null && x.h > 0);
    const hs = valid.map((x) => x.h);
    const spread = hs.length ? Math.max(...hs) - Math.min(...hs) : Infinity;
    check('scope tab-panel heights equal (within 4px)', valid.length === panels.length && spread <= 4,
      `spread=${isFinite(spread) ? spread : 'n/a'}px ` +
      valid.map((x) => `${x.p}=${x.h}`).join(' '));

    // ===== ASSERT 6 (C26): dashed-slider lines are FIXED-SIZE DOM overlays =====
    // The trigger-level / position / channel-offset dashed lines + triangle handles
    // moved OFF the canvas into .scope-overlay so a screenshot at any target size
    // keeps them 1.5px (canvas scales, overlays don't). Verify the DOM slider cores
    // exist, are visible, carry the right --slider-color and a ~1.5px dashed border,
    // and that a triangle handle is a fixed-size DOM element. (Their pixel thickness
    // in the produced screenshot is separately proven by shot-proof.mjs.)
    await page.click('#scopeTabs .tab[data-panel="scopeTrig"]');
    await page.waitForTimeout(120);
    await dragSlider(page, 'LEVEL', 0.25);
    await dragSlider(page, 'POSITION', 0.25);
    const sl = await page.evaluate(() => {
      const ovl = document.querySelector('.scope-pane .canvas-wrap .scope-overlay');
      const pick = (sel) => {
        const els = ovl ? Array.from(ovl.querySelectorAll(sel)).filter((e) => getComputedStyle(e).display !== 'none') : [];
        const e = els[0]; if (!e) return null;
        const cs = getComputedStyle(e); const r = e.getBoundingClientRect();
        return { color: (cs.getPropertyValue('--slider-color') || '').trim().toLowerCase(),
                 borderTop: parseFloat(cs.borderTopWidth) || 0, borderLeft: parseFloat(cs.borderLeftWidth) || 0,
                 w: r.width, h: r.height };
      };
      return { overlay: !!ovl,
        level: pick('.scope-ovl-slidercore.scope-ovl-hline'),
        pos: pick('.scope-ovl-slidercore.scope-ovl-vline'),
        handle: pick('.scope-ovl-sliderhandle') };
    });
    check('C26 trigger-level dashed line is a DOM overlay (YELLOW, ~1.5px core)',
      !!sl.level && sl.level.w > 0 && Math.abs(sl.level.borderTop - 1.5) <= 1 && /f{2}ff00|f{2}f0|255/.test(sl.level.color),
      sl.level ? `color=${sl.level.color} borderTop=${sl.level.borderTop}px box=${sl.level.w.toFixed(0)}x${sl.level.h.toFixed(1)}` : 'NOT FOUND');
    check('C26 trigger-position dashed line is a DOM overlay (WHITE, ~1.5px core)',
      !!sl.pos && sl.pos.h > 0 && Math.abs(sl.pos.borderLeft - 1.5) <= 1 && /f{6}|255/.test(sl.pos.color),
      sl.pos ? `color=${sl.pos.color} borderLeft=${sl.pos.borderLeft}px box=${sl.pos.w.toFixed(1)}x${sl.pos.h.toFixed(0)}` : 'NOT FOUND');
    check('C26 slider handle is a fixed-size DOM triangle',
      !!sl.handle && sl.handle.w > 0 && sl.handle.h > 0,
      sl.handle ? `box=${sl.handle.w.toFixed(1)}x${sl.handle.h.toFixed(1)}` : 'NOT FOUND');

    // ===== ASSERT 7 (C7/C21): preset Save/Load/Delete enablement ==========
    // Open the Presets tab. Empty name -> Load+Delete DISABLED. Seed a preset via
    // Save, then typing that existing name -> Load+Delete ENABLED. A brand-new
    // name -> Save ENABLED.
    await page.click('#scopeTabs .tab[data-panel="scopePresets"]');
    await page.waitForTimeout(120);

    const presetState = () => page.evaluate(() => {
      const dis = (id) => { const b = document.getElementById(id); return !!b && b.disabled; };
      return { save: dis('scopePresetSave'), load: dis('scopePresetLoad'), del: dis('scopePresetDelete') };
    });
    const setPresetName = async (v) => {
      await page.fill('#scopePresetName', v);
      // refreshOscPresetButtons fires on 'input'; page.fill dispatches it.
      await page.waitForTimeout(60);
    };

    // (a) empty name -> Load + Delete disabled.
    await setPresetName('');
    const stEmpty = await presetState();
    check('C7 empty preset name disables Load + Delete',
      stEmpty.load === true && stEmpty.del === true,
      `save=${!stEmpty.save?'EN':'DIS'} load=${!stEmpty.load?'EN':'DIS'} del=${!stEmpty.del?'EN':'DIS'}`);

    // (b) brand-new name -> Save enabled.
    const newName = 'e2e-new-' + Date.now();
    await setPresetName(newName);
    const stNew = await presetState();
    check('C21 brand-new preset name enables Save',
      stNew.save === false,
      `save=${!stNew.save?'EN':'DIS'} load=${!stNew.load?'EN':'DIS'} del=${!stNew.del?'EN':'DIS'}`);

    // (c) seed an existing preset via the Save button, then re-type its name ->
    // Load + Delete enabled (existing name).
    const seedName = 'e2e-seed-' + Date.now();
    await setPresetName(seedName);
    await page.click('#scopePresetSave');
    await page.waitForTimeout(120);
    await setPresetName('');               // clear, then re-enter the saved name
    await setPresetName(seedName);
    const stExisting = await presetState();
    check('C7 existing preset name enables Load + Delete',
      stExisting.load === false && stExisting.del === false,
      `save=${!stExisting.save?'EN':'DIS'} load=${!stExisting.load?'EN':'DIS'} del=${!stExisting.del?'EN':'DIS'}`);

    // ===== ASSERT 8 (C20): screenshot dialog controls ====================
    // Open the Utility tab, click the camera button, assert the modal opens and
    // carries the expected control set (comment input, format control, width +
    // height inputs, Copy-to-clipboard, Save). Count them; FAIL if any missing.
    await page.click('#scopeTabs .tab[data-panel="scopeUtility"]');
    await page.waitForTimeout(120);
    await page.click('#scopeShot');
    await page.waitForTimeout(400);   // bootstrap modal fade-in
    const shot = await page.evaluate(() => {
      const modal = document.getElementById('shotModal');
      const open = !!modal && modal.classList.contains('show');
      const has = (sel) => !!document.querySelector(sel);
      const controls = {
        comment: has('#shotComment'),
        format: has('#shotFormat'),
        width: has('#shotWidth'),
        height: has('#shotHeight'),
        copy: has('#shotCopy'),
        save: has('#shotSave'),
      };
      const present = Object.values(controls).filter(Boolean).length;
      return { open, controls, present, total: Object.keys(controls).length };
    });
    const missing = Object.entries(shot.controls).filter(([, v]) => !v).map(([k]) => k);
    check('C20 screenshot dialog opens with all expected controls',
      shot.open && shot.present === shot.total,
      `open=${shot.open} controls=${shot.present}/${shot.total}` +
      (missing.length ? ` missing=[${missing.join(',')}]` : ''));
    // Close the modal so its backdrop doesn't intercept later clicks. Click the
    // dismiss button via Playwright (real user gesture), then guarantee teardown
    // by force-stripping the modal/backdrop if the fade transition stalls in
    // headless (transitionend can fail to fire).
    await page.click('#shotModal [data-bs-dismiss="modal"]');
    await page.waitForTimeout(400);
    await page.evaluate(() => {
      const el = document.getElementById('shotModal');
      if (el && el.classList.contains('show')) {
        try {
          const inst = window.bootstrap.Modal.getOrCreateInstance(el);
          inst._isTransitioning = false; inst.hide();
        } catch (e) { /* fall through to manual strip */ }
      }
      // Manual strip - clears whatever is left so nothing intercepts clicks.
      if (el) { el.classList.remove('show'); el.style.display = 'none'; el.setAttribute('aria-hidden', 'true'); }
      document.querySelectorAll('.modal-backdrop').forEach((b) => b.remove());
      document.body.classList.remove('modal-open');
      document.body.style.removeProperty('overflow');
      document.body.style.removeProperty('padding-right');
    });
    await page.waitForFunction(
      () => { const e = document.getElementById('shotModal'); return !e || !e.classList.contains('show'); },
      { timeout: 5000 });
    await page.waitForTimeout(150);

    // ===== ASSERT 9 (C22): Save-to has a file picker, NO format select =====
    // Open the Save-to tab; assert a file-picker control exists (input[type=file]
    // OR a Browse button) and that there is NO standalone format <select> - the
    // format comes from the chosen file's extension.
    await page.click('#scopeTabs .tab[data-panel="scopeSavePanel"]');
    await page.waitForTimeout(120);
    const saveTo = await page.evaluate(() => {
      const panel = document.getElementById('scopeSavePanel');
      if (!panel) return { error: 'no #scopeSavePanel' };
      const fileInput = !!panel.querySelector('input[type=file]');
      const browseBtn = !!document.getElementById('scopeSaveBrowse');
      const selects = panel.querySelectorAll('select').length;
      return { fileInput, browseBtn, selects };
    });
    check('C22 Save-to has a file picker (input[type=file] or Browse)',
      !saveTo.error && (saveTo.fileInput || saveTo.browseBtn),
      saveTo.error || `fileInput=${saveTo.fileInput} browseBtn=${saveTo.browseBtn}`);
    check('C22 Save-to has NO standalone format <select>',
      !saveTo.error && saveTo.selects === 0,
      saveTo.error || `formatSelects=${saveTo.selects}`);

    // ===== ASSERT 10 (#2): TWO-button save - Choose target, then Save silently ==
    // The save flow is now two buttons: Browse ("Choose target") picks + stores the
    // destination handle and enables Save; Save writes to that stored target WITHOUT
    // re-prompting. Save stays DISABLED until a target is chosen. Recording is still
    // on so latestScope is populated. Stub showSaveFilePicker so pickSaveTarget yields
    // a {name, handle} whose createWritable captures the written bytes - the REAL path
    // (Browse -> pickSaveTarget -> [Save] -> encode -> writeToTarget) runs end to end.
    await page.evaluate(() => {
      window.__savedBlobs = [];
      // A real handle so writeToTarget's handle branch (not the anchor fallback) runs
      // and captures the bytes; both showSaveFilePicker (pickSaveTarget) return it.
      const mkHandle = (name) => ({
        name,
        createWritable: async () => ({
          write: async (blob) => {
            const size = blob && blob.size != null ? blob.size
              : (blob && blob.byteLength != null ? blob.byteLength : 0);
            window.__savedBlobs.push(size);
          },
          close: async () => {},
        }),
      });
      window.showSaveFilePicker = async ({ suggestedName } = {}) => mkHandle(suggestedName || 'scope.wav');
      // In case any path falls back to <a download>, capture that blob too and
      // swallow the navigation.
      const orig = URL.createObjectURL.bind(URL);
      URL.createObjectURL = (b) => {
        try { if (b instanceof Blob) window.__savedBlobs.push(b.size); } catch (e) { /* ignore */ }
        return orig(b);
      };
      const ap = HTMLAnchorElement.prototype;
      window.__origAnchorClick = ap.click;
      ap.click = function () { /* swallow the download navigation */ };
    });
    // Save is DISABLED before a target is chosen (two-button flow: no silent target).
    const saveDisabledBefore = await page.evaluate(() =>
      !!document.getElementById('scopeSaveGo').disabled);
    check('#2 Save is disabled until a target is chosen',
      saveDisabledBefore === true, `disabledBefore=${saveDisabledBefore}`);
    // Choose target (Browse) - stores the handle + enables Save; writes NOTHING yet.
    await page.click('#scopeSaveBrowse');
    await page.waitForTimeout(200);
    const afterBrowse = await page.evaluate(() => ({
      enabled: !document.getElementById('scopeSaveGo').disabled,
      blobs: (window.__savedBlobs || []).length,
    }));
    check('#2 Choosing a target enables Save and writes nothing yet',
      afterBrowse.enabled === true && afterBrowse.blobs === 0,
      `saveEnabled=${afterBrowse.enabled} blobsAfterBrowse=${afterBrowse.blobs}`);
    // Save writes to the already-chosen target silently (no second picker prompt).
    await page.click('#scopeSaveGo');
    await page.waitForTimeout(400);
    const saveBlobs = await page.evaluate(() => window.__savedBlobs || []);
    check('#2 Save writes a non-empty file blob to the chosen target (no re-prompt)',
      saveBlobs.length > 0 && saveBlobs[0] > 44,
      `blobs=${saveBlobs.length} firstSize=${saveBlobs[0] || 0}`);

    // ===== ASSERT 11 (C20b): screenshot captures the FULL pane (collapsed tabs) =
    // The composed shot stacks the scope canvas + zoomed canvas + the collapsed
    // tile-tab header strip, so the native shot height EXCEEDS the bare scope
    // render height. C20d: the shot renders into an OFFSCREEN clone at a FIXED
    // native size (SHOT_SCOPE_H = 240), decoupled from the live #scope canvas's
    // responsive size - so compare against that fixed scope-portion height, not
    // the live canvas. Open Utility, screenshot, Save, and read the produced blob.
    const SHOT_SCOPE_NATIVE_H = 240;
    await page.click('#scopeTabs .tab[data-panel="scopeUtility"]');
    await page.waitForTimeout(120);
    await page.evaluate(() => { window.__savedBlobs = []; });
    await page.click('#scopeShot');
    await page.waitForTimeout(400);
    const shotNative = await page.evaluate(() => ({
      w: parseInt(document.getElementById('shotWidth').value, 10),
      h: parseInt(document.getElementById('shotHeight').value, 10),
      canvasH: document.getElementById('scope').height,
    }));
    check('C20b screenshot native height exceeds the bare scope render (full pane)',
      shotNative.h > SHOT_SCOPE_NATIVE_H,
      `shotH=${shotNative.h} bareScopeNativeH=${SHOT_SCOPE_NATIVE_H} liveCanvasH=${shotNative.canvasH} shotW=${shotNative.w}`);
    await page.fill('#shotComment', 'e2e-shot');
    await page.click('#shotSave');
    await page.waitForTimeout(400);
    const shotBlobs = await page.evaluate(() => window.__savedBlobs || []);
    check('C20 screenshot Save produces a non-empty image blob (no throw)',
      shotBlobs.length > 0 && shotBlobs[0] > 0,
      `blobs=${shotBlobs.length} firstSize=${shotBlobs[0] || 0}`);
    await page.evaluate(() => {
      if (window.__origAnchorClick) HTMLAnchorElement.prototype.click = window.__origAnchorClick;
    });

    // ===== ASSERT 12 (C28 / C28b): deleting a preset confirms via a BOOTSTRAP modal
    // Seed a preset, then click Delete. The first click must raise the shared
    // Bootstrap confirm modal (#confirmModal), NOT a native window.confirm: clicking
    // its Cancel must NOT remove the preset (still listed in the dropdown menu);
    // clicking OK removes it. Preset presence is read from the #scopePresetMenu list
    // the app rebuilds after each mutation.
    await page.click('#scopeTabs .tab[data-panel="scopePresets"]');
    await page.waitForTimeout(120);
    const delName = 'e2e-del-' + Date.now();
    const presetListed = (n) => page.evaluate((name) =>
      Array.from(document.querySelectorAll('#scopePresetMenu .dropdown-item'))
        .some((b) => b.textContent.trim() === name), n);
    const confirmShown = () => page.evaluate(() => {
      const el = document.getElementById('confirmModal');
      return !!el && el.classList.contains('show');
    });
    await page.fill('#scopePresetName', delName);
    await page.waitForTimeout(60);
    await page.click('#scopePresetSave');
    await page.waitForTimeout(120);
    const seededOk = await presetListed(delName);
    await page.fill('#scopePresetName', '');
    await page.fill('#scopePresetName', delName);
    await page.waitForTimeout(60);
    // (a) Open the confirm modal, click Cancel -> preset survives (still in the menu).
    await page.click('#scopePresetDelete');
    await page.waitForTimeout(250);
    const sawConfirm = await confirmShown();
    await page.click('#confirmCancel');
    await page.waitForTimeout(350);   // wait out the modal fade-out
    const survivesCancel = await presetListed(delName);
    check('C28 Delete confirms first (Bootstrap modal); Cancel keeps the preset',
      sawConfirm === true && seededOk === true && survivesCancel === true,
      `confirmShown=${sawConfirm} seeded=${seededOk} survivesCancel=${survivesCancel}`);
    // (b) Open it again, click OK -> preset is removed from the menu.
    await page.fill('#scopePresetName', delName);
    await page.waitForTimeout(60);
    await page.click('#scopePresetDelete');
    await page.waitForTimeout(250);
    await page.click('#confirmOk');
    await page.waitForTimeout(350);   // wait out the modal fade-out
    const removedOnAccept = !(await presetListed(delName));
    check('C28 Delete removes the preset when confirmed', removedOnAccept,
      `stillListed=${!removedOnAccept}`);

    // ===== ASSERT 13 (C30): a known sine WAV loads with correct peak/period ====
    // Build a 16-bit mono sine WAV, hand it to #scopeLoadGo, and verify the load.
    // The scope status line echoes "loaded <name> - <frames> frames @ <rate> Hz";
    // assert it parses with the right frame count + rate. Then independently decode
    // the SAME bytes through the app's own readWav module (dynamic import) and
    // assert the signed-decode peak/period match - the real C30 fix (no double bias).
    const wavProbe = await page.evaluate(async () => {
      const rate = 48000, freq = 1000, amp = 0.5, secs = 0.05;
      const n = Math.floor(rate * secs), bps = 2, dataLen = n * bps;
      const buf = new ArrayBuffer(44 + dataLen), dv = new DataView(buf);
      const ascii = (o, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(o + i, s.charCodeAt(i)); };
      ascii(0, 'RIFF'); dv.setUint32(4, 36 + dataLen, true); ascii(8, 'WAVE');
      ascii(12, 'fmt '); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true);
      dv.setUint16(22, 1, true); dv.setUint32(24, rate, true);
      dv.setUint32(28, rate * bps, true); dv.setUint16(32, bps, true); dv.setUint16(34, 16, true);
      ascii(36, 'data'); dv.setUint32(40, dataLen, true);
      for (let i = 0; i < n; i++) {
        const s = amp * Math.sin(2 * Math.PI * freq * i / rate);
        dv.setInt16(44 + i * 2, Math.max(-32768, Math.min(32767, Math.round(s * 32767))), true);
      }
      const bytes = new Uint8Array(buf);
      // (1) Drive the real load handler so fileMode + the banner engage.
      const file = new File([bytes], 'e2e-sine.wav', { type: 'audio/wav' });
      const dt = new DataTransfer(); dt.items.add(file);
      const input = document.getElementById('scopeLoadGo');
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 400));
      const status = (document.getElementById('status') || {}).textContent || '';
      // (2) Decode the same bytes through the app's readWav + the load-path norm.
      const { readWav } = await import('/js/io/wav.js');
      const dec = readWav(bytes);
      const mid = Math.pow(2, dec.bitsPerSample - 1);
      let peak = 0; const left = new Float32Array(dec.frameCount);
      for (let i = 0; i < dec.frameCount; i++) { left[i] = dec.ch0[i] / mid; peak = Math.max(peak, Math.abs(left[i])); }
      const cross = [];
      for (let i = 1; i < left.length && cross.length < 2; i++) if (left[i - 1] < 0 && left[i] >= 0) cross.push(i);
      const period = cross.length === 2 ? cross[1] - cross[0] : -1;
      return { status, peak, period, frames: dec.frameCount, rate: dec.sampleRate,
               expectPeak: amp, expectPeriod: rate / freq, expectFrames: n };
    });
    const statusOk = new RegExp(`loaded e2e-sine\\.wav .* ${wavProbe.expectFrames} frames @ ${wavProbe.rate} Hz`).test(wavProbe.status);
    const peakOk = Math.abs(wavProbe.peak - wavProbe.expectPeak) < 0.02;
    const periodOk = Math.abs(wavProbe.period - wavProbe.expectPeriod) <= 1;
    check('C30 loaded WAV reproduces the real samples (peak/period/frames)',
      statusOk && peakOk && periodOk,
      `status="${wavProbe.status}" peak=${wavProbe.peak.toFixed(4)}~${wavProbe.expectPeak} period=${wavProbe.period}~${wavProbe.expectPeriod}`);

    // ===== ASSERT 14 (C29): a 'Loaded from' banner overlay is shown ============
    // After the load above, fileMode is on; the self-blinking banner carries the
    // file name and is visible over the scope canvas.
    const banner = await page.evaluate(() => {
      const el = document.getElementById('scopeFileBanner');
      if (!el) return { present: false };
      return { present: true, hidden: el.hidden, text: (el.textContent || '').trim() };
    });
    check('C29 loaded-file banner overlay present and showing the file name',
      banner.present && banner.hidden === false && /e2e-sine\.wav/.test(banner.text),
      `present=${banner.present} hidden=${banner.hidden} text="${banner.text}"`);

    // ===== ASSERT 15 (C31): open FFT + FreqResp panes - no Missing i18n key ====
    // Toggle the FFT settings tab, switch to the Frequency-response top tab and
    // change the FFT-size combo (re-runs refreshFftLabel, which previously used
    // an invented web.* key). Then assert no Missing-i18n-key warning fired at any
    // point and no console/page error accumulated.
    await page.click('#fftTabs .tab[data-panel="fftSettings"]').catch(() => {});
    await page.waitForTimeout(100);
    await page.click('#mainTabs button[data-bs-target="#tab-fr"]');
    await page.waitForTimeout(200);
    await page.click('#frTabs .tab[data-panel="frSettings"]').catch(() => {});
    await page.waitForTimeout(100);
    await page.selectOption('#frFft', '4194304').catch(() => {});
    await page.waitForTimeout(150);
    const frLabel = await page.evaluate(() => (document.getElementById('frFftLabel') || {}).textContent || '');
    check('C31 no Missing i18n key warnings (FFT + FreqResp panes)',
      missingKeys.length === 0,
      missingKeys.length ? missingKeys.join(' | ') : `clean; frFftLabel="${frLabel}"`);
    check('C31 no console/page errors after opening FFT + FreqResp',
      errors.length === 0, errors.length ? errors.join(' | ') : 'clean');

    // ----- Generator sweep panel: a sweep form reveals the sweep fields (seeded from
    // prefs) and hides them again for a non-sweep form (syncFormUI toggle). -----
    const sweepUI = await page.evaluate(() => {
      const disp = (id) => getComputedStyle(document.getElementById(id)).display;
      const setForm = (v) => {
        const el = document.getElementById('signalForm');
        el.value = v; el.dispatchEvent(new Event('change', { bubbles: true }));
      };
      setForm('LINEAR_SWEEP');
      const shown = disp('sweepWrap') !== 'none';
      const startVal = document.getElementById('sweepStart').value;
      setForm('SINE');
      const hiddenAgain = disp('sweepWrap') === 'none';
      return { shown, startVal, hiddenAgain };
    });
    check('C32 generator sweep form reveals seeded sweep fields + hides for non-sweep',
      sweepUI.shown && sweepUI.startVal !== '' && sweepUI.hiddenAgain,
      JSON.stringify(sweepUI));

    // ----- Generator per-form UI semantics (Java GeneratorPane): RECTANGLE and TRIANGLE
    // bracket their freq + duty (both sample-period-aligned to fs/N), SINE plain when snap off,
    // noise disables the frequency field, compensated forms enable the .dpd row, DUAL_TONE shows
    // Frequency 1 + Frequency 2. -----
    const formUI = await page.evaluate(() => {
      const setForm = (v) => { const el = document.getElementById('signalForm'); el.value = v; el.dispatchEvent(new Event('change', { bubbles: true })); };
      const txt = (id) => ((document.getElementById(id) || {}).textContent || '');
      const disp = (id) => { const e = document.getElementById(id); return e ? getComputedStyle(e).display : 'none'; };
      const dis = (id) => { const e = document.getElementById(id); return !!(e && e.disabled); };
      const snap = document.getElementById('snap');
      if (snap.checked) { snap.checked = false; snap.dispatchEvent(new Event('change', { bubbles: true })); }
      const out = {};
      setForm('RECTANGLE'); out.rectFreq = txt('freqLabel'); out.rectDuty = txt('dutyLabel');
      setForm('TRIANGLE'); out.triFreq = txt('freqLabel'); out.triDuty = txt('dutyLabel');
      setForm('SINE'); out.sineFreq = txt('freqLabel');
      setForm('WHITE_NOISE'); out.noiseDisabled = dis('toneHz');
      setForm('SINE_COMP'); out.compEnabled = !dis('genCorrBrowse');
      setForm('DUAL_TONE'); out.dualF1 = txt('freqLabel'); out.dualF2vis = disp('tone2Wrap') !== 'none';
      out.dualF1Val = (document.getElementById('toneHz') || {}).value || '';   // freq-1 = 1000 Hz default
      out.dualF2Val = (document.getElementById('tone2Hz') || {}).value || '';   // freq-2 = 1300 Hz default
      out.footerBtns = document.querySelectorAll('#genCol .gen-footer button').length;   // Java = 2
      setForm('SINE');
      return out;
    });
    check('C33 RECTANGLE freq + duty labels are bracketed (sample-corrected)',
      /\(/.test(formUI.rectFreq) && /\(/.test(formUI.rectDuty), JSON.stringify(formUI));
    check('C33 TRIANGLE freq label is bracketed (sample-period-aligned fs/N, like RECTANGLE)',
      /\(/.test(formUI.triFreq), formUI.triFreq);
    check('C33 TRIANGLE duty label is bracketed (duty corner quantises to the fs/N grid, like RECTANGLE)',
      /\(/.test(formUI.triDuty), formUI.triDuty);
    check('C33 SINE freq label plain with snap off', !/\(/.test(formUI.sineFreq), formUI.sineFreq);
    check('C33 noise forms disable the frequency field', formUI.noiseDisabled, JSON.stringify(formUI));
    check('C33 compensated forms enable the .dpd corrections row', formUI.compEnabled, JSON.stringify(formUI));
    check('C33 DUAL_TONE shows Frequency 1 + Frequency 2',
      /1/.test(formUI.dualF1) && formUI.dualF2vis, JSON.stringify(formUI));
    check('C34 DUAL_TONE defaults: Freq1 = 1000 Hz, Freq2 = 1300 Hz (auto-ranged to kHz)',
      /^1\b/.test(formUI.dualF1Val.trim()) && /^1\.3\b/.test(formUI.dualF2Val.trim()),
      `f1="${formUI.dualF1Val}" f2="${formUI.dualF2Val}"`);
    check('C34 generator footer has exactly two buttons (Calibrate-DAC + Play)',
      formUI.footerBtns === 2, `footerBtns=${formUI.footerBtns}`);

  } catch (e) {
    failed++;
    console.log('FAIL: harness error - ' + (e && e.stack || e));
  } finally {
    await browser.close().catch(() => {});
    server.close();
  }

  console.log(`e2e: ${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.log('FAIL: fatal - ' + (e && e.stack || e));
  console.log(`e2e: ${passed} passed, ${failed + 1} failed`);
  process.exit(1);
});
