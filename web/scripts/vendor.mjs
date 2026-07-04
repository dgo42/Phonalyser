/*
 * Phonalyser web — copy the npm dependencies' dist files into web/vendor/ so the
 * static app can reference local, version-pinned libraries (no CDN). Runs on
 * `npm install` (postinstall) and `npm run vendor`. Keeps deps auditable via
 * `npm audit` / `npm outdated` while the app stays a no-bundler static site.
 * GNU AGPL v3 or later.
 */
import { cp, rm, mkdir } from 'node:fs/promises';

const COPIES = [
  ['node_modules/bootstrap/dist',        'vendor/bootstrap'],
  ['node_modules/jquery/dist',           'vendor/jquery'],
  ['node_modules/bootstrap-icons/font',  'vendor/bootstrap-icons'],
  ['node_modules/libflacjs/dist',        'vendor/libflac'],
];

await rm('vendor', { recursive: true, force: true });
for (const [src, dst] of COPIES) {
  await mkdir(dst, { recursive: true });
  await cp(src, dst, { recursive: true });
  console.log(`vendored ${src} -> ${dst}`);
}
console.log('vendor: done');
