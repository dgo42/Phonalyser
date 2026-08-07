/*
 * Phonalyser web - copy the npm dependencies' dist files into web/vendor/ so the
 * static app can reference local, version-pinned libraries (no CDN). Runs on
 * `npm install` (postinstall) and `npm run vendor`. Keeps deps auditable via
 * `npm audit` / `npm outdated` while the app stays a no-bundler static site.
 * GNU AGPL v3 or later.
 */
import { cp, rm, mkdir } from 'node:fs/promises';
import { build } from 'esbuild';

const COPIES = [
  ['node_modules/bootstrap/dist',        'vendor/bootstrap'],
  ['node_modules/jquery/dist',           'vendor/jquery'],
  ['node_modules/bootstrap-icons/font',  'vendor/bootstrap-icons'],
  ['node_modules/libflacjs/dist',        'vendor/libflac'],
];

/**
 * Dependencies that ship no browser-ready file to copy. CodeMirror 6 is a dozen ESM packages
 * importing each other by BARE SPECIFIER, which a browser cannot resolve without an import
 * map; codemirror-json-schema additionally imports its own internals without file extensions.
 * Both resolve under a bundler, so each entry below is bundled into ONE self-contained ES
 * module - still offline, still version-pinned by package.json, just assembled instead of
 * copied. The entry files under scripts/vendor-src/ are what fix the surface the app may use.
 */
const BUNDLES = [
  ['scripts/vendor-src/codemirror.js', 'vendor/codemirror/codemirror.js'],
];

await rm('vendor', { recursive: true, force: true });
for (const [src, dst] of COPIES) {
  await mkdir(dst, { recursive: true });
  await cp(src, dst, { recursive: true });
  console.log(`vendored ${src} -> ${dst}`);
}
for (const [src, dst] of BUNDLES) {
  await build({
    entryPoints: [src],
    outfile: dst,
    bundle: true,
    format: 'esm',
    // Not minified on purpose: the app ships unbundled, readable sources, and a vendored
    // library that can be stepped through in the debugger matches that. build.mjs minifies
    // the shipped packaging.
    minify: false,
    logLevel: 'warning',
  });
  console.log(`bundled  ${src} -> ${dst}`);
}
console.log('vendor: done');
