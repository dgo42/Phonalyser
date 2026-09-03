// Browser end-to-end tests for the bench client - C3, C4, C5, and B14 / B16 / B18 / B19 / B20.
//
// These were reported BROKEN on the bench while every node test covering them was green, which is
// the whole reason this file exists: a reload that must keep a preference, a double-click that
// must reach a delegated handler, a rate list that must be re-read after a reconnect, a session
// that must keep answering a keepalive while a measurement computes, and two dialogs whose
// stacking and focus are facts about Bootstrap. Nothing here is doubled except the bench's
// hardware.
//
// Run:  node test/e2e/net-bench-e2e.mjs        (from web/)
// Exits non-zero if any check fails.

import { chromium } from 'playwright';
import { BenchServer } from './bench-server.mjs';

let passed = 0, failed = 0;
function check(name, ok, detail) {
  if (ok) { passed++; console.log(`PASS: ${name}${detail ? ` - ${detail}` : ''}`); }
  else { failed++; console.log(`FAIL: ${name}${detail ? ` - ${detail}` : ''}`); }
}

/** The bench's second offer - DIFFERENT rates and widths, which is what C5 is about. */
function otherBackends() {
  const formats = [{ rate: 192000, bits: 32, channels: 2 }, { rate: 384000, bits: 32, channels: 2 }];
  return [{
    backend: 'JAVASOUND', displayName: 'Java Sound',
    devices: [
      { index: 0, name: 'Bench ADC', description: 'Bench ADC (bench)', vendor: 'Bench Instruments',
        input: true, output: false, hasBitDepth: true, formats, lock: null, cal: null, card: null },
      { index: 0, name: 'Bench DAC', description: 'Bench DAC (bench)', vendor: 'Bench Instruments',
        input: false, output: true, hasBitDepth: true, formats, lock: null, cal: null, card: null },
    ],
  }];
}

/** TWO bench backends, each with devices of its own - what a switch away and back needs (B16). */
function twoBenchBackends() {
  const formats = [{ rate: 48000, bits: 24, channels: 2 }, { rate: 96000, bits: 24, channels: 2 }];
  const pair = (backend) => ({
    backend, displayName: backend,
    devices: [true, false].map((input) => ({
      index: 0, name: `${backend} ${input ? 'ADC' : 'DAC'}`,
      description: `${backend} ${input ? 'ADC' : 'DAC'} (bench)`, vendor: 'Bench Instruments',
      input, output: !input, hasBitDepth: true, formats, lock: null, cal: null, card: null,
    })),
  });
  return [pair('JAVASOUND'), pair('WASAPI')];
}

/** Waits until `fn` (evaluated in the page) is truthy, or gives up. */
async function until(page, fn, arg, timeoutMs = 8000, what = 'condition') {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await page.evaluate(fn, arg)) return true;
    if (Date.now() > deadline) { console.log(`  (timed out waiting for ${what})`); return false; }
    await page.waitForTimeout(100);
  }
}

const backendOptions = () => [...document.querySelectorAll('#backendSel option')].map((o) => o.value);
const rateOptions = () => [...document.querySelectorAll('#inRate option')].map((o) => o.value);
const outRateOptions = () => [...document.querySelectorAll('#outRate option')].map((o) => o.value);
const depthOptions = () => [...document.querySelectorAll('#inDepth option')].map((o) => o.value);
const deviceOptions = () => [...document.querySelectorAll('#inSel option')].map((o) => o.value);

/** Opens the Preferences dialog the way its own opener does, and waits for it to be shown. */
async function openPrefs(page) {
  await page.evaluate(() => {
    window.bootstrap.Modal.getOrCreateInstance(document.getElementById('prefsModal')).show();
  });
  await page.waitForSelector('#prefsModal.show', { timeout: 5000 });
  // The show handler stages ASYNCHRONOUSLY (it re-reads the backend's formats), and the combos
  // pass through an intermediate fill on the way. Wait for the rate combo to stop moving rather
  // than for a fixed delay - reading it mid-staging is the test's own race, not the app's.
  let last = null, stable = 0;
  for (let i = 0; i < 80 && stable < 8; i++) {
    const now = await page.evaluate(() => `${document.getElementById('inRate').value}|`
      + `${document.getElementById('inSel').options.length}`);
    stable = (now === last) ? stable + 1 : 0;
    last = now;
    await page.waitForTimeout(100);
  }
}

async function closePrefs(page, ok = true) {
  await page.click(ok ? '#prefsOk' : '#prefsCancel');
  await until(page, () => !document.querySelector('#prefsModal.show'), null, 5000, 'prefs to close');
  // The store writes through a DEBOUNCED save (Preferences._requestSave), so the commit reaches
  // localStorage a moment after the OK returns. Reading - or reloading - before that flush reads
  // the state the operator just changed, and blames the reload for it. No operator reloads
  // 200 ms after clicking OK; the test must not either.
  await page.waitForTimeout(1500);
}

async function openServers(page) {
  await page.click('#netServers');
  await page.waitForSelector('#netServersModal.show', { timeout: 5000 });
  await page.waitForTimeout(300);
}

async function closeServers(page) {
  await page.evaluate(() => {
    window.bootstrap.Modal.getOrCreateInstance(document.getElementById('netServersModal')).hide();
  });
  await until(page, () => !document.querySelector('#netServersModal.show'), null, 5000, 'servers to close');
  await page.waitForTimeout(200);
}

/** True while the page holds a live session (the backend combo carries a net: entry). */
const isConnected = () => [...document.querySelectorAll('#backendSel option')]
  .some((o) => o.value.startsWith('net:'));

/**
 * True once the boot has actually ARRIVED at the bench: the backend the combo is SHOWING is the
 * server's, and its device list has been filled from the catalogue. Every scenario waits for
 * this before touching a control.
 *
 * It is deliberately not "a net: entry exists" (that is true the moment backend.list answers,
 * while the scan is still running) and not the splash either: a dialog opened mid-boot stages
 * the OUTGOING backend's values and commits them to the outgoing backend's slot, so a test that
 * starts there is testing its own impatience.
 */
const benchReady = () => document.getElementById('backendSel').value.startsWith('net:')
  && document.getElementById('inSel').options.length > 0
  && document.getElementById('inRate').options.length > 0
  // The scan's own completion line - the app saying it has finished enumerating. Without it the
  // combos can be filled while the scan's tail (which seeds the live config from them) has not
  // run, and a dialog opened in that window commits nothing.
  && /\d+ input\(s\)/.test(document.getElementById('status').textContent || '');

async function main() {
  const bench = await new BenchServer().start();
  const browser = await chromium.launch({ headless: true });
  // ONE context for the whole run: localStorage has to survive a reload, which is C3's subject.
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  try {
    await page.goto(`${bench.origin}/index.html`, { waitUntil: 'domcontentloaded' });
    const up = await until(page, isConnected, null, 15000, 'the auto-connect to the serving bench');
    check('the page a bench serves dials that bench at load', up,
      up ? '' : 'no net: backend appeared in the combo');
    if (!up) return;
    await until(page, benchReady, null, 20000, 'the boot to reach the bench');

    await c3ReloadKeepsTheRemoteSelection(page, bench);
    await c4DoubleClickConnects(page, bench);
    await c5ReconnectRereadsTheFormats(page, bench);
    await b16SwitchingBackReRoutesTheSession(page, bench);
    await b18ARefusedScanKeepsWhatIsShown(page, bench);
    await b19b20DialogsOpenedFromPreferences(page, bench);
    // LAST: it is the one case that can cost the session, so nothing after it would be testing
    // what it thinks it is.
    await b14DeconvolveKeepsTheSessionAlive(page, bench);
    // Its OWN bench and page: this one is about the state a page BOOTS in, which the scenarios
    // above have long since changed on the shared one.
    await b27RangesWithoutABackendChange(browser);

    const fatal = errors.filter((e) => !/favicon|net::ERR/.test(e));
    check('no uncaught page errors', fatal.length === 0, fatal.slice(0, 3).join(' | '));
  } finally {
    await browser.close();
    await bench.stop();
    console.log(`e2e: ${passed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
  }
}

// ---------------------------------------------------------------------------
// B27 - the Ranges block on a page that never changed its backend
// ---------------------------------------------------------------------------
//
// The bench analyzer's calibration and ranges reach this client through one sync, and the Ranges
// table renders from the device-provided card that sync writes - nothing else. The sync used to
// hang off the backend combo's CHANGE handler, so a page whose selection was the server's from
// the first paint never ran it and showed no Ranges for any backend. That page is every page a
// Phonalyser server serves, and it is exactly this one: the harness serves the app from the bench
// itself, the boot auto-connects, and the backend combo arrives already reading net:<backend>.
// Nothing below touches that combo - the whole point is that no change event ever happens.
async function b27RangesWithoutABackendChange(browser) {
  // A bench whose ONLY backend is its analyzer, so the boot settles on it with no help: the
  // shared bench above offers JavaSound, which has no analyzer behind it and correctly shows no
  // ranges (a JavaSound device on a server reaches no QA40x - maintainer bench B5).
  const formats = [{ rate: 48000, bits: 24, channels: 2 }, { rate: 96000, bits: 24, channels: 2 }];
  const analyzer = [{
    backend: 'QA40X', displayName: 'QA40x',
    devices: [true, false].map((input) => ({
      index: 0, name: 'QA403', description: 'QA403 (bench)', vendor: 'QuantAsylum',
      input, output: !input, hasBitDepth: true, formats, lock: null, cal: null, card: null,
    })),
  }];
  const bench = await new BenchServer({ serverId: 'e2e-bench-qa40x', name: 'E2E Analyzer',
    backends: analyzer }).start();
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(`${bench.origin}/index.html`, { waitUntil: 'domcontentloaded' });
    if (!await until(page, isConnected, null, 15000, 'the auto-connect')) {
      check('B27 setup: the analyzer bench page connects', false);
      return;
    }
    await until(page, benchReady, null, 20000, 'the boot to reach the bench');
    const shown = await page.evaluate(() => document.getElementById('backendSel').value);
    check('B27 setup: the page booted ON the bench analyzer, with no switch', shown === 'net:QA40X',
      `backend combo reads ${shown}`);

    await openPrefs(page);
    const ranges = await until(page, () => document.querySelectorAll('#inRanges > *').length > 0,
      null, 10000, 'the input Ranges rows');
    // The range LABELS are the values of their input boxes, not element text (the row is the card
    // editor's own widget), so read them the way the operator sees them.
    const seen = await page.evaluate(() => ({
      rows: document.querySelectorAll('#inRanges > *').length,
      labels: [...document.querySelectorAll('#inRanges input[type=text]')].map((i) => i.value).filter(Boolean),
      active: (() => {
        const on = document.querySelector('#inRanges input[type=radio]:checked');
        const row = on ? on.closest('*').parentElement : null;
        const box = row ? row.querySelector('input[type=text]') : null;
        return box ? box.value : null;
      })(),
      caption: !document.getElementById('inRangesLabel').classList.contains('d-none'),
    }));
    check('B27: the Ranges block is filled for the boot-selected bench analyzer',
      ranges && seen.labels.length > 0, `${seen.rows} row(s), labels=[${seen.labels.join(', ')}]`);
    check('B27: the ranges are the ANALYZER\'s, at the position the bench reports in force',
      seen.labels.includes('12 dBV') && seen.active === '12 dBV',
      `active=${seen.active} of [${seen.labels.join(', ')}] (the bench answered activeInputDbv 12)`);
    check('B27: and the caption is shown with them', ranges && seen.caption);
    await closePrefs(page, false);
  } finally {
    await context.close();
    await bench.stop();
  }
}

// ---------------------------------------------------------------------------
// C3 - "devices + audio settings for the remote server are gone after a reload"
// ---------------------------------------------------------------------------

async function c3ReloadKeepsTheRemoteSelection(page, bench) {
  await openPrefs(page);
  const rates = await page.evaluate(rateOptions);
  const devices = await page.evaluate(deviceOptions);
  check('C3 setup: the bench\'s devices and rates are on offer', rates.length > 0 && devices.length > 0,
    `devices=[${devices}] rates=[${rates}]`);
  // Pick something that is NOT the first entry, so a "reverted to the default" outcome is visible.
  const wantedRate = rates.length > 1 ? rates[1] : rates[0];
  await page.selectOption('#inRate', wantedRate);
  await page.selectOption('#outRate', wantedRate);
  const backend = await page.evaluate(() => document.getElementById('backendSel').value);
  const device = await page.evaluate(() => document.getElementById('inSel').value);
  await closePrefs(page, true);
  // The COMMIT and the RESTORE are two different claims, and only one of them was ever broken.
  // Reading the stored block here says which: a slot that never received the selection is an OK
  // that did not commit, not a reload that lost it.
  const stored = await page.evaluate((key) => {
    const root = JSON.parse(localStorage.getItem('phonalyser.preferences') || '{}');
    return (root.perBackend || {})[key] || null;
  }, backend);
  check('C3: OK commits the selection to the bench\'s own slot',
    stored != null && String(stored.inputSampleRate) === wantedRate,
    `${backend} -> ${JSON.stringify(stored)}`);

  await page.reload({ waitUntil: 'domcontentloaded' });
  const up = await until(page, isConnected, null, 15000, 'the auto-connect after the reload');
  check('C3: the session comes back after a reload', up);
  if (!up) return;
  await until(page, benchReady, null, 20000, 'the boot to reach the bench');
  await openPrefs(page);
  const after = await page.evaluate(() => ({
    backend: document.getElementById('backendSel').value,
    device: document.getElementById('inSel').value,
    inRate: document.getElementById('inRate').value,
    outRate: document.getElementById('outRate').value,
  }));
  check('C3: the remote BACKEND survives the reload', after.backend === backend,
    `${backend} -> ${after.backend}`);
  check('C3: the remote DEVICE survives the reload', after.device === device,
    `${device} -> ${after.device}`);
  check('C3: the remote RATES survive the reload',
    after.inRate === wantedRate && after.outRate === wantedRate,
    `wanted ${wantedRate}, got in=${after.inRate} out=${after.outRate}`);
  await closePrefs(page, false);
}

// ---------------------------------------------------------------------------
// C4 - "double-click on a server row doesn't connect"
// ---------------------------------------------------------------------------

async function c4DoubleClickConnects(page, bench) {
  await openPrefs(page);
  await openServers(page);
  await page.click('#netDisconnect');
  await page.waitForTimeout(500);
  const rows = await page.evaluate(() => document.querySelectorAll('#netServersRows tr').length);
  check('C4 setup: the bench is listed and this client is disconnected', rows > 0,
    `${rows} row(s)`);

  await page.dblclick('#netServersRows tr:first-child');
  const back = await until(page, () => [...document.querySelectorAll('#netServersRows tr')]
    .some((r) => /connected/i.test(r.textContent)), null, 8000, 'the row to read connected');
  check('C4: a double-click on a server row connects it', back);

  await closeServers(page);
  await closePrefs(page, false);
}

// ---------------------------------------------------------------------------
// C5 - "reconnect doesn't reload available samplerates/bitdepths"
// ---------------------------------------------------------------------------

async function c5ReconnectRereadsTheFormats(page, bench) {
  await openPrefs(page);
  const before = { rates: await page.evaluate(rateOptions), depths: await page.evaluate(depthOptions),
    outRates: await page.evaluate(outRateOptions) };
  await openServers(page);
  await page.click('#netDisconnect');
  await page.waitForTimeout(500);

  // The bench's hardware changed while nobody was connected - the case a client can only get
  // right by re-reading the catalogue on the new session.
  bench.setBackends(otherBackends());

  await page.click('#netServersRows tr:first-child');
  await page.click('#netConnect');
  const back = await until(page, isConnected, null, 10000, 'the reconnect');
  check('C5 setup: the reconnect succeeds', back);
  if (!back) { await closeServers(page); await closePrefs(page, false); return; }
  await closeServers(page);
  await page.waitForTimeout(800);

  const after = { rates: await page.evaluate(rateOptions), depths: await page.evaluate(depthOptions),
    outRates: await page.evaluate(outRateOptions) };
  check('C5: the input RATE list is re-read from the new session',
    after.rates.includes('192000') && !after.rates.includes('48000'),
    `[${before.rates}] -> [${after.rates}]`);
  check('C5: the output RATE list is re-read too',
    after.outRates.includes('192000') && !after.outRates.includes('48000'),
    `[${before.outRates}] -> [${after.outRates}]`);
  check('C5: the bit-DEPTH list is re-read from the new session',
    after.depths.includes('32') && !after.depths.includes('24'),
    `[${before.depths}] -> [${after.depths}]`);
  await closePrefs(page, false);
}

// ---------------------------------------------------------------------------
// B16 - "switching back to net:WASAPI shows an empty card combo"
// ---------------------------------------------------------------------------

async function b16SwitchingBackReRoutesTheSession(page, bench) {
  // The session answers a client's reads only for the backend it was last ROUTED at
  // (backend.select). Rebuilding the combos from the dialog's cache leaves it pointed at whatever
  // was scanned last, so the card list came back empty until "Scan devices" was pressed - the
  // repro is a switch away and back with no click on Scan.
  bench.setBackends(twoBenchBackends());
  bench.broadcastDevicesChanged();
  const offered = await until(page, () => [...document.querySelectorAll('#backendSel option')]
    .filter((o) => o.value.startsWith('net:')).length === 2, null, 15000, 'both bench backends');
  check('B16 setup: the bench offers two backends', offered);
  if (!offered) return;

  await openPrefs(page);
  const inputNames = () => [...document.querySelectorAll('#inSel option')].map((o) => o.textContent);
  await page.selectOption('#backendSel', 'net:WASAPI');
  const away = await until(page, () => [...document.querySelectorAll('#inSel option')]
    .some((o) => o.textContent.includes('WASAPI ADC')), null, 10000, 'the other backend\'s devices');
  check('B16 setup: switching to the bench\'s other backend lists ITS devices', away,
    `[${await page.evaluate(inputNames)}]`);

  bench.selects.length = 0;
  await page.selectOption('#backendSel', 'net:JAVASOUND');
  const back = await until(page, () => [...document.querySelectorAll('#inSel option')]
    .some((o) => o.textContent.includes('JAVASOUND ADC')), null, 10000, 'the first backend again');
  check('B16: switching BACK shows that backend\'s devices again - with no Scan click', back,
    `[${await page.evaluate(inputNames)}]`);
  check('B16: and the session was re-routed there', bench.selects.includes('JAVASOUND'),
    `backend.select since the switch: [${bench.selects}]`);
  const cards = await page.evaluate(() =>
    [...document.querySelectorAll('#inCardSel option')].map((o) => o.textContent));
  check('B16: the CARD combo is filled - a session routed elsewhere answers cards.list with null',
    cards.includes('Bench card'), `[${cards}]`);
  await closePrefs(page, false);
}

// ---------------------------------------------------------------------------
// B18 - "a scan that found nothing must not empty the combos"
// ---------------------------------------------------------------------------

async function b18ARefusedScanKeepsWhatIsShown(page, bench) {
  await openPrefs(page);
  const shown = await page.evaluate(deviceOptions);
  const cardsShown = await page.evaluate(() =>
    [...document.querySelectorAll('#inCardSel option')].map((o) => o.textContent));
  check('B18 setup: the combos are filled before the refused scan', shown.length > 0,
    `devices=[${shown}] cards=[${cardsShown}]`);

  // The bench refuses backend.select - a backend it has but cannot run. The client's listing is
  // then empty, and emptying the combos on it loses the operator's device AND its card.
  bench.refuseSelect = true;
  await page.click('#scan');
  await page.waitForTimeout(1500);
  bench.refuseSelect = false;

  const after = await page.evaluate(deviceOptions);
  const cardsAfter = await page.evaluate(() =>
    [...document.querySelectorAll('#inCardSel option')].map((o) => o.textContent));
  check('B18: a refused scan leaves the device combo exactly as it was',
    JSON.stringify(after) === JSON.stringify(shown), `[${shown}] -> [${after}]`);
  check('B18: and the card combo with it',
    JSON.stringify(cardsAfter) === JSON.stringify(cardsShown), `[${cardsShown}] -> [${cardsAfter}]`);
  const status = await page.evaluate(() => document.getElementById('status').textContent);
  check('B18: the status line says so instead', /already shown are kept/.test(status), status);

  // ...but a SWITCH is not a rescan of what is shown: the options belong to the backend being left,
  // and keeping them would offer this backend's devices as the other one's - an id that OK would
  // write into its slot for the first open to fail on.
  bench.refuseSelect = true;
  await page.selectOption('#backendSel', 'net:WASAPI');
  const moved = await until(page, () => ![...document.querySelectorAll('#inSel option')]
    .some((o) => o.textContent.includes('JAVASOUND ADC')), null, 10000, 'the foreign devices to go');
  const shownNow = await page.evaluate(deviceOptions);
  check('B16/B18: a switch to a backend that answers nothing drops the previous devices', moved,
    `[${shownNow}]`);
  check('B16/B18: and shows that backend\'s own listing instead',
    shownNow.length === 0 || shownNow.every((id) => id.startsWith('WASAPI')), `[${shownNow}]`);
  bench.refuseSelect = false;
  await page.selectOption('#backendSel', 'net:JAVASOUND');
  await until(page, () => [...document.querySelectorAll('#inSel option')]
    .some((o) => o.textContent.includes('JAVASOUND ADC')), null, 10000, 'the bench backend back');
  await closePrefs(page, false);
}

// ---------------------------------------------------------------------------
// B19 / B20 - a dialog opened from inside Preferences: its own layer, and the focus
// ---------------------------------------------------------------------------

async function b19b20DialogsOpenedFromPreferences(page, bench) {
  await openPrefs(page);
  await openServers(page);
  const focus = await page.evaluate(() => {
    const dialog = document.getElementById('netServersModal');
    return { inside: dialog.contains(document.activeElement),
      on: document.activeElement ? (document.activeElement.id || document.activeElement.className) : null };
  });
  check('B20: the servers dialog opens with the keyboard focus INSIDE it', focus.inside,
    `activeElement: ${focus.on}`);
  await closeServers(page);

  // The QA40x settings dialog, built the way its manager builds it (the USB read that supplies the
  // telemetry is the only thing standing in) - over the open Preferences dialog, with the real
  // Bootstrap that gives every modal and backdrop the same two layers.
  const layers = await page.evaluate(async () => {
    const { Qa40xSettingsDialog } = await import('/js/qa40x/qa40x-settings-dialog.js');
    const { Qa40xDeviceInfo } = await import('/js/qa40x/qa40x-device-info.js');
    const info = new Qa40xDeviceInfo('fw-60', '4.98 V', '0.25 A', '0.12 A', '31.5 °C',
      '0x00000003', '0x0000000A', '0A1B2C3D');
    const dialog = new Qa40xSettingsDialog(null, info);
    const closed = dialog.open(false);
    await new Promise((resolve) => setTimeout(resolve, 800));   // Bootstrap's fade-in
    const root = document.getElementById('qa40xSettingsModal');
    const backdrops = document.querySelectorAll('.modal-backdrop');
    const own = backdrops[backdrops.length - 1];
    const active = document.activeElement;
    const out = {
      modal: parseInt(getComputedStyle(root).zIndex, 10),
      backdrop: parseInt(getComputedStyle(own).zIndex, 10),
      prefs: parseInt(getComputedStyle(document.getElementById('prefsModal')).zIndex, 10),
      focus: active ? active.id : null,
      inside: root.contains(active),
    };
    window.bootstrap.Modal.getOrCreateInstance(root).hide();
    await closed;
    return out;
  });
  check('B19: the QA40x settings dialog is raised above the dialog it was opened from',
    layers.modal > layers.prefs, `qa40x=${layers.modal} prefs=${layers.prefs}`);
  check('B19: and its BACKDROP covers that dialog, so Preferences is really out of reach',
    layers.backdrop > layers.prefs, `backdrop=${layers.backdrop} prefs=${layers.prefs}`);
  check('B20: it opens with the focus on its OK button (Java\'s default button)',
    layers.inside && layers.focus === 'qa40xSettingsOk', `activeElement: ${layers.focus}`);
  await closePrefs(page, false);
}

// ---------------------------------------------------------------------------
// B14 - "the first freqresp run dies with 'the connection was closed'"
// ---------------------------------------------------------------------------

async function b14DeconvolveKeepsTheSessionAlive(page, bench) {
  // The measurement's own deconvolution step, sized as the shipped default sizes it: the sweep is
  // authored so that lead-in + sweep + tail is exactly freqRespFftSize (2^22), and the capture
  // loop collects in 50 ms polls - so a record that overruns its nominal length by one poll makes
  // the analyzer's nextPow2 land on 2^23. Run on the browser's MAIN thread that arithmetic blocks
  // every message handler for as long as it takes, including the one that answers this bench's
  // 500 ms pings, and four unanswered pings is a dead client by spec 4.1. The capture itself is
  // not doubled here: the bench streams no PCM, and B14 is about what happens after the samples
  // are in.
  const killsBefore = bench.kills.length;
  const run = await page.evaluate(async (samples) => {
    const { renderLogSweep } = await import('/js/freqresp/farina-sweep.js');
    const { binAlignedFreqs } = await import('/js/freqresp/deconvolve.js');
    const sampleRate = 48000, leadInSamples = 2400;
    const sweepSamples = samples - leadInSamples;
    const sweepRef = renderLogSweep(20, 20000, sweepSamples, sampleRate);
    const recLeft = new Float64Array(samples);
    const recRight = new Float64Array(samples);
    for (let i = 0; i < sweepSamples; i++) {
      recLeft[leadInSamples + i] = sweepRef[i] * 0.5;
      recRight[leadInSamples + i] = sweepRef[i] * 0.5;
    }
    const freqs = binAlignedFreqs(20, 20000, sampleRate / samples, 200);
    const started = performance.now();
    const { calL, calR } = await window.__freqRespController._deconvolveBoth({ recLeft, recRight,
      sweepRef, leadInSamples, sampleRate, freqs, amplitudeVRms: 1, adcFsVoltageRms: 1,
      fade: 1000, applySavGol: false });
    const real = (cal) => cal != null && cal.magLin.length === freqs.length
      && [...cal.magLin].every((v) => Number.isFinite(v) && v > 0)
      && [...cal.phaseRad].every(Number.isFinite);
    return { elapsed: performance.now() - started, wanted: freqs.length,
      points: calL == null ? 0 : calL.freqs.length, sane: real(calL) && real(calR) };
  }, (1 << 22) + 4800);

  // The death lands half a period after the fourth miss (the count is checked BEFORE the next
  // ping goes out - net-connection.js runs the same arithmetic against the server), so a block
  // that is to prove anything has to outlast 2.5 s.
  check('B14 setup: the deconvolution outlasts the keepalive budget (4 × 500 ms)', run.elapsed > 2500,
    `${run.elapsed.toFixed(0)} ms of work at the shipped FFT size - a red needs it over 2500`);
  // Without this, a seam that answered {} would satisfy every timing check around it.
  check('B14: the worker hands back a real calibration for BOTH channels',
    run.sane && run.points === run.wanted,
    `${run.points} of ${run.wanted} points, magnitudes finite and positive: ${run.sane}`);
  const killed = bench.kills.slice(killsBefore);
  check('B14: the bench never declared this client dead while the measurement computed',
    killed.length === 0, killed.map((k) => `${k.client} missed ${k.missed} pings`).join(' | '));
  check('B14: and the session is still there afterwards', bench.sessions.size > 0,
    `${bench.sessions.size} live session(s)`);
}

await main();
