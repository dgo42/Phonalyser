// Real end-to-end probe for the "beat disappears when stopping in AUTO" fix.
//
// Drives a REAL headless Chromium against the actual web/ app (same server + fake
// audio as scope-smoke.mjs). Sets the generator to DUAL_TONE, turns the scope's
// Reconstructed-beat overlay on, starts the generator so GENERATOR_RUNNING is true
// (the beat gate), records the scope in AUTO, and then:
//   - confirms the LIVE frame carries the reconstructed-beat polyline,
//   - STOPS recording while in AUTO and asserts the canvas is NOT blank (the frozen
//     frame is replayed) and the held frame still carries the beat overlay,
//   - RESIZES the window and asserts the frozen frame is still painted (not blank).
//
// Run:  node test/e2e/_scope-freeze-beat-probe.mjs   (from web/)
// Exits non-zero if any assertion fails. Prints 'probe: N passed, M failed'.

import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));

const MIME = {
  '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.wasm': 'application/wasm', '.properties': 'text/plain; charset=utf-8',
};

function startServer() {
  const server = http.createServer((req, res) => {
    let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (urlPath === '/') urlPath = '/index.html';
    const filePath = normalize(join(WEB_ROOT, urlPath));
    if (!filePath.startsWith(WEB_ROOT)) { res.writeHead(403); res.end('forbidden'); return; }
    if (!existsSync(filePath) || !statSync(filePath).isFile()) { res.writeHead(404); res.end('not found: ' + urlPath); return; }
    const mime = MIME[extname(filePath).toLowerCase()] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': mime, 'Cache-Control': 'no-store' });
    createReadStream(filePath).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port })));
}

let passed = 0, failed = 0;
function check(name, ok, detail) {
  if (ok) { passed++; console.log(`PASS: ${name}` + (detail ? ` - ${detail}` : '')); }
  else { failed++; console.log(`FAIL: ${name}` + (detail ? ` - ${detail}` : '')); }
}

// Distinct-colour count + luminance variance of the live #scope canvas -> not-blank test.
async function canvasStats(page) {
  return page.evaluate(() => {
    const c = document.getElementById('scope');
    if (!c) return { error: 'no #scope canvas' };
    const ctx = c.getContext('2d');
    const w = c.width, h = c.height;
    const img = ctx.getImageData(0, 0, w, h).data;
    const seen = new Set();
    let sum = 0, sumSq = 0, n = 0;
    for (let i = 0; i < img.length; i += 16) {
      const r = img[i], g = img[i + 1], b = img[i + 2];
      seen.add((r << 16) | (g << 8) | b);
      const lum = (r + g + b) / 3; sum += lum; sumSq += lum * lum; n++;
    }
    const mean = sum / n;
    return { colours: seen.size, variance: sumSq / n - mean * mean, w, h };
  });
}

// Count attenuated-cyan beat-overlay pixels on the live #scope canvas. The L channel
// is cyan (0x00d7ff = 0,215,255); its reconstructed-beat overlay is that colour
// attenuated 0.45 (≈ 0,96,114) - a dimmer cyan than the bright main trace. We count
// pixels that are cyan-DOMINANT but NOT the bright main trace: green in [40,190] and
// blue in [50,210] with blue≥green and red low. A frame WITH the beat has clearly more
// such pixels than a single bright trace alone. Used as an on-canvas "beat present"
// witness (the exact held-frame beat buffer is proven by the node unit test).
async function beatPixels(page) {
  return page.evaluate(() => {
    const c = document.getElementById('scope');
    if (!c) return { error: 'no #scope canvas' };
    const ctx = c.getContext('2d');
    const W = c.width, H = c.height;
    const img = ctx.getImageData(0, 0, W, H).data;
    let dimCyan = 0;
    for (let i = 0; i < img.length; i += 4) {
      const r = img[i], g = img[i + 1], b = img[i + 2];
      if (r < 90 && b >= g && g >= 40 && g <= 190 && b >= 50 && b <= 210) dimCyan++;
    }
    return { dimCyan, W, H };
  });
}

async function main() {
  const { chromium } = await import('playwright');
  const { server, port } = await startServer();
  const origin = `http://127.0.0.1:${port}`;
  console.log(`server: ${origin}`);

  const fakeWav = join(__dirname, 'fake-audio.wav');
  makeFakeAudioWav(fakeWav);

  const browser = await chromium.launch({
    headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
      '--autoplay-policy=no-user-gesture-required', `--use-file-for-fake-audio-capture=${fakeWav}`],
  });
  const context = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1400, height: 900 } });
  await context.grantPermissions(['microphone'], { origin });
  const page = await context.newPage();

  const errors = [];
  page.on('console', (msg) => {
    const t = msg.text();
    if (msg.type() === 'error') { if (/favicon|service worker|Failed to load resource/i.test(t)) return; errors.push('console.error: ' + t); }
  });
  page.on('pageerror', (err) => errors.push('pageerror: ' + (err && err.stack || err)));

  try {
    await page.goto(origin + '/index.html', { waitUntil: 'load' });
    await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });
    await page.waitForFunction(() => !!document.getElementById('scope'), { timeout: 15000 });
    await page.waitForTimeout(500);

    // Generator -> DUAL_TONE, then turn the scope's Reconstructed-beat overlay ON.
    await page.evaluate(() => {
      const el = document.getElementById('signalForm');
      el.value = 'DUAL_TONE'; el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.waitForTimeout(200);
    // Start the generator so GENERATOR_RUNNING is true (the beat gate).
    await page.click('#genCol .gen-footer button:last-child').catch(() => {});
    await page.waitForTimeout(400);
    // Reconstructed-beat checkbox (Trigger tab). Open the tab and check it.
    await page.click('#scopeTabs .tab[data-panel="scopeTrig"]').catch(() => {});
    await page.waitForTimeout(150);
    await page.evaluate(() => {
      const cb = document.getElementById('scopeTrigBeat');
      if (cb && !cb.checked) { cb.checked = true; cb.dispatchEvent(new Event('change', { bubbles: true })); }
    });
    await page.waitForTimeout(150);

    // Record the scope in AUTO (default mode).
    await page.click('.scope-pane .led-btn');
    await page.waitForTimeout(1800);

    check('no console/page errors', errors.length === 0, errors.length ? errors.join(' | ') : 'clean');

    const live = await canvasStats(page);
    check('scope canvas not blank while recording (AUTO dual)', live && !live.error && live.colours > 1 && live.variance > 1,
      live.error || `colours=${live.colours} var=${live.variance.toFixed(2)} ${live.w}x${live.h}`);

    const liveBeat = await beatPixels(page);
    check('LIVE frame shows the reconstructed-beat overlay (dim-cyan pixels present)',
      !liveBeat.error && liveBeat.dimCyan > 20,
      liveBeat.error || `dimCyanPixels=${liveBeat.dimCyan} ${liveBeat.W}x${liveBeat.H}`);

    // ===== STOP while in AUTO - the frozen frame must stay on screen with the beat ==
    await page.click('.scope-pane .led-btn');
    await page.waitForTimeout(600);   // let the rAF loop repaint the frozen frame

    const recOff = await page.evaluate(() => !document.querySelector('.scope-pane .led-btn.rec'));
    check('recording stopped (LED off)', recOff === true, `recOff=${recOff}`);

    const stopped = await canvasStats(page);
    check('scope canvas NOT blank after stopping in AUTO (frozen replay)',
      stopped && !stopped.error && stopped.colours > 1 && stopped.variance > 1,
      stopped.error || `colours=${stopped.colours} var=${stopped.variance.toFixed(2)}`);
    const stoppedBeat = await beatPixels(page);
    check('the FROZEN frame still shows the beat overlay (beat NOT lost on stop in AUTO)',
      !stoppedBeat.error && stoppedBeat.dimCyan > 20,
      stoppedBeat.error || `dimCyanPixels=${stoppedBeat.dimCyan}`);

    // ===== RESIZE while stopped - the frozen frame must repaint (still not blank) =====
    await page.setViewportSize({ width: 1100, height: 820 });
    await page.waitForTimeout(600);
    const resized = await canvasStats(page);
    check('scope canvas still NOT blank after a resize while stopped',
      resized && !resized.error && resized.colours > 1 && resized.variance > 1,
      resized.error || `colours=${resized.colours} var=${resized.variance.toFixed(2)} ${resized.w}x${resized.h}`);
    const resizedBeat = await beatPixels(page);
    check('the beat overlay survives the resize (still painted on the held frame)',
      !resizedBeat.error && resizedBeat.dimCyan > 20,
      resizedBeat.error || `dimCyanPixels=${resizedBeat.dimCyan}`);

  } catch (e) {
    failed++;
    console.log('FAIL: harness error - ' + (e && e.stack || e));
  } finally {
    await browser.close().catch(() => {});
    server.close();
  }

  console.log(`probe: ${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.log('FAIL: fatal - ' + (e && e.stack || e)); console.log(`probe: ${passed} passed, ${failed + 1} failed`); process.exit(1); });
