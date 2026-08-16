/*
 * Phonalyser web - service worker.
 * Caches every same-origin asset so the app loads instantly / offline
 * (stale-while-revalidate: serve from cache, refresh in the background).
 * The cache name is VERSION-keyed; bump VERSION on each deploy - the browser
 * then sees sw.js change, installs the new worker, and the page (update.js)
 * shows a "new version - reload" banner. Accepting it skips waiting and reloads
 * into the fresh cache; activate() purges the old one.
 *
 * Cross-origin requests (CDN: Bootstrap/jQuery/libFLAC) are left to the browser
 * HTTP cache - vendor them under web/vendor/ if full offline is required.
 * GNU AGPL v3 or later.
 */
const VERSION = '1.2.1';                    // single source of truth = package.json version (build injects it)
const CACHE = `phonalyser-${VERSION}`;
const CORE = ['./', './index.html', './css/app.css', './favicon.svg', './manifest.webmanifest', './devices.yaml'];

self.addEventListener('install', (e) => {
  self.skipWaiting();                       // a freshly-edited worker takes over promptly
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)));
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('message', (e) => {
  if (e.data === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;            // CDN etc. -> browser HTTP cache
  // NETWORK-FIRST: always serve the freshest same-origin asset when online, so a
  // rebuilt app / edited dev module shows up on the very next reload. (The old
  // stale-while-revalidate served the PREVIOUS bundle for a whole extra reload -
  // which, with a version-pinned cache key, looked like "my changes never load".)
  // Falls back to the cache only when the network is unavailable (offline).
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res && res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
        return res;
      })
      .catch(() => caches.open(CACHE).then((c) => c.match(req)))
  );
});
