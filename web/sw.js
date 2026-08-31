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
const VERSION = '1.2.2';                    // single source of truth = package.json version (build injects it)
const CACHE = `phonalyser-${VERSION}`;
const CORE = ['./', './index.html', './css/app.css', './favicon.svg', './manifest.webmanifest', './devices.yaml'];

// CROSS-ORIGIN ISOLATION. The FFT analyser divides one transform's butterfly stages across lane
// workers that share the sample arrays outright, and a browser allows that sharing
// (SharedArrayBuffer) only in a cross-origin-isolated page - which is a property of two response
// headers on the document. A static host cannot send them, so this worker adds them to what it
// serves; a page that never reaches the worker simply is not isolated and the analyser keeps
// every transform on one thread.
//
// credentialless, not require-corp: the app's own assets are all same-origin, but the net backend
// reaches OTHER origins - it probes candidate benches for their JSON descriptor and posts a
// generator buffer to the one in use. require-corp would demand a header from every one of those
// answers; credentialless asks for nothing extra of them.
const ISOLATION_HEADERS = {
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'credentialless',
};

/** The same response, carrying the isolation headers. A fetched Response's headers are immutable,
 *  so it is rebuilt around the same body. An opaque answer (a cross-origin no-cors fetch) has
 *  nothing to rebuild and is passed through untouched. */
function isolated(res) {
  if (!res || res.type === 'opaque' || res.type === 'opaqueredirect') return res;
  const headers = new Headers(res.headers);
  for (const name of Object.keys(ISOLATION_HEADERS)) headers.set(name, ISOLATION_HEADERS[name]);
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
}

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
  // The isolation headers are added on the way OUT, to the live answer and to the cached one
  // alike - what is stored stays the server's own response, so a later deploy that sets its own
  // headers is not overwritten by a stale copy of these.
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res && res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); }
        return isolated(res);
      })
      .catch(() => caches.open(CACHE).then((c) => c.match(req)).then(isolated))
  );
});
