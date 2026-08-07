/*
 * Phonalyser web - capture help screenshots from the running WEB app.
 *
 * Drives the real app headless in a FIXED 1280×768 window (deviceScaleFactor 1 -> exact
 * pixels, no dpr scaling) and writes web/help/<lang>/img/<file>.png for each spec, so the
 * help shows the WEB UI (which differs slightly from the desktop app's screenshots).
 *
 * Each spec carries a `ready` flag: specs whose web pane/dialog isn't built yet are
 * SKIPPED and logged, so this can run at any stage - re-run per feature. All panes
 * (parts 2-5) + the per-tab toolbar strips are captured today.
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
import { fakeQa40xUsbInit } from './fake-qa40x-usb.mjs';

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
// change -> structural restart + per-form row show/hide.
async function setSignalForm(page, form) {
  await page.evaluate((f) => {
    const sel = document.getElementById('signalForm');
    sel.value = f;
    sel.dispatchEvent(new Event('change'));
  }, form);
  await page.waitForTimeout(500);
}

// Open the tabbed Preferences modal (app wires #menuPrefs -> modal.show()), switch to one
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
// modal + backdrop would sit over - and swallow the clicks of - every later spec.
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
// drop-down .tab-panel expands, then clip the STRIP PLUS the expanded body - the strip
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
  // (a click on an active tab with an open panel closes it - scope-tab-control /
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

// id      -> CLI selector. file -> help/<lang>/img/<file>. ready -> capturable now.
// shot(page) -> optional async; returns an ElementHandle to clip, or null for the whole
// 1280×768 window. Specs without shot() default to a full-window capture.
/** The attenuator position the bench converter is physically set to, plus that position's
 *  CALIBRATED full scale per channel. The card catalogue seeds the nominal 2.7 V; the bench's own
 *  calibration reads 2.794 V, and it is the calibrated figure the instrument measures with - so the
 *  histogram shot's voltage axis is labelled the way the analyzer really reads. */
const COSMOS_INPUT_RANGE = '2.7V';
const COSMOS_FS_VRMS = { left: 2.794405, right: 2.794613 };

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

  // ── Amplitude-histogram window ───────────────────────────────────────────────────────
  // A LIVE shot: the scope actually records for six seconds so the plot shows a distribution the
  // app collected itself, through the whole real path (capture -> measurement client -> accumulator
  // -> view). Nothing is injected. The input is pinned to the bench converter (E1DA Cosmos ADC) -
  // its own noise floor is the picture the help text describes; whatever happens to be the system
  // default microphone is not.
  { id: 'scope-histogram', file: 'scope-histogram.png', ready: true,
    async shot(page) {
      // The startup splash is an overlay: capture it away and this shot is a picture of the
      // splash. It self-dismisses when the boot device scan resolves, which can outlast the
      // harness's fixed post-load wait when this spec runs first.
      await page.waitForSelector('#startupSplash', { state: 'hidden', timeout: 20000 }).catch(() => {});
      // Choose the converter the way a user does: in Preferences ▸ Audio, then OK - which commits
      // engine.config and restarts the consumers on it. Two things this must NOT do:
      //   - reload afterwards - media device IDs are re-salted per browsing session, so a saved id
      //     no longer resolves and the app falls back to `default` (that is how a shot ended up
      //     recording the headset microphone at 48 kHz), and
      //   - force a sample rate - in the browser the rate is whatever Windows has configured for
      //     that device; the dialog's own rate list is the truth here.
      await openPrefs(page, 'audio');
      const bound = await page.evaluate(() => {
        const sel = document.getElementById('inSel');
        const opt = [...sel.options].find((o) => /Cosmos/i.test(o.textContent));
        if (!opt) return null;
        sel.value = opt.value;
        sel.dispatchEvent(new Event('change'));
        return opt.textContent.trim();
      });
      await page.waitForTimeout(400);          // let the dialog re-derive the device's rate list + card
      // Put the card on the range the converter is PHYSICALLY switched to. The range only tells the
      // app the full-scale volts, so a mismatch mislabels the voltage axis by the ratio of the two
      // ranges - the seeded default (1.7 V) drew this bench's noise floor at half its real level.
      // One side at a time, re-querying between: each pick re-renders the whole range list, so a
      // second click taken from the same query would land on a detached row and be lost (which left
      // the right channel - the one on show - still on 1.7 V).
      for (const side of [0, 1]) {
        const ok = await page.evaluate(({ want, i }) => {
          const rows = [...document.querySelectorAll('#inRanges .card-range-row')];
          const row = rows.find((r) => [...r.querySelectorAll('input[type=text]')].some((x) => x.value === want));
          const radio = row && row.querySelectorAll('input[type=radio]')[i];
          if (!radio) return false;
          radio.click();
          return true;
        }, { want: COSMOS_INPUT_RANGE, i: side });
        if (!ok) {
          console.warn(`  ! input range "${COSMOS_INPUT_RANGE}" not offered by the card - axis scale unverified`);
          break;
        }
        await page.waitForTimeout(250);
      }
      await page.click('#prefsOk');
      await page.waitForTimeout(1200);         // commit + consumer restart
      if (!bound) console.warn('  ! Cosmos ADC not in the input list - the shot records the default input');
      // The card seed carries the range's NOMINAL full scale; the bench is calibrated. Only the axis
      // labels depend on it (the accumulator counts normalised samples), so setting it here relabels
      // the plot without touching a single count.
      await page.evaluate((fs) => {
        const p = window.__scopePane.prefs;
        p.adcFsVoltageRms.set(fs.left);
        p.adcFsVoltageRmsRight.set(fs.right);
      }, COSMOS_FS_VRMS);
      // Pose the window by SETTING state, never by clicking a toggle: the open state is a preference
      // the pane restores at startup, so a click could just as well close it. Right channel because
      // ch1 is the calibrated / attenuated input on this bench - the channel a reading is taken on.
      await page.evaluate(() => {
        const pane = window.__scopePane;
        pane.prefs.oscRightChannelEnabled.set(true);
        pane.prefs.oscHistogramChannel.set('R');
        pane.setHistogramOpen(true);
        pane.syncHistogramButtons();
      });
      await page.waitForTimeout(300);
      await page.click('.scope-pane .led-btn');        // record
      // Wait for REAL samples - a silent stream still fills the centre bin, so a non-zero total
      // proves nothing; a spread of occupied bins does.
      await page.waitForFunction(() => {
        const h = window.__scopePane.engine.scope.histogramSnapshot('R');
        if (!h) return false;
        let n = 0;
        for (let i = h.firstOccupied(); i >= 0 && i <= h.lastOccupied(); i++) if (h.getCount(i) > 0) n++;
        return n >= 16;
      }, null, { timeout: 30000 }).catch(() => {});
      // Start the shown distribution from here, so it is six seconds of real signal rather than a
      // range fitted while the line was still settling.
      await page.evaluate(() => window.__scopePane.engine.scope.resetHistograms());
      await page.waitForTimeout(6000);                 // collect a distribution worth showing
      // A silent stream still draws a perfectly tidy plot - one bar at 0 V - so verify the shot is
      // actually a distribution before it becomes a help image.
      const occupied = await page.evaluate(() => {
        const h = window.__scopePane.engine.scope.histogramSnapshot('R');
        if (!h) return 0;
        let n = 0;
        for (let i = h.firstOccupied(); i >= 0 && i <= h.lastOccupied(); i++) if (h.getCount(i) > 0) n++;
        return n;
      });
      if (occupied < 16) {
        console.warn(`  ! histogram has only ${occupied} occupied bins - the input looks silent; `
          + 'check that the converter is connected and not held by another application');
      }
      // Say WHICH device and rate the picture is of, so a fallback to some other input cannot pass
      // unnoticed again.
      const src = await page.evaluate(async () => {
        const pane = window.__scopePane;
        const cfg = pane.engine.config;
        const devs = await navigator.mediaDevices.enumerateDevices();
        const open = devs.find((d) => d.deviceId === cfg.inDeviceId);
        const meas = pane.view && pane.view.latest;
        const vrms = meas && meas.vrms ? `${(meas.vrms * 1e6).toFixed(2)} µV` : 'n/a';
        return `${open ? open.label : cfg.inDeviceId} @ ${cfg.inRate} Hz, `
          + `R full scale ${pane.prefs.getAdcFsVoltageRms('R').toFixed(3)} Vrms, measured ${vrms} rms`;
      });
      console.log(`    histogram recorded from: ${src}`);
      return page.$('#scopeHistWindow');
    },
    after: async (page) => {
      await page.click('.scope-pane .led-btn');        // stop recording again
      await page.waitForTimeout(300);
      await page.evaluate(() => {
        window.__scopePane.engine.scope.resetHistograms();
        window.__scopePane.prefs.oscHistogramChannel.set('L');   // leave the app as it was found
        document.getElementById('scopeHistClose').click();
      });
      await page.waitForTimeout(200);
    } },

  // ── Help pages for the Tune-notch + DAC-predistortion dialogs ─────────────
  // tune-notch.png is NOT captured here: the Java help ships a LIVE-capture shot
  // (a real measured notch response in the plot) that the static modal render can't
  // match - the web help uses the Java image verbatim (copied by sync-help).
  // Re-enable only if a live web capture is ever scripted.
  { id: 'dac-predistortion', file: 'dac-predistortion-wizard.png', ready: true,
    shot: (page) => showModal(page, 'predistModal'), after: (page) => hideModal(page, 'predistModal') },

  // ── Device-profile sync dialogs: the unified ADC/DAC Calibration dialog +
  //    the Card editor. #calibrationModal is ONE element reused for ADC and DAC - each
  //    opener sets its own title/prompt - so we drive the real openers and hide between shots.
  // ADC calibration: the scope / FFT Utility crosshair only opens with a live measured Vrms
  // (FftView.getLastVrms -> its last analysis). Headless has no live signal, so seed a synthetic
  // last result and drive the FFT ADC-calibrate handler - the SAME unified dialog the scope
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
  // "DAC calibration", seeded from the configured amplitude (default 0.5 Vrms > 0) - no live
  // signal needed.
  { id: 'dac-cal', file: 'DAC calibration.png', ready: true,
    async shot(page) {
      await page.evaluate(() => document.getElementById('calibrateDac').click());
      await page.waitForSelector('#calibrationModal.show', { timeout: 5000 });
      await page.waitForTimeout(400);
      return page.$('#calibrationModal .modal-content');
    }, after: (page) => hideModal(page, 'calibrationModal') },
  // Card editor: open Preferences -> Audio (its show.bs.modal populates the card combos from the
  // seeded catalog), select the first real card so the pencil (edit) button enables, then open
  // the card editor on it. Clip the (stacked) card-editor modal; hide it then close Preferences.
  { id: 'card-editor', file: 'Card editor.png', ready: true,
    async shot(page) {
      await openPrefs(page, 'audio');
      await page.evaluate(() => {
        // Pick the E1DA card BY NAME: binding re-puts the profile, which can
        // reorder the store - index 0 then points at a different card on the
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
      // then relabel the two device combos with the hardware names a real
      // bench machine shows. Values/FS readouts stay the true
      // seeded card values; only the device LABEL text is staged.
      // BOTH sides are bound HERE, not inherited. The card-editor spec cannot supply the input
      // side, because that spec closes Preferences with Cancel, so the
      // binding never reached this shot: the picture went out with Card = "New card..." and
      // an uncalibrated 1.700000 ADC full scale. Binding
      // the card the bench really uses is what makes the full-scale readouts below it real.
      await page.evaluate(() => {
        const bind = (id, name) => {
          const sel = document.getElementById(id);
          const opt = [...sel.options].find((o) => o.textContent.trim() === name);
          if (opt) { sel.value = opt.value; sel.dispatchEvent(new Event('change')); }
          return !!opt;
        };
        if (!bind('inCardSel', 'E1DA Cosmos ADC')) console.warn('  ! E1DA Cosmos ADC card not offered');
        if (!bind('outCardSel', 'I2SoverUSB')) console.warn('  ! I2SoverUSB card not offered');
      });
      await page.waitForTimeout(400);
      await page.evaluate(() => {
        const label = (sel, text) => {
          const o = sel.selectedOptions[0] || sel.options[0];
          if (o) { o.textContent = text; o.selected = true; }
        };
        label(document.getElementById('inSel'), 'Line In (E1DA Cosmos ADC)');
        label(document.getElementById('outSel'), 'Speakers (I2SoverUSB)');
        // The rate combos show the (fake) capture device's native rate - stage the
        // bench hardware's 384 kHz like the device labels.
        label(document.getElementById('inRate'), '384000 Hz');
        label(document.getElementById('outRate'), '384000 Hz');
      });
      return page.$('#prefsModal .modal-content');
    }, after: closePrefs },
  // The QA40x pair. A fake navigator.usb (scripts/fake-qa40x-usb.mjs, installed for every page)
  // carries one QA403 answering the register protocol, so these two shots come from the REAL code
  // path - the analyzer is enumerated, its calibration page read and its card built - rather than
  // from staged DOM. Selecting the backend is what asks for the device, exactly as a user does.
  { id: 'prefs-audio-qa40x', file: 'Preferences Audio QA40x.png', ready: true,
    async shot(page) {
      await openPrefs(page, 'audio');
      await page.evaluate(() => {
        const sel = document.getElementById('backendSel');
        sel.value = 'QA40X';
        sel.dispatchEvent(new Event('change'));
      });
      // The switch opens the analyzer, reads its cal page and rebuilds the card + range table.
      await page.waitForTimeout(1200);
      return page.$('#prefsModal .modal-content');
    }, after: closePrefs },
  { id: 'qa40x-settings', file: 'QA40x settings.png', ready: true,
    async shot(page) {
      await openPrefs(page, 'audio');
      await page.evaluate(() => {
        const sel = document.getElementById('backendSel');
        sel.value = 'QA40X';
        sel.dispatchEvent(new Event('change'));
      });
      await page.waitForTimeout(1200);
      await page.click('#backendSettings');
      await page.waitForTimeout(600);   // the panel reads its registers as it opens
      // ITS OWN id, with no `.modal.show` fallback: page.$ returns the first DOM match and the
      // Preferences modal comes earlier in the document, so a fallback clipped that instead - the
      // settings dialog then appeared cut off, missing its I2S toggle and buttons.
      return page.$('#qa40xSettingsModal .modal-content');
    },
    async after(page) {
      await page.evaluate(() => document.querySelector('#qa40xSettingsOk')?.click());
      await page.waitForTimeout(300);
      return closePrefs(page);
    } },
  { id: 'prefs-scope',    file: 'Preferences Oscilloscope.png',  ready: true,
    async shot(page) { return openPrefs(page, 'oscilloscope'); }, after: closePrefs },
  { id: 'prefs-fft',      file: 'Preferences FFT.png',           ready: true,
    async shot(page) { return openPrefs(page, 'fft'); }, after: closePrefs },
  { id: 'prefs-freqresp', file: 'Preferences Frequency response.png', ready: true,
    async shot(page) { return openPrefs(page, 'freqResp'); }, after: closePrefs },

  // ── The two dialogs the freshly imported help references and the web tree had no image of ──
  // Both are driven through the app's OWN openers, so the pixels are the real widgets in the real
  // locale. Neither can carry the live CONTENT its desktop counterpart's shot has, and neither is
  // staged to pretend otherwise:
  //  - the server table lists what THIS installation remembers, and the web has no multicast
  //    discovery (net-server-list-dialog.js: one typed address buys the room via the peer table),
  //    so on a machine never pointed at a bench the rows are empty - which is what the operator
  //    sees the first time they open it, and what the two entry rows below the table are for;
  //  - the busy shell's meter is fed from an IN-FLIGHT recording (pumpMeter), so it is empty until
  //    a sweep is really running - this is the dialog at the instant Play is pressed.
  { id: 'net-servers', file: 'Phonalyser servers.png', ready: true,
    async shot(page) {
      await openPrefs(page, 'audio');
      await page.evaluate(() => document.getElementById('netServers').click());
      await page.waitForSelector('#netServersModal.show', { timeout: 5000 });
      await page.waitForTimeout(600);   // fade-in + the on-open liveness round's first repaint
      return page.$('#netServersModal .modal-content');
    },
    after: async (page) => { await hideModal(page, 'netServersModal'); await closePrefs(page); } },
  // The sweep's busy shell - what carries the cooperative Cancel button the help text beside this
  // image describes. openBusyMeter() is the SAME call captureAndDeconvolve makes, handed the SAME
  // five numbers it computes from the pane's own settings, so the meter's dB grid and its
  // total-duration axis label are this installation's real sweep rather than a pose.
  // closeBusyMeter() is the shell's own teardown (Java closeBusyShell).
  { id: 'fr-busy', file: 'FreqResp - Busy.png', ready: true,
    async shot(page) {
      await page.evaluate(() => {
        const c = window.__freqRespController;
        const f0 = Math.max(1, c.prefs.freqRespStartHz.get());
        const f1 = Math.max(f0 + 1, c.prefs.freqRespStopHz.get());
        const leadInSec = Math.max(0, c.prefs.freqRespLeadInSec.get());
        c.openBusyMeter(c.expectedMeasurementSeconds(), leadInSec, c.durationSec(), f0, f1);
      });
      await page.waitForSelector('#frBusyModal.show', { timeout: 5000 });
      await page.waitForTimeout(500);   // 'shown.bs.modal' builds the meter off the laid-out box
      return page.$('#frBusyModal .modal-content');
    },
    after: async (page) => {
      await page.evaluate(() => window.__freqRespController.closeBusyMeter());
      await page.waitForTimeout(400);   // fade-out
    } },

  // The JSON config editor (shell/json-config-dialog.js). Opened through its own menu item, so
  // the picture is the real window on the real live document - the preferences store, which is
  // the one an operator opens first. The SCHEMA COMPLETION is staged open on purpose: it is the
  // half of this feature a static screenshot cannot otherwise show, and the help text beside the
  // image describes it. Ctrl+Space is CodeMirror's own explicit-completion binding, so the list
  // is raised the way a user raises it rather than by poking the widget.
  { id: 'json-config', file: 'JSON config editor.png', ready: true,
    async shot(page) {
      await page.evaluate(() => document.querySelector('#menuCfgPreferencesItems .dropdown-item').click());
      await page.waitForSelector('#jsonConfigModal.show', { timeout: 8000 });
      await page.waitForTimeout(800);          // fade-in + the editor's first measure
      // Put the caret on a fresh line inside the document and ask for completion there: at the
      // top level the list is the whole key set, which is what makes the point.
      await page.evaluate(() => {
        const view = window.__jsonConfigView;
        if (!view) return;
        // End of line 1 ("{"), then a newline and two spaces - a key position.
        const at = view.state.doc.line(1).to;
        view.dispatch({ changes: { from: at, insert: '\n  ' }, selection: { anchor: at + 3 } });
        view.focus();
      });
      await page.waitForTimeout(200);
      // TYPE the opening quote rather than pressing the explicit-completion chord: CodeMirror
      // activates completion on typing by default, and that is also how an operator meets the
      // list. (Control+Space did not reach the editor through Playwright's synthetic events.)
      await page.keyboard.type('"');
      // The completion list is rendered asynchronously; wait for it rather than guessing.
      await page.waitForSelector('.cm-tooltip-autocomplete', { timeout: 5000 }).catch(() => {
        console.warn('  ! completion list did not open - the shot shows the editor without it');
      });
      await page.waitForTimeout(400);
      return page.$('#jsonConfigModal .modal-content');
    },
    after: async (page) => {
      await page.keyboard.press('Escape');     // dismiss the completion list, not the dialog
      await page.waitForTimeout(150);
      await page.evaluate(() => {
        const m = window.bootstrap.Modal.getInstance(document.getElementById('jsonConfigModal'));
        if (m) m.hide();
      });
      await page.waitForTimeout(400);
    } },

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
// Windows browser shows - use it, preferring Chrome, falling back to Edge,
// then to the bundled shell (shots then lack scrollbars, better than failing).
// --headed: run a visible browser - headless Chromium (any shell/channel) forces
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
      // Auto-grant the microphone in BOTH modes: without permission enumerateDevices returns empty
      // labels, no card binds, and a spec that has to RUN a capture has nothing to record. Only the
      // PERMISSION prompt is faked - the fake capture DEVICE is headed-only, so the histogram shot
      // records the real converter on the bench.
      args: headed ? headedArgs : ['--use-fake-ui-for-media-stream'],
    });
    break;
  } catch { /* channel not installed - try the next */ }
}
const ctx = await browser.newContext({ viewport: VIEW, deviceScaleFactor: 1 });
// Suppress the startup Tip-of-the-day popup so it never overlaps a captured pane
// (it docks bottom-left and would cover the generator's lower controls). Seeding
// only showTipsAtStartup=false leaves every other pref at its default and the
// device store (a separate key) untouched.
await ctx.addInitScript(() => {
  // SEED ONLY ON A VIRGIN PROFILE. An init script runs on EVERY navigation, so writing
  // unconditionally overwrote the app's own saved preferences on each page.reload() - including the
  // uiLanguage the language pass had just persisted, which is why help/de and help/uk received
  // English pixels. Once the app has saved a document, leave it alone: it
  // already carries showTipsAtStartup=false, plus formatVersion, which a load requires.
  try {
    const KEY = 'phonalyser.preferences';
    if (!localStorage.getItem(KEY)) {
      localStorage.setItem(KEY, JSON.stringify({ showTipsAtStartup: false }));
    }
  } catch { /* ignore */ }
});
// One fake QA403 on navigator.usb, so the QA40x specs reach the real backend without hardware. It
// answers registers only; audio transfers park, which is what an idle analyzer looks like. Harmless
// to the other specs: the backend still starts on WEB_AUDIO, this only makes QA40x selectABLE.
await ctx.addInitScript(fakeQa40xUsbInit());
const page = await ctx.newPage();
// 'load' + the #scopePane wait below, NOT 'networkidle': the libflac wasm
// loader leaves its /vendor/libflac/*.wasm response body unconsumed, so the
// network never idles and a networkidle gate times out (the app itself is up).
await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'load' });
// CAPTURE-ONLY classic scrollbar: every headless Chromium mode draws OVERLAY
// scrollbars that vanish when idle (crbug), so the scrolling Preferences Audio
// pane would screenshot without its scrollbar - unlike real desktop Chrome on
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
    // Switch through the app's OWN menu handler: it sets the pref and saves a COMPLETE preferences
    // document (formatVersion included), which is what the next load will accept. Writing
    // localStorage directly does not work - a document without formatVersion is rejected on load
    // and the app silently comes up in English.
    //
    // WAIT for the item first: app.js builds the language menu at runtime, so firing the click
    // before it exists is a no-op that leaves the pass in the PREVIOUS locale - which is how
    // help/de and help/uk came to hold English pixels, byte-identical to help/en.
    // state:'attached' - the items live in a CLOSED dropdown, so they are present but not visible,
    // and Playwright's default visibility wait would time out on a perfectly good element.
    await page.waitForSelector(`#langMenu [data-lang="${lang}"]`, { state: 'attached', timeout: 10000 });
    await page.evaluate((t) => window.jQuery(`#langMenu [data-lang="${t}"]`).trigger('click'), lang);
    // Then WAIT FOR THE SAVE, rather than guessing at the debounce: reloading too early re-reads the
    // old locale and the pass captures the previous language's pixels.
    await page.waitForFunction((t) => {
      try { return JSON.parse(localStorage.getItem('phonalyser.preferences') || '{}').uiLanguage === t; }
      catch { return false; }
    }, lang, { timeout: 10000 });
    await page.reload({ waitUntil: 'load' });
    await page.waitForSelector('#scopePane', { timeout: 10000 }).catch(() => {});
    await page.waitForTimeout(600);   // layout/fonts settle in the new locale
  }
  // Say which locale the APP actually came up in. A silent mismatch here writes English pixels into
  // help/de and help/uk, which is easy to miss (the files are written, just wrong).
  const uiLang = await page.evaluate(() => document.documentElement.lang || '(unset)');
  if (uiLang !== lang) console.warn(`WARNING: pass '${lang}' but the app reports lang='${uiLang}'`);
  const imgDir = join(WEB, 'help', lang, 'img');
  mkdirSync(imgDir, { recursive: true });
  for (const spec of todo) {
    if (!spec.ready) { if (lang === 'en') { console.log(`skip (web UI not ready): ${spec.id} -> ${spec.file}`); skipped++; } continue; }
    const out = join(imgDir, spec.file);
    // shot() -> ElementHandle, {clip: rect} (a region no single element wraps - the
    // tab-strip + sibling panel shots), or null for the whole 1280×768 window.
    const el = spec.shot ? await spec.shot(page) : null;
    if (el && el.clip) await page.screenshot({ path: out, clip: el.clip });
    else if (el) await el.screenshot({ path: out });
    else await page.screenshot({ path: out });
    if (spec.after) await spec.after(page);   // per-spec cleanup (close the modal)
    console.log(`captured: ${lang}/${spec.id} -> help/${lang}/img/${spec.file}`);
    done++;
  }
}

await browser.close();
s.close();
console.log(`\n${done} captured, ${skipped} skipped.`);
