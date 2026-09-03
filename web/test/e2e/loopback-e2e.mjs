// Live proof of the loopback browser backend (round F, clauses F2 / F3 / F4).
//
// Run:  node test/e2e/loopback-e2e.mjs        (from web/)
// Exits non-zero if any assertion fails. Prints 'loopback e2e: N passed, M failed'.
//
// It drives a REAL Chromium (Playwright) against the actual web/ source tree, served over HTTP
// by the same tiny static server the scope smoke uses, and it proves three separate things:
//
//   F2 - the backend is selectable in the REAL Preferences dialog, its one device is listed for
//        both directions, its format table is the 15-rate ladder (32000 included) at 16/20/24/32
//        with the depth rows visible, and the selection plus its per-backend block survive a
//        reload through localStorage.
//   F4 - with NO playback lane, the delivered samples are NOT digital zeros: their broadband
//        level is the arithmetic TPDF floor 3.0103 - 6.0206*N dBFS of the selected depth, and
//        two depths differ by exactly 6.0206 dB per bit.
//   F3 - with a tone played through the crossing, the captured fundamental reads 20*log10(A)
//        dBFS and the floor underneath it has not moved.
//
// The numeric half runs the REAL lanes off the served module tree and stages every batch through
// the REAL SignalBuffer ring, so the Float64 staging is proved end to end rather than asserted.
// The tone start deliberately carries a ditherBits of 12: a configured dither APPLIES, so the
// residual floor under the tone lands at the 12-bit figure (about -71 dBFS) instead of the
// 24-bit default - the F3 assertion is also the live proof of the dither-override rule.

import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));   // web/

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
  '.yaml': 'text/plain; charset=utf-8',
  '.properties': 'text/plain; charset=utf-8',
};

function startServer() {
  const server = http.createServer((req, res) => {
    let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (urlPath === '/') urlPath = '/index.html';
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

let passed = 0, failed = 0;
function check(name, ok, detail) {
  if (ok) { passed++; console.log('PASS: ' + name + (detail ? ' - ' + detail : '')); }
  else { failed++; console.log('FAIL: ' + name + (detail ? ' - ' + detail : '')); }
}

/** Waits until `fn` (evaluated in the page) is truthy, or gives up. */
async function until(page, fn, arg, timeoutMs = 15000, what = 'condition') {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await page.evaluate(fn, arg)) return true;
    if (Date.now() > deadline) { console.log('  (timed out waiting for ' + what + ')'); return false; }
    await page.waitForTimeout(100);
  }
}

/** Opens Preferences the way its own opener does, then waits for the combos to stop moving. */
async function openPrefs(page) {
  await page.evaluate(() => {
    window.bootstrap.Modal.getOrCreateInstance(document.getElementById('prefsModal')).show();
  });
  await page.waitForSelector('#prefsModal.show', { timeout: 8000 });
  let last = null, stable = 0;
  for (let i = 0; i < 80 && stable < 8; i++) {
    const now = await page.evaluate(() => document.getElementById('inRate').value + '|'
      + document.getElementById('inSel').options.length + '|'
      + document.getElementById('inDepth').options.length);
    stable = (now === last) ? stable + 1 : 0;
    last = now;
    await page.waitForTimeout(100);
  }
}

async function closePrefs(page) {
  await page.click('#prefsOk');
  await until(page, () => !document.querySelector('#prefsModal.show'), null, 8000, 'prefs to close');
  // The store writes through a DEBOUNCED save, so the commit reaches localStorage a moment after
  // OK returns. Reading - or reloading - before that flush reads the state just changed.
  await page.waitForTimeout(1500);
}

const LADDER = [8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000,
  176400, 192000, 352800, 384000, 705600, 768000];
const DEPTHS = [16, 20, 24, 32];

// ---------------------------------------------------------------------------
// The numeric probe, evaluated IN THE PAGE against the served module tree.
// ---------------------------------------------------------------------------
//
// Returns, for one run: the broadband floor in dBFS, the per-window fundamental level, how many
// windows carried the tone, whether L and R are identical, and whether anything was non-zero.
const PROBE = async ({ depth, rate, collectMs, tone }) => {
  const { LoopbackDeviceManager } = await import('/js/loopback/loopback-device-manager.js');
  const { SignalBuffer } = await import('/js/audio/signal-buffer.js');
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  const manager = new LoopbackDeviceManager({
    inputDepthOf: () => depth,
    outputDepthOf: () => depth,
  });
  const capture = manager.openCapture(null);
  await capture.open(null, rate);
  const ring = new SignalBuffer(rate, 30);
  let batches = 0, lrIdentical = true, ringType = '';
  let playback = null;
  if (tone) {
    playback = manager.openPlayback(null, 0);
    await playback.open({ sampleRate: rate });
    await playback.start({
      form: 'SINE',
      frequency: tone.hz,
      amplitudeVRms: tone.vrms,
      dacFsVoltageAmpl: 1.0,
      // DELIBERATELY non-zero: a configured dither applies, and the residual-floor
      // assertion below expects the 12-bit figure.
      ditherBits: 12,
      outputChannels: 'BOTH',
      rightLaneScale: 1.0,
      control: null,
    });
  }
  await capture.start((b) => {
    batches++;
    if (ringType === '') ringType = b.l.constructor.name;
    for (let i = 0; i < b.n; i += 97) { if (b.l[i] !== b.r[i]) lrIdentical = false; }
    ring.appendBatch(b.l, b.r, b.n);
  }, null);
  await sleep(collectMs);
  await capture.stop();
  if (playback) await playback.close();
  await capture.close();

  const blockFrames = Math.max(1, Math.floor(rate / 50));
  const total = ring.getWritePos();
  // Skip the FIRST delivered block, exactly as the desktop test skips block 0.
  const start = blockFrames;
  const count = total - start;
  if (count <= 0) return { error: 'nothing delivered', batches, total };
  const l = new Float64Array(count);
  const r = new Float64Array(count);
  ring.readStartingAt(start, count, l, r);

  let nonZero = 0;
  for (let i = 0; i < count; i++) if (l[i] !== 0) nonZero++;

  const dbfs = (rmsValue) => 20 * Math.log10(rmsValue * Math.SQRT2);
  const rmsOf = (a, from, n) => {
    let s = 0;
    for (let i = 0; i < n; i++) { const v = a[from + i]; s += v * v; }
    return Math.sqrt(s / n);
  };

  // Whole-run broadband level (what the arithmetic floor predicts when nothing is playing).
  const broadbandDbfs = dbfs(rmsOf(l, 0, count));

  // Per-window fit at the tone frequency; each window is one delivered block, which at 48 kHz
  // and 1 kHz is exactly 20 whole cycles, so the projection returns the peak amplitude directly.
  const windows = Math.floor(count / blockFrames);
  const amps = [];
  const residualDb = [];
  if (tone) {
    const w = 2 * Math.PI * tone.hz / rate;
    for (let k = 0; k < windows; k++) {
      const at = k * blockFrames;
      let c = 0, s = 0;
      for (let i = 0; i < blockFrames; i++) {
        const v = l[at + i];
        c += v * Math.cos(w * i);
        s += v * Math.sin(w * i);
      }
      c = (2 / blockFrames) * c;
      s = (2 / blockFrames) * s;
      const amp = Math.hypot(c, s);
      amps.push(amp);
      let acc = 0;
      for (let i = 0; i < blockFrames; i++) {
        const fit = c * Math.cos(w * i) + s * Math.sin(w * i);
        const e = l[at + i] - fit;
        acc += e * e;
      }
      residualDb.push(dbfs(Math.sqrt(acc / blockFrames)));
    }
  }
  const median = (xs) => {
    if (xs.length === 0) return NaN;
    const c = [...xs].sort((a, b) => a - b);
    return c[Math.floor(c.length / 2)];
  };
  const expectedPeak = tone ? tone.vrms * Math.SQRT2 : 0;
  const toneWindows = amps.filter((a) => a > 0.5 * expectedPeak);
  const toneResiduals = residualDb.filter((_, i) => amps[i] > 0.5 * expectedPeak);

  return {
    batches, total, frames: count, nonZero, lrIdentical, ringType,
    broadbandDbfs, windows,
    toneWindowCount: toneWindows.length,
    medianAmp: median(toneWindows),
    medianAmpDbfs: toneWindows.length ? 20 * Math.log10(median(toneWindows)) : NaN,
    medianResidualDbfs: median(toneResiduals),
  };
};

const expectedFloorDbfs = (n) => 3.0103 - 6.0206 * n;

async function main() {
  const { server, port } = await startServer();
  const origin = 'http://127.0.0.1:' + port;
  const { chromium } = await import('playwright');
  const launchArgs = [
    '--use-fake-device-for-media-stream',
    '--use-fake-ui-for-media-stream',
    '--autoplay-policy=no-user-gesture-required',
  ];
  let browser, headed = true;
  try {
    browser = await chromium.launch({ headless: false, args: launchArgs });
  } catch (e) {
    headed = false;
    browser = await chromium.launch({ headless: true, args: launchArgs });
  }
  console.log('chromium launched ' + (headed ? 'HEADED' : 'HEADLESS (headed launch refused)'));
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  const missing = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  // Name what 404s: the unbundled source tree legitimately lacks the files the BUILD emits
  // (version.json), and a nameless "404" tells a later reader nothing.
  page.on('response', (res) => { if (res.status() === 404) missing.push(res.url().replace(origin, '')); });

  try {
    await page.goto(origin + '/index.html', { waitUntil: 'domcontentloaded' });
    const booted = await until(page, () => document.getElementById('backendSel')
      && document.getElementById('backendSel').options.length > 0, null, 25000, 'the app to boot');
    check('the app boots and offers a backend list', booted);
    if (!booted) return;

    // ---------------- F2: selection, format table, persistence ----------------
    await openPrefs(page);
    const offered = await page.evaluate(() => [...document.querySelectorAll('#backendSel option')]
      .map((o) => o.value));
    check('LOOPBACK is offered beside the other local backends', offered.includes('LOOPBACK'),
      offered.join(','));

    await page.selectOption('#backendSel', 'LOOPBACK');
    await until(page, () => document.querySelectorAll('#inRate option').length === 15,
      null, 10000, 'the loopback rate ladder');
    await page.waitForTimeout(500);

    const combos = await page.evaluate(() => ({
      inRates: [...document.querySelectorAll('#inRate option')].map((o) => Number(o.value)),
      outRates: [...document.querySelectorAll('#outRate option')].map((o) => Number(o.value)),
      inDepths: [...document.querySelectorAll('#inDepth option')].map((o) => Number(o.value)),
      outDepths: [...document.querySelectorAll('#outDepth option')].map((o) => Number(o.value)),
      inDevices: [...document.querySelectorAll('#inSel option')].map((o) => o.textContent),
      outDevices: [...document.querySelectorAll('#outSel option')].map((o) => o.textContent),
      depthRowsHidden: [...document.querySelectorAll('.depth-row')]
        .filter((el) => el.classList.contains('d-none')).length,
      depthRowCount: document.querySelectorAll('.depth-row').length,
    }));
    const sameList = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
    check('the input rate combo is the 15-rate ladder', sameList(combos.inRates, LADDER),
      combos.inRates.join(','));
    check('the output rate combo is the same ladder', sameList(combos.outRates, LADDER),
      combos.outRates.join(','));
    check('32000 is offered (unique to this backend)', combos.inRates.includes(32000)
      && combos.outRates.includes(32000));
    check('the input depth combo is 16/20/24/32', sameList(combos.inDepths, DEPTHS),
      combos.inDepths.join(','));
    check('the output depth combo is 16/20/24/32', sameList(combos.outDepths, DEPTHS),
      combos.outDepths.join(','));
    check('the depth rows are visible', combos.depthRowsHidden === 0
      && combos.depthRowCount > 0, combos.depthRowsHidden + ' of ' + combos.depthRowCount + ' hidden');
    check('the one device is listed for capture, carrying the card-binding name',
      combos.inDevices.length === 1 && combos.inDevices[0].includes('Phonalyser Loopback'),
      combos.inDevices.join(' | '));
    check('the same device is listed for playback',
      combos.outDevices.length === 1 && combos.outDevices[0].includes('Phonalyser Loopback'),
      combos.outDevices.join(' | '));

    await page.selectOption('#inRate', '48000');
    await page.selectOption('#outRate', '48000');
    await page.selectOption('#inDepth', '24');
    await page.selectOption('#outDepth', '24');
    await closePrefs(page);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await until(page, () => document.getElementById('backendSel')
      && document.getElementById('backendSel').options.length > 0, null, 25000, 'the reload to boot');
    const stored = await page.evaluate(() => {
      const root = JSON.parse(localStorage.getItem('phonalyser.preferences') || '{}');
      return { backend: root.backend, block: (root.perBackend || {}).LOOPBACK || null,
        shown: document.getElementById('backendSel').value };
    });
    check('the LOOPBACK selection survives a reload', stored.backend === 'LOOPBACK'
      && stored.shown === 'LOOPBACK', 'stored=' + stored.backend + ' shown=' + stored.shown);
    check('its per-backend block survives with the picked rates and depths',
      stored.block != null && stored.block.inputSampleRate === 48000
      && stored.block.outputSampleRate === 48000 && stored.block.inputBitDepth === 24
      && stored.block.outputBitDepth === 24, JSON.stringify(stored.block));

    // ---------------- F4: the dithered-silence floor ----------------
    const silence24 = await page.evaluate(PROBE,
      { depth: 24, rate: 48000, collectMs: 1200, tone: null });
    console.log('  silence@24: ' + JSON.stringify(silence24));
    const want24 = expectedFloorDbfs(24);
    check('capture with no playback delivers samples at all',
      !silence24.error && silence24.frames > 24000,
      'frames=' + silence24.frames + ' batches=' + silence24.batches);
    // TPDF dither of exactly one LSB peak rounds to zero for 75 percent of the samples - the
    // triangular density puts P(|v| less than 0.5 LSB) at 0.75 - and to plus or minus one LSB for
    // the rest, which is what makes the total error 0.5 LSB RMS. So the F4 claim is about the
    // FLOOR, not about every sample: minus-infinity zeros would be a run with NONE set.
    check('those samples are NOT digital zeros',
      silence24.nonZero > 0.15 * silence24.frames,
      silence24.nonZero + ' of ' + silence24.frames + ' non-zero ('
      + (100 * silence24.nonZero / silence24.frames).toFixed(1) + ' percent; TPDF predicts 25)');
    check('the blocks are staged in Float64Array', silence24.ringType === 'Float64Array',
      silence24.ringType);
    check('L and R carry the identical dithered sample', silence24.lrIdentical === true);
    check('the 24-bit dithered-silence floor is the arithmetic one',
      Math.abs(silence24.broadbandDbfs - want24) <= 1.0,
      'measured ' + silence24.broadbandDbfs.toFixed(2) + ' dBFS, expected '
      + want24.toFixed(2) + ' +/- 1.0');

    const silence16 = await page.evaluate(PROBE,
      { depth: 16, rate: 48000, collectMs: 1200, tone: null });
    console.log('  silence@16: ' + JSON.stringify(silence16));
    const want16 = expectedFloorDbfs(16);
    check('the 16-bit dithered-silence floor is the arithmetic one',
      Math.abs(silence16.broadbandDbfs - want16) <= 1.0,
      'measured ' + silence16.broadbandDbfs.toFixed(2) + ' dBFS, expected '
      + want16.toFixed(2) + ' +/- 1.0');
    const measuredStep = silence16.broadbandDbfs - silence24.broadbandDbfs;
    check('the floor falls by 6.0206 dB per bit',
      Math.abs(measuredStep - 6.0206 * 8) <= 1.0,
      'measured ' + measuredStep.toFixed(2) + ' dB over 8 bits, expected '
      + (6.0206 * 8).toFixed(2) + ' +/- 1.0');

    // ---------------- F3: a tone through the crossing ----------------
    // amplitudeVRms 0.35355 with a full-scale of 1.0 gives a peak of 0.5 = -6.02 dBFS.
    const tone = { hz: 1000, vrms: 0.5 / Math.SQRT2 };
    const played = await page.evaluate(PROBE,
      { depth: 24, rate: 48000, collectMs: 1500, tone });
    console.log('  tone@24: ' + JSON.stringify(played));
    check('the played tone reaches the capture lane',
      played.toneWindowCount > 10,
      played.toneWindowCount + ' of ' + played.windows + ' windows carried it');
    check('the captured fundamental is 20*log10(A) dBFS',
      Math.abs(played.medianAmpDbfs - 20 * Math.log10(0.5)) <= 1.0,
      'measured ' + played.medianAmpDbfs.toFixed(3) + ' dBFS, expected '
      + (20 * Math.log10(0.5)).toFixed(3) + ' +/- 1.0');
    // A dither of b bits on a d-bit lane floors at sigma = sqrt(4^(d-b)/6 + 1/12) LSB of d
    // (the 0.5-LSB figure is the b = d case); the broadband metric is 20*log10(sigma*sqrt(2))
    // in full scale. For 12 on 24 that is about -71.0 dBFS.
    const sigmaLsb12 = Math.sqrt(Math.pow(4, 24 - 12) / 6 + 1 / 12);
    const want12 = 20 * Math.log10(Math.SQRT2 * sigmaLsb12 * Math.pow(2, -(24 - 1)));
    check('the configured dither applies - the floor under the tone is the 12-bit one',
      Math.abs(played.medianResidualDbfs - want12) <= 1.0,
      'residual ' + played.medianResidualDbfs.toFixed(2) + ' dBFS, expected '
      + want12.toFixed(2) + ' +/- 1.0 (start carried ditherBits 12)');

    // ---------------- F3, the displayed half: generator to loopback to scope ----------------
    // The numbers above ran the lanes directly; this runs the APP's own dispatch - the generator
    // opens a playback sink through AudioEngine._newPlaybackSink and the scope's record opens a
    // capture source through _newCaptureSource, both on the backend the dialog just committed.
    await page.click('#genPlay');
    await page.click('.scope-pane .led-btn');
    await page.waitForTimeout(2500);
    const shown = await page.evaluate(() => {
      const c = document.getElementById('scope');
      if (!c) return { error: 'no #scope canvas' };
      const ctx = c.getContext('2d');
      const img = ctx.getImageData(0, 0, c.width, c.height).data;
      const seen = new Set();
      let sum = 0, sumSq = 0, n = 0;
      for (let i = 0; i < img.length; i += 16) {
        seen.add((img[i] << 16) | (img[i + 1] << 8) | img[i + 2]);
        const lum = (img[i] + img[i + 1] + img[i + 2]) / 3;
        sum += lum; sumSq += lum * lum; n++;
      }
      const mean = sum / n;
      return { colours: seen.size, variance: sumSq / n - mean * mean, w: c.width, h: c.height,
        recording: !!document.querySelector('.scope-pane .led-btn.rec'),
        generating: !!document.querySelector('#genPlay.playing, #genPlay.on') };
    });
    console.log('  scope: ' + JSON.stringify(shown));
    check('the scope records through the loopback backend and draws a trace',
      !shown.error && shown.colours > 1 && shown.variance > 1,
      shown.error || 'colours=' + shown.colours + ' var=' + shown.variance.toFixed(2)
      + ' rec=' + shown.recording);

    const fatal = errors.filter((e) => !/favicon|net::ERR|Missing i18n|404/.test(e));
    check('no uncaught page errors', fatal.length === 0, fatal.slice(0, 3).join(' | '));
    // Whatever 404s must be a BUILD artefact the source tree does not carry, or the served-by-a-
    // server probe this plain file server answers for by NOT having it - never an app file.
    const unexpected = missing.filter((u) => !/version\.json|favicon|^\/info$/.test(u));
    check('nothing the app needs is missing from the served source tree',
      unexpected.length === 0, 'missing: ' + (missing.join(', ') || 'none'));
  } finally {
    await browser.close();
    server.close();
    console.log('loopback e2e: ' + passed + ' passed, ' + failed + ' failed');
    process.exit(failed === 0 ? 0 : 1);
  }
}

main();
