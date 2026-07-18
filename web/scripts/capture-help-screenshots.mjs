/*
 * Phonalyser web — capture help screenshots from the running WEB app.
 *
 * Drives the real app headless in a FIXED 1280×768 window (deviceScaleFactor 1 → exact
 * pixels, no dpr scaling) and writes web/help/<lang>/img/<file>.png for each spec, so the
 * help shows the WEB UI (which differs slightly from the desktop app's screenshots).
 *
 * Each spec carries a `ready` flag: specs whose web pane/dialog isn't built yet are
 * SKIPPED and logged, so this can run at any stage — re-run per feature. All panes
 * (parts 2–5) + the per-tab toolbar strips are captured today.
 *
 * Run:  node scripts/capture-help-screenshots.mjs                # every ready spec
 *       node scripts/capture-help-screenshots.mjs app scope      # only the given ids
 *       node scripts/capture-help-screenshots.mjs --list         # list specs + readiness
 * GNU AGPL v3 or later.
 */
import http from 'node:http';
import { createReadStream, existsSync, statSync, mkdirSync } from 'node:fs';
import { join, normalize, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const WEB = normalize(join(dirname(fileURLToPath(import.meta.url)), '..'));
const VIEW = { width: 1280, height: 768 };   // FIXED capture window (project requirement)
const LANGS = ['en', 'de', 'uk'];   // capture EVERY language (app UI switched + reloaded per pass)
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.wasm': 'application/wasm',
  '.properties': 'text/plain', '.woff': 'font/woff', '.woff2': 'font/woff2', '.wav': 'audio/wav', '.flac': 'audio/flac' };

function serve() {
  const s = http.createServer((req, res) => {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const fp = normalize(join(WEB, p));
    if (!fp.startsWith(WEB) || !existsSync(fp) || !statSync(fp).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': (MIME[extname(fp).toLowerCase()] || 'application/octet-stream') + '; charset=utf-8', 'Cache-Control': 'no-store' });
    createReadStream(fp).pipe(res);
  });
  return new Promise((r) => s.listen(0, '127.0.0.1', () => r({ s, port: s.address().port })));
}

// Switch the generator signal form by driving the hidden #signalForm select (the custom
// combo syncs from it), exactly as _web-states.mjs / the app do: set value, fire native
// change → structural restart + per-form row show/hide.
async function setSignalForm(page, form) {
  await page.evaluate((f) => {
    const sel = document.getElementById('signalForm');
    sel.value = f;
    sel.dispatchEvent(new Event('change'));
  }, form);
  await page.waitForTimeout(500);
}

// Open the tabbed Preferences modal (app wires #menuPrefs → modal.show()), switch to one
// panel via its tab strip (preferences-dialog.prefsTab), and return the modal-content to
// clip. The panel key matches the data-prefs-panel attribute in index.html.
async function openPrefs(page, panel) {
  await page.evaluate(() => document.getElementById('menuPrefs').click());
  await page.waitForSelector('#prefsModal.show', { timeout: 5000 });
  await page.waitForTimeout(300);
  await page.click(`#prefsTabs .nav-link[data-prefs-panel="${panel}"]`);
  await page.waitForTimeout(300);
  return page.$('#prefsModal .modal-content');
}

// Close the Preferences modal after a prefs capture (spec `after` hook): a left-open
// modal + backdrop would sit over — and swallow the clicks of — every later spec.
async function closePrefs(page) {
  await page.evaluate(() => {
    const m = window.bootstrap.Modal.getInstance(document.getElementById('prefsModal'));
    if (m) m.hide();
  });
  await page.waitForTimeout(400);   // fade-out
}

// New dialog specs (tune-notch, dac-predistortion): show the modal DIRECTLY so its static
// layout renders in the current locale WITHOUT starting a live sweep/run (open() would); clip
// the modal-content; hide it after so the backdrop doesn't swallow later specs.
async function showModal(page, id) {
  await page.evaluate((mid) => window.bootstrap.Modal.getOrCreateInstance(document.getElementById(mid)).show(), id);
  await page.waitForSelector(`#${id}.show`, { timeout: 5000 });
  await page.waitForTimeout(500);
  return page.$(`#${id} .modal-content`);
}
async function hideModal(page, id) {
  await page.evaluate((mid) => { const m = window.bootstrap.Modal.getInstance(document.getElementById(mid)); if (m) m.hide(); }, id);
  await page.waitForTimeout(400);
}

// Per-tab toolbar shot (Oscilloscope - Trigger.png & co): activate one tile-tab so its
// drop-down .tab-panel expands, then clip the STRIP PLUS the expanded body — the strip
// and its panels are DOM siblings (no single element wraps exactly the pair), so this
// returns a {clip} union rect, matching the desktop help's wide short strip framing.
// mainTab switches the top-level Bootstrap tab first (scope/FFT live in #tab-multi,
// FreqResp in #tab-fr); expandPane un-collapses a collapsible pane (#fftPane).
async function tabStripShot(page, { mainTab, stripId, panelId, expandPane }) {
  const onTab = await page.$eval(`.nav-link[data-bs-target="${mainTab}"]`,
    (el) => el.classList.contains('active'));
  if (!onTab) { await page.click(`button[data-bs-target="${mainTab}"]`); await page.waitForTimeout(600); }
  if (expandPane) {
    const collapsed = await page.$eval(expandPane, (el) => el.classList.contains('collapsed'));
    if (collapsed) { await page.click(`${expandPane} .pane-header`); await page.waitForTimeout(300); }
  }
  // Click the owning tab only while its panel is closed: the strip handlers TOGGLE
  // (a click on an active tab with an open panel closes it — scope-tab-control /
  // fft-tab-control / app.js '#xxTabs .tab' click handlers).
  await page.evaluate((arg) => {
    if (!document.getElementById(arg.panelId).classList.contains('show')) {
      document.querySelector(`#${arg.stripId} .tab[data-panel="${arg.panelId}"]`).click();
    }
  }, { stripId, panelId });
  await page.waitForTimeout(400);   // let the panel show + tiles repaint
  const strip = await (await page.$(`#${stripId}`)).boundingBox();
  const panel = await (await page.$(`#${panelId}`)).boundingBox();
  const x = Math.min(strip.x, panel.x), y = Math.min(strip.y, panel.y);
  return { clip: { x, y,
    width: Math.max(strip.x + strip.width, panel.x + panel.width) - x,
    height: Math.max(strip.y + strip.height, panel.y + panel.height) - y } };
}

// Spec factories for the three tile-tab strips (same shape as the hand-written specs).
const scopeTab = (id, file, panelId) => ({ id, file, ready: true,
  shot: (page) => tabStripShot(page, { mainTab: '#tab-multi', stripId: 'scopeTabs', panelId }) });
const fftTab = (id, file, panelId) => ({ id, file, ready: true,
  shot: (page) => tabStripShot(page, { mainTab: '#tab-multi', stripId: 'fftTabs', panelId, expandPane: '#fftPane' }) });
const frTab = (id, file, panelId) => ({ id, file, ready: true,
  shot: (page) => tabStripShot(page, { mainTab: '#tab-fr', stripId: 'frTabs', panelId }) });

// id      → CLI selector. file → help/<lang>/img/<file>. ready → capturable now.
// shot(page) → optional async; returns an ElementHandle to clip, or null for the whole
// 1280×768 window. Specs without shot() default to a full-window capture.
const SPECS = [
  { id: 'app',        file: 'multifunctional.png',  ready: true },
  { id: 'scope',      file: 'oscilloscope-pane.png', ready: true,
    async shot(page) { return page.$('#scopePane'); } },
  { id: 'generator',  file: 'generator-pane.png',    ready: true,
    async shot(page) { await setSignalForm(page, 'SINE'); return page.$('#genCol'); } },

  // ── generator alternate forms (part 3, landed) ─────────────────────────────────────
  { id: 'gen-dualtone', file: 'Generator - dual tone.png', ready: true,
    async shot(page) { await setSignalForm(page, 'DUAL_TONE'); return page.$('#genCol'); } },
  { id: 'gen-sweep',    file: 'Generator - sweep mode.png', ready: true,
    async shot(page) { await setSignalForm(page, 'LOG_SWEEP'); return page.$('#genCol'); } },

  // ── FFT pane (part 4, landed): expand it if collapsed, then clip the pane ───────────
  { id: 'fft', file: 'fft-pane.png', ready: true,
    async shot(page) {
      const collapsed = await page.$eval('#fftPane', (el) => el.classList.contains('collapsed'));
      if (collapsed) { await page.click('#fftPane .pane-header'); await page.waitForTimeout(300); }
      return page.$('#fftPane');
    } },

  // ── Frequency-response pane (part 5, landed): switch to its main tab, clip the pane ──
  { id: 'freqresp', file: 'freqresp-pane.png', ready: true,
    async shot(page) {
      await page.click('button[data-bs-target="#tab-fr"]');
      await page.waitForTimeout(800);
      return page.$('#tab-fr .fft-pane');
    } },

  // ── New help pages this sync: the Tune-notch + DAC-predistortion dialogs ─────────────
  // tune-notch.png is NOT captured here: the Java help ships a LIVE-capture shot
  // (a real measured notch response in the plot) that the static modal render can't
  // match — the web help uses the Java image verbatim (copied by sync-help; per the
  // maintainer, 2026-07-12). Re-enable only if a live web capture is ever scripted.
  { id: 'dac-predistortion', file: 'dac-predistortion-wizard.png', ready: true,
    shot: (page) => showModal(page, 'predistModal'), after: (page) => hideModal(page, 'predistModal') },

  // ── Device-profile sync dialogs this round: the unified ADC/DAC Calibration dialog +
  //    the Card editor. #calibrationModal is ONE element reused for ADC and DAC — each
  //    opener sets its own title/prompt — so we drive the real openers and hide between shots.
  // ADC calibration: the scope / FFT Utility crosshair only opens with a live measured Vrms
  // (FftView.getLastVrms → its last analysis). Headless has no live signal, so seed a synthetic
  // last result and drive the FFT ADC-calibrate handler — the SAME unified dialog the scope
  // Utility path opens (both call openAdc with calibrate.title / calibrate.input).
  { id: 'adc-cal', file: 'ADC calibration.png', ready: true,
    async shot(page) {
      await page.evaluate(() => {
        if (window.__fftPane && window.__fftPane.view) window.__fftPane.view._last = { fundamentalLinear: 0.5 };
        document.getElementById('fftAdcCalibrate').click();
      });
      await page.waitForSelector('#calibrationModal.show', { timeout: 5000 });
      await page.waitForTimeout(400);
      return page.$('#calibrationModal .modal-content');
    },
    after: async (page) => {
      await hideModal(page, 'calibrationModal');
      await page.evaluate(() => { if (window.__fftPane && window.__fftPane.view) window.__fftPane.view._last = null; });
    } },
  // DAC calibration: the generator crosshair (#calibrateDac) opens the same modal titled
  // "DAC calibration", seeded from the configured amplitude (default 0.5 Vrms > 0) — no live
  // signal needed.
  { id: 'dac-cal', file: 'DAC calibration.png', ready: true,
    async shot(page) {
      await page.evaluate(() => document.getElementById('calibrateDac').click());
      await page.waitForSelector('#calibrationModal.show', { timeout: 5000 });
      await page.waitForTimeout(400);
      return page.$('#calibrationModal .modal-content');
    }, after: (page) => hideModal(page, 'calibrationModal') },
  // Card editor: open Preferences → Audio (its show.bs.modal populates the card combos from the
  // seeded catalog), select the first real card so the pencil (edit) button enables, then open
  // the card editor on it. Clip the (stacked) card-editor modal; hide it then close Preferences.
  { id: 'card-editor', file: 'Card editor.png', ready: true,
    async shot(page) {
      await openPrefs(page, 'audio');
      await page.evaluate(() => {
        // Pick the E1DA card BY NAME: binding re-puts the profile, which can
        // reorder the store — index 0 then points at a different card on the
        // next language pass (uk once seeded CUBILUX while en/de had E1DA).
        const sel = document.getElementById('inCardSel');
        const opt = [...sel.options].find((o) => o.textContent.trim() === 'E1DA Cosmos ADC');
        sel.value = opt ? opt.value : '0';
        sel.dispatchEvent(new Event('change'));
      });
      await page.waitForTimeout(400);
      await page.evaluate(() => {
        document.getElementById('inCardEdit').disabled = false;   // ensure the click fires the handler
        document.getElementById('inCardEdit').click();
      });
      await page.waitForSelector('#cardEditorModal.show', { timeout: 5000 });
      await page.waitForTimeout(400);
      return page.$('#cardEditorModal .modal-content');
    },
    after: async (page) => { await hideModal(page, 'cardEditorModal'); await closePrefs(page); } },

  // ── Preferences dialog tabs (part 2, landed). Every prefs spec closes the modal
  //    after its capture (`after`), so later specs never sit under a leftover backdrop.
  { id: 'prefs-lookfeel', file: 'Preferences Look and Feel.png', ready: true,
    async shot(page) { return openPrefs(page, 'lookAndFeel'); }, after: closePrefs },
  { id: 'prefs-audio',    file: 'Preferences Audio.png',         ready: true,
    async shot(page) {
      await openPrefs(page, 'audio');
      // Stage the REAL bench pairing for the shot (headless enumerates only a
      // bare "Default" device): bind the I2SoverUSB card on the output side
      // (the input side is bound to the E1DA card by the card-editor spec),
      // then relabel the two device combos with the hardware names the
      // maintainer's live machine shows. Values/FS readouts stay the true
      // seeded card values; only the device LABEL text is staged.
      await page.evaluate(() => {
        const out = document.getElementById('outCardSel');
        const opt = [...out.options].find((o) => o.textContent.trim() === 'I2SoverUSB');
        if (opt) { out.value = opt.value; out.dispatchEvent(new Event('change')); }
      });
      await page.waitForTimeout(400);
      await page.evaluate(() => {
        const label = (sel, text) => {
          const o = sel.selectedOptions[0] || sel.options[0];
          if (o) { o.textContent = text; o.selected = true; }
        };
        label(document.getElementById('inSel'), 'Line In (E1DA Cosmos ADC)');
        label(document.getElementById('outSel'), 'Speakers (I2SoverUSB)');
        // The rate combos show the (fake) capture device's native rate — stage the
        // bench hardware's 384 kHz like the device labels.
        label(document.getElementById('inRate'), '384000 Hz');
        label(document.getElementById('outRate'), '384000 Hz');
      });
      return page.$('#prefsModal .modal-content');
    }, after: closePrefs },
  { id: 'prefs-scope',    file: 'Preferences Oscilloscope.png',  ready: true,
    async shot(page) { return openPrefs(page, 'oscilloscope'); }, after: closePrefs },
  { id: 'prefs-fft',      file: 'Preferences FFT.png',           ready: true,
    async shot(page) { return openPrefs(page, 'fft'); }, after: closePrefs },
  { id: 'prefs-freqresp', file: 'Preferences Frequency response.png', ready: true,
    async shot(page) { return openPrefs(page, 'freqResp'); }, after: closePrefs },

  // ── per-tab toolbar shots: the tile-tab strip + the active tab's expanded body ──────
  scopeTab('scope-tab-left',    'Oscilloscope - Left.png',        'scopeLeft'),
  scopeTab('scope-tab-right',   'Oscilloscope - Right.png',       'scopeRight'),
  scopeTab('scope-tab-horiz',   'Oscilloscope - Horizontal.png',  'scopeHoriz'),
  scopeTab('scope-tab-trigger', 'Oscilloscope - Trigger.png',     'scopeTrig'),
  scopeTab('scope-tab-presets', 'Oscilloscope - Presets.png',     'scopePresets'),
  scopeTab('scope-tab-utility', 'Oscilloscope - Utility.png',     'scopeUtility'),
  scopeTab('scope-tab-save',    'Oscilloscope - Save to.png',     'scopeSavePanel'),
  scopeTab('scope-tab-load',    'Oscilloscope - Load signal.png', 'scopeLoadPanel'),
  fftTab('fft-tab-settings', 'FFT - FFT settings.png',     'fftSettings'),
  fftTab('fft-tab-thd',      'FFT - THD settings.png',     'thdSettings'),
  fftTab('fft-tab-presets',  'FFT - Presets.png',          'fftPresetsPanel'),
  fftTab('fft-tab-utility',  'FFT - Utility.png',          'fftUtility'),
  fftTab('fft-tab-cal',      'FFT - Load calibration.png', 'fftCalPanel'),
  fftTab('fft-tab-save',     'FFT - Save to.png',          'fftSavePanel'),
  fftTab('fft-tab-load',     'FFT - Load from.png',        'fftLoadPanel'),
  frTab('fr-tab-settings', 'FreqResp - Settings.png',         'frSettings'),
  frTab('fr-tab-riaa',     'FreqResp - RIAA IEC.png',         'frRiaaPanel'),
  frTab('fr-tab-filters',  'FreqResp - Filters.png',          'frFilterPanel'),
  frTab('fr-tab-uneven',   'FreqResp - Unevenness.png',       'frUnevenPanel'),
  frTab('fr-tab-presets',  'FreqResp - Presets.png',          'frPresetsPanel'),
  frTab('fr-tab-utility',  'FreqResp - Utility.png',          'frUtility'),
  frTab('fr-tab-cal',      'FreqResp - Load calibration.png', 'frCalPanel'),
  frTab('fr-tab-save',     'FreqResp - Save to.png',          'frSavePanel'),
  frTab('fr-tab-load',     'FreqResp - Load from.png',        'frLoadPanel'),
];

const argv = process.argv.slice(2);
if (argv.includes('--list')) {
  for (const s of SPECS) console.log(`${s.ready ? '✓' : '·'} ${s.id.padEnd(16)} ${s.file}`);
  process.exit(0);
}
const want = argv.filter((a) => !a.startsWith('--'));
const todo = SPECS.filter((s) => (want.length ? want.includes(s.id) : true));

const { s, port } = await serve();
// Classic (always-visible) scrollbars: Playwright's bundled headless shell
// draws OVERLAY scrollbars that hide when idle, so a scrolling pane (the
// Preferences Audio tab) captures without its scrollbar. The installed desktop
// Chrome/Edge in new-headless mode renders the same classic scrollbars a real
// Windows browser shows — use it, preferring Chrome, falling back to Edge,
// then to the bundled shell (shots then lack scrollbars, better than failing).
// --headed: run a visible browser — headless Chromium (any shell/channel) forces
// overlay scrollbars (crbug), so a shot that must show a classic scrollbar (the
// scrolling Audio tab) needs a real headed window.
const headed = argv.includes('--headed');
// Headed also gets FAKE media devices: a headed browser without mic permission
// enumerates devices with EMPTY labels, so the app's device combo comes up blank
// and no card can bind. The fake devices give the whole pipeline something real
// to resolve against (the audio spec re-labels the visible combo text afterwards).
const headedArgs = ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'];
let browser = null;
for (const channel of ['chrome', 'msedge', undefined]) {
  try {
    browser = await chromium.launch({
      ...(channel ? { channel } : {}),
      headless: !headed,
      args: headed ? headedArgs : [],
    });
    break;
  } catch { /* channel not installed — try the next */ }
}
const ctx = await browser.newContext({ viewport: VIEW, deviceScaleFactor: 1 });
// Suppress the startup Tip-of-the-day popup so it never overlaps a captured pane
// (it docks bottom-left and would cover the generator's lower controls). Seeding
// only showTipsAtStartup=false leaves every other pref at its default and the
// device store (a separate key) untouched.
await ctx.addInitScript(() => {
  try { localStorage.setItem('phonalyser.preferences', JSON.stringify({ showTipsAtStartup: false })); } catch { /* ignore */ }
});
const page = await ctx.newPage();
// 'load' + the #scopePane wait below, NOT 'networkidle': the libflac wasm
// loader leaves its /vendor/libflac/*.wasm response body unconsumed, so the
// network never idles and a networkidle gate times out (the app itself is up).
await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'load' });
// CAPTURE-ONLY classic scrollbar: every headless Chromium mode draws OVERLAY
// scrollbars that vanish when idle (crbug), so the scrolling Preferences Audio
// pane would screenshot without its scrollbar — unlike real desktop Chrome on
// Windows, which shows the classic bar. Style a webkit scrollbar (always
// rendered) to match the Windows look; the shipped app.css is untouched.
await page.waitForSelector('#scopePane', { timeout: 10000 }).catch(() => {});
await page.waitForTimeout(500);   // let layout/fonts settle

let done = 0, skipped = 0;
for (const lang of LANGS) {
  // 'en' is the fresh initial load; for the others switch the UI language then RELOAD so each
  // pass starts from a clean, correctly-localised state (init reads prefs.uiLanguage on load;
  // the #langMenu handler persists it via the 250ms-debounced save, so wait before reloading).
  if (lang !== 'en') {
    await page.evaluate((t) => window.jQuery(`#langMenu [data-lang="${t}"]`).trigger('click'), lang);
    await page.waitForTimeout(700);
    await page.reload({ waitUntil: 'load' });
    await page.waitForSelector('#scopePane', { timeout: 10000 }).catch(() => {});
    await page.waitForTimeout(600);   // layout/fonts settle in the new locale
  }
  const imgDir = join(WEB, 'help', lang, 'img');
  mkdirSync(imgDir, { recursive: true });
  for (const spec of todo) {
    if (!spec.ready) { if (lang === 'en') { console.log(`skip (web UI not ready): ${spec.id} → ${spec.file}`); skipped++; } continue; }
    const out = join(imgDir, spec.file);
    // shot() → ElementHandle, {clip: rect} (a region no single element wraps — the
    // tab-strip + sibling panel shots), or null for the whole 1280×768 window.
    const el = spec.shot ? await spec.shot(page) : null;
    if (el && el.clip) await page.screenshot({ path: out, clip: el.clip });
    else if (el) await el.screenshot({ path: out });
    else await page.screenshot({ path: out });
    if (spec.after) await spec.after(page);   // per-spec cleanup (close the modal)
    console.log(`captured: ${lang}/${spec.id} → help/${lang}/img/${spec.file}`);
    done++;
  }
}

await browser.close();
s.close();
console.log(`\n${done} captured, ${skipped} skipped.`);
