// BUG A repro: the maintainer's bench condition - the app served by ONE server, a second
// KNOWN server at another origin. He sees "all known server shown as offline, but one is live".
//
// Run from web/:  node test/e2e/_bugA-probe.mjs
//
// It reports, in order:
//   1. the raw cross-origin GET /info from the page (is it CORS, or is it reachable?)
//   2. what the Servers dialog paints for each row
//   3. whether the other bench was asked at all (its own /info counter)

import { chromium } from 'playwright';
import { BenchServer } from './bench-server.mjs';

const serving = new BenchServer({ serverId: 'e2e-serving', name: 'Serving bench' });
const other = new BenchServer({ serverId: 'e2e-other', name: 'Other bench' });

async function main() {
  await serving.start();
  await other.start();
  console.log(`serving bench on ${serving.port}, other bench on ${other.port}`);

  const browser = await chromium.launch();
  const page = await browser.newPage();
  page.on('console', (m) => {
    const text = m.text();
    if (/net discovery|net client|probe|offline/i.test(text)) console.log(`  [page] ${text}`);
  });

  // The stored document the app boots on: the OTHER bench is a known, manually added server.
  const base = `http://127.0.0.1:${serving.port}/`;
  await page.addInitScript((port) => {
    const doc = { custom: { net: { servers: [
      { serverId: 'e2e-other', name: 'Other bench', host: '127.0.0.1', port, manual: true },
    ] } } };
    for (const key of ['phonalyser.prefs', 'phonalyser.preferences', 'phonalyser']) {
      localStorage.setItem(key, JSON.stringify(doc));
    }
  }, other.port);

  await page.goto(base, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6000);

  // 1. The raw question: can this page fetch the other bench's /info at all?
  const raw = await page.evaluate(async (port) => {
    try {
      const r = await fetch(`http://127.0.0.1:${port}/info`);
      return { ok: r.ok, status: r.status, body: await r.text() };
    } catch (e) {
      return { error: `${e.name}: ${e.message}` };
    }
  }, other.port);
  console.log('1. raw cross-origin GET /info from the page:', JSON.stringify(raw));

  // 2. What the operator sees. The Servers button lives INSIDE Preferences, so that dialog is
  //    opened first - which is also the moment the round this round added is spent.
  await page.evaluate(() => {
    window.bootstrap.Modal.getOrCreateInstance(document.getElementById('prefsModal')).show();
  });
  await page.waitForSelector('#prefsModal.show', { timeout: 5000 });
  await page.waitForTimeout(1500);
  console.log(`   after Preferences opened: /info hits other=${other.infoHits}`);
  await page.click('#netServers');
  await page.waitForSelector('#netServersModal.show', { timeout: 5000 });
  await page.waitForTimeout(2500);
  const rows = await page.evaluate(() => [...document.querySelectorAll('#netServersRows tr')]
    .map((tr) => [...tr.children].map((td) => td.textContent).join(' | ')));
  console.log('2. Servers dialog rows:');
  for (const row of rows) console.log(`     ${row}`);

  // 3. Did anything ask the other bench at all?  The bench counts its own /info hits.
  console.log(`3. /info hits: serving=${serving.infoHits}, other=${other.infoHits}`);

  await browser.close();
  await serving.stop();
  await other.stop();
}

main().catch(async (e) => {
  console.error(e);
  process.exit(1);
});
