/*
 * Phonalyser web - dev server for DEBUGGING the UNBUNDLED source.
 * Serves web/ directly (no build): the page loads js/shell/app.js, js/ui/scope-view.js,
 * etc. as REAL ES-module files, so DevTools -> Sources shows the actual source and
 * native breakpoints work - no bundling, no source maps. Sends `Cache-Control: no-store`
 * so every reload runs the latest files (and update.js skips the service worker on
 * localhost). Run:  npm run dev   (or:  PORT=9000 node scripts/serve.mjs)
 */
import http from 'node:http';
import { hostname } from 'node:os';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = normalize(join(fileURLToPath(new URL('.', import.meta.url)), '..'));   // web/
const PORT = Number(process.env.PORT) || 8080;
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.wasm': 'application/wasm',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.properties': 'text/plain; charset=utf-8',
  '.wav': 'audio/wav', '.flac': 'audio/flac', '.map': 'application/json; charset=utf-8',
};

http.createServer((req, res) => {
  let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
  if (urlPath === '/') urlPath = '/index.html';
  const fp = normalize(join(ROOT, urlPath));
  if (!fp.startsWith(ROOT) || !existsSync(fp) || !statSync(fp).isFile()) {
    res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('not found: ' + urlPath); return;
  }
  res.writeHead(200, {
    'Content-Type': MIME[extname(fp).toLowerCase()] || 'application/octet-stream',
    'Cache-Control': 'no-store',   // never cache -> freshest source every reload (breakpoints hit)
    // CROSS-ORIGIN ISOLATION, on every path this serves. The FFT's divided transform shares its
    // sample arrays between lane workers, which a browser permits only in an isolated page. In
    // production the service worker adds these headers; here there is deliberately NO service
    // worker (update.js unregisters it on localhost so freshly edited source runs and breakpoints
    // hit), so without these two lines the dev page would silently be the only place the
    // transform stayed on one thread - dev and production would stop being the same program.
    //
    // They go on EVERY response, help/ included, not just the app document: the help pop-up keeps
    // a live handle in the page (app.js refreshOpenHelp re-points it on a language switch), and
    // an opener with COOP same-origin is severed from a pop-up that lacks it - the handle would
    // read as closed and the help window would quietly stop following the UI language.
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Embedder-Policy': 'credentialless',
  });
  createReadStream(fp).pipe(res);
}).listen(PORT, () => {
  console.log(`Phonalyser dev (UNBUNDLED source, no cache) -> http://localhost:${PORT}`);
  console.log('Open it, then DevTools -> Sources -> js/ ... set breakpoints in the real files. No build needed.');
});
