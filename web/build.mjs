/*
 * Phonalyser web — production build. Bundles the app's ES modules with esbuild
 * into the fewest files (cut HTTP requests) under html/web/, copies the static
 * assets + npm-vendored libs, and rewrites index.html / sw.js with the version
 * from package.json (the single source of truth). That version is also synced back
 * into the SOURCE index.html / sw.js, so the unbundled web/ served in dev shows the
 * same version — package.json is the ONLY place the version is hand-edited.
 *
 * URL resolution (the load-bearing invariant): backend.js, once bundled INTO
 * the output app.js, runs `new URL('./fft-worker.js', import.meta.url)` and
 * `new URL('./worklets/<name>.js', import.meta.url)`. At runtime import.meta.url
 * is the location of app.js, so those resolve to its siblings fft-worker.js and
 * worklets/<name>.js — which is exactly where we emit them. esbuild leaves
 * `new URL(..., import.meta.url)` untouched (intended), so no code change.
 * GNU AGPL v3 or later.
 */
import { build } from 'esbuild';
import { cp, rm, mkdir, readFile, writeFile, stat, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.dirname(fileURLToPath(import.meta.url));
// Compiled app output: <repo>/html/web (was web/dist). All emitted files (app.js, workers,
// worklets, vendor.js, index.html, sw.js, version.json, copied static assets, help) live here
// together, so the `new URL('./x', import.meta.url)` sibling resolution below is unaffected.
const outDir = path.join(root, '..', 'html', 'web');

const pkg = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
const VERSION = pkg.version;

// `node build.mjs --debug` → a readable, breakpoint-friendly build: no minification
// and INLINE source maps, so the browser devtools show the original module source.
// (Production `node build.mjs` stays minified with linked .map files.)
const DEBUG = process.argv.includes('--debug') || process.argv.includes('-d');

// Entry → output. ESM for the app/worker (modern module loaders); IIFE for the
// AudioWorklets — addModule loads them as CLASSIC scripts, so they must be
// self-contained with no import/export (bundling inlines dds-kernel.js etc.).
const ESM = [
  ['js/shell/app.js', 'app.js'],
  ['js/shell/update.js', 'update.js'],
  ['js/audio/fft-worker.js', 'fft-worker.js'],
  ['js/audio/fft-pool-worker.js', 'fft-pool-worker.js'],
  ['js/scope/osc-freq-worker.js', 'osc-freq-worker.js'],
];
const IIFE = [
  ['js/audio/worklets/capture-processor.js', 'worklets/capture-processor.js'],
  ['js/audio/worklets/generator-processor.js', 'worklets/generator-processor.js'],
  ['js/audio/worklets/dds-processor.js', 'worklets/dds-processor.js'],
];

const exists = async (p) => { try { await access(p); return true; } catch { return false; } };

async function bundle(entry, out, format) {
  await build({
    entryPoints: [path.join(root, entry)],
    outfile: path.join(outDir, out),
    bundle: true,
    minify: !DEBUG,
    sourcemap: DEBUG ? 'inline' : true,
    format,
    target: 'es2022',
    legalComments: 'none',
  });
}

async function copyStatic() {
  // Same relative layout as web/ so the unchanged asset paths keep resolving.
  // NOTE: help is NOT copied here — it's a separate target (npm run build:copy-help), so a
  // regular build stays fast and doesn't regenerate the search index every time.
  const items = ['css', 'assets', 'i18n', 'favicon.svg', 'manifest.webmanifest'];
  for (const it of items) {
    const src = path.join(root, it);
    if (!(await exists(src))) { console.warn(`  skip (absent): ${it}`); continue; }
    await cp(src, path.join(outDir, it), { recursive: true });
  }
  await copyVendorMinimal();
}

// Copy ONLY the specific vendor files the app references — NOT the whole npm dist
// trees. jQuery + Bootstrap JS are bundled into vendor.js; here we just bring
// the Bootstrap CSS the app links, the Bootstrap-icons webfont, and the libFLAC
// loader + its .wasm. (Everything else under vendor/ is unused build noise.)
async function copyVendorMinimal() {
  if (!(await exists(path.join(root, 'vendor')))) {
    throw new Error('vendor/ missing — run `npm install` (or `npm run vendor`) first.');
  }
  const files = [
    'vendor/bootstrap/css/bootstrap.min.css',
    'vendor/bootstrap-icons/bootstrap-icons.min.css',
    'vendor/bootstrap-icons/fonts/bootstrap-icons.woff2',
    'vendor/bootstrap-icons/fonts/bootstrap-icons.woff',
    'vendor/libflac/libflac.min.wasm.js',
    'vendor/libflac/libflac.min.wasm.wasm',
  ];
  for (const f of files) {
    const src = path.join(root, f);
    if (!(await exists(src))) { console.warn(`  skip vendor (absent): ${f}`); continue; }
    const dst = path.join(outDir, f);
    await mkdir(path.dirname(dst), { recursive: true });
    await cp(src, dst);
  }
}

async function emitIndexHtml() {
  let html = await readFile(path.join(root, 'index.html'), 'utf8');
  // Module scripts now point at the bundled siblings in the output dir.
  html = html.replace('src="js/shell/app.js"', 'src="app.js"');
  html = html.replace('src="js/shell/update.js"', 'src="update.js"');
  // Collapse the two separate vendor <script>s (jQuery + Bootstrap) into the one
  // tree-shaken bundle vendor.js.
  html = html.replace(
    /<script src="vendor\/jquery\/jquery\.min\.js"><\/script>\s*<script src="vendor\/bootstrap\/js\/bootstrap\.bundle\.min\.js"><\/script>/,
    '<script src="vendor.js"></script>',
  );
  // Inject the real version into the menu-bar version chip.
  html = html.replace(
    /(<span class="menu-ver">)[^<]*(<\/span>)/,
    `$1v${VERSION} · web$2`,
  );
  await writeFile(path.join(outDir, 'index.html'), html);
}

async function emitServiceWorker() {
  let sw = await readFile(path.join(root, 'sw.js'), 'utf8');
  // Pin the cache key + update prompt to the real version.
  sw = sw.replace(/const VERSION = '[^']*';/, `const VERSION = '${VERSION}';`);
  await writeFile(path.join(outDir, 'sw.js'), sw);
  await writeFile(path.join(outDir, 'version.json'), JSON.stringify({ version: VERSION }) + '\n');
}

// Sync the version literal in the SOURCE index.html (menu-bar chip) and sw.js (cache key)
// from package.json, IN PLACE — so the unbundled web/ served in dev shows the same version
// a built html/web/ would, leaving package.json as the only hand-edited copy. Guarded: writes
// only when the value actually changed, so a same-version rebuild touches nothing.
async function syncSourceVersion() {
  const htmlPath = path.join(root, 'index.html');
  const html = await readFile(htmlPath, 'utf8');
  const html2 = html.replace(/(<span class="menu-ver">)[^<]*(<\/span>)/, `$1v${VERSION} · web$2`);
  if (html2 !== html) await writeFile(htmlPath, html2);

  const swPath = path.join(root, 'sw.js');
  const sw = await readFile(swPath, 'utf8');
  const sw2 = sw.replace(/const VERSION = '[^']*';/, `const VERSION = '${VERSION}';`);
  if (sw2 !== sw) await writeFile(swPath, sw2);
}

async function report() {
  const outs = [...ESM, ...IIFE].map(([, o]) => o)
    .concat(['vendor.js', 'index.html', 'sw.js', 'version.json']);
  console.log(`\nbuilt phonalyser-web v${VERSION} → html/web/${DEBUG ? '  (DEBUG: unminified + inline source maps)' : ''}`);
  for (const o of outs) {
    const { size } = await stat(path.join(outDir, o));
    console.log(`  ${o.padEnd(34)} ${(size / 1024).toFixed(1)} kB`);
  }
}

async function main() {
  await rm(outDir, { recursive: true, force: true });
  await mkdir(outDir, { recursive: true });

  for (const [entry, out] of ESM) await bundle(entry, out, 'esm');
  for (const [entry, out] of IIFE) await bundle(entry, out, 'iife');
  await bundle('build-vendor.js', 'vendor.js', 'iife');   // jQuery + Bootstrap (+ Popper), tree-shaken → globals

  await copyStatic();
  await syncSourceVersion();
  await emitIndexHtml();
  await emitServiceWorker();
  await report();
}

main().catch((e) => { console.error(e); process.exit(1); });
