// Real-browser wheel smoke for the scope navigation transplant (temp, _-prefixed).
//
// Boots the app with FAKE audio, starts a LIVE scope capture, then fires a wheel event
// with each modifier on the scope canvas and reads the affected preference back from
// BOTH the live Preferences.instance() AND localStorage after the coalesced save flush:
//
//   plain wheel        -> oscLeftOffsetFrac / oscRightOffsetFrac  (vertical move, both ch)
//   Shift+wheel        -> oscTriggerPositionFrac                  (½-div horizontal pan)
//   Ctrl+wheel         -> oscLeftVoltsPerDiv / oscRightVoltsPerDiv (coupled V/div zoom)
//   Ctrl+Shift+wheel   -> oscTimePerDiv (t/div zoom) + oscTriggerPositionFrac re-anchor
//
// Each combo asserts the pref moved in the Java-parity direction. This exercises the REAL
// _onWheel dispatch + render() geometry end to end in Chromium.

import http from 'node:http';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeFakeAudioWav } from './make-fake-audio.mjs';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));
const MIME = { '.html':'text/html', '.mjs':'text/javascript', '.js':'text/javascript', '.css':'text/css', '.json':'application/json', '.webmanifest':'application/manifest+json', '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon', '.wasm':'application/wasm', '.properties':'text/plain' };
function startServer() {
  const server = http.createServer((req, res) => {
    let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (urlPath === '/') urlPath = '/index.html';
    const filePath = normalize(join(WEB_ROOT, urlPath));
    if (!filePath.startsWith(WEB_ROOT) || !existsSync(filePath) || !statSync(filePath).isFile()) { res.writeHead(404); res.end('nf'); return; }
    res.writeHead(200, { 'Content-Type': (MIME[extname(filePath).toLowerCase()]||'application/octet-stream')+'; charset=utf-8', 'Cache-Control':'no-store' });
    createReadStream(filePath).pipe(res);
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, port: server.address().port })));
}

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS:', msg); } else { fail++; console.log('FAIL:', msg); } };

async function main() {
  const { chromium } = await import('playwright');
  const { server, port } = await startServer();
  const origin = `http://127.0.0.1:${port}`;
  const fakeWav = join(__dirname, 'fake-audio.wav');
  makeFakeAudioWav(fakeWav);
  const browser = await chromium.launch({ headless: true, args: ['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required',`--use-file-for-fake-audio-capture=${fakeWav}`] });
  const context = await browser.newContext({ permissions: ['microphone'], deviceScaleFactor: 2 });
  await context.grantPermissions(['microphone'], { origin });
  const page = await context.newPage();
  page.on('pageerror', (e) => console.log('PAGEERR', e.message));

  await page.goto(origin + '/index.html', { waitUntil: 'load' });
  await page.waitForSelector('.scope-pane .led-btn', { timeout: 15000 });

  // Read one pref from the LIVE Preferences.instance() (authoritative, synchronous).
  const livePref = (key) => page.evaluate(async (k) => {
    const mod = await import('/js/store/preferences.js');
    return mod.Preferences.instance()[k].get();
  }, key);
  // Read one pref from localStorage (the coalesced persisted JSON) - the spec's read path.
  const lsPref = (key) => page.evaluate((k) => {
    try { const raw = localStorage.getItem('phonalyser.preferences'); if (!raw) return undefined;
          return JSON.parse(raw)[k]; } catch (e) { return undefined; }
  }, key);

  // Dispatch ONE wheel tick on the scope canvas centre (deltaY<0 = wheel UP = dir +1).
  const wheelOn = (mods) => page.evaluate((m) => {
    const c = document.getElementById('scope');
    const r = c.getBoundingClientRect();
    c.dispatchEvent(new WheelEvent('wheel', {
      deltaY: -120, ctrlKey: !!m.ctrl, shiftKey: !!m.shift,
      clientX: r.left + r.width * 0.5, clientY: r.top + r.height * 0.5, bubbles: true, cancelable: true }));
  }, mods);

  // Start LIVE capture so render() runs the live geometry path.
  await page.click('.scope-pane .led-btn');
  await page.waitForTimeout(1800);   // let the fake ring fill + a few frames + meas warmup run

  // ---- plain wheel -> vertical offset on BOTH channels (offset DECREASES on wheel up) ----
  {
    const l0 = await livePref('oscLeftOffsetFrac'), r0 = await livePref('oscRightOffsetFrac');
    await wheelOn({});
    await page.waitForTimeout(350);   // > SAVE_COALESCE_MS so localStorage has flushed
    const l1 = await livePref('oscLeftOffsetFrac'), r1 = await livePref('oscRightOffsetFrac');
    const lLs = await lsPref('oscLeftOffsetFrac');
    ok(l1 < l0 && r1 < r0, `plain wheel moved BOTH channel offsets down (L ${l0}->${l1}, R ${r0}->${r1})`);
    ok(Math.abs(lLs - l1) < 1e-9, `plain wheel offset persisted to localStorage (${lLs})`);
  }

  // ---- Shift+wheel -> ½-div horizontal trigger-offset pan (posFrac changes) ----
  {
    const p0 = await livePref('oscTriggerPositionFrac');
    await wheelOn({ shift: true });
    await page.waitForTimeout(350);
    const p1 = await livePref('oscTriggerPositionFrac');
    const pLs = await lsPref('oscTriggerPositionFrac');
    ok(Math.abs(p1 - p0) > 1e-9, `Shift+wheel moved the trigger-position offset (${p0}->${p1})`);
    ok(Math.abs(pLs - p1) < 1e-9, `Shift+wheel offset persisted to localStorage (${pLs})`);
  }

  // ---- Ctrl+wheel -> coupled V/div zoom on BOTH channels (wheel up -> finer, smaller V/div) ----
  {
    const l0 = await livePref('oscLeftVoltsPerDiv'), r0 = await livePref('oscRightVoltsPerDiv');
    await wheelOn({ ctrl: true });
    await page.waitForTimeout(350);
    const l1 = await livePref('oscLeftVoltsPerDiv'), r1 = await livePref('oscRightVoltsPerDiv');
    const lLs = await lsPref('oscLeftVoltsPerDiv');
    ok(l1 < l0 && r1 < r0, `Ctrl+wheel zoomed V/div finer on BOTH channels (L ${l0}->${l1}, R ${r0}->${r1})`);
    ok(Math.abs(lLs - l1) < 1e-15, `Ctrl+wheel V/div persisted to localStorage (${lLs})`);
  }

  // ---- Ctrl+Shift+wheel -> t/div zoom (wheel up -> finer, smaller t/div) + posFrac re-anchor ----
  {
    const t0 = await livePref('oscTimePerDiv');
    await wheelOn({ ctrl: true, shift: true });
    await page.waitForTimeout(350);
    const t1 = await livePref('oscTimePerDiv');
    const tLs = await lsPref('oscTimePerDiv');
    ok(t1 < t0, `Ctrl+Shift+wheel zoomed t/div finer (${t0}->${t1})`);
    ok(Math.abs(tLs - t1) < 1e-15, `Ctrl+Shift+wheel t/div persisted to localStorage (${tLs})`);
  }

  await browser.close();
  server.close();
  console.log(`\nscope-wheel-smoke: ${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}
main().catch(e => { console.log('FATAL', e.stack || e); process.exit(1); });
