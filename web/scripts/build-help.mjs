/*
 * Phonalyser web — help build target (SEPARATE from the regular app build).
 *
 * Regenerates the lunr search index (window.HELP_DOCS per language) and copies
 * web/help → dist/help. The regular `node build.mjs` deliberately does NOT touch help, so
 * a normal build stays fast; run this only when the help changed. It writes INTO an
 * existing dist/ (the app build wipes dist), so run it AFTER `node build.mjs`.
 *
 * Run:  npm run build:copy-help      (= node scripts/build-help.mjs)
 * GNU AGPL v3 or later.
 */
import { cp, mkdir, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { buildHelpIndex } from './build-help-index.mjs';

const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const helpSrc = path.join(web, 'help');
const helpDst = path.join(web, 'dist', 'help');
const exists = async (p) => { try { await access(p); return true; } catch { return false; } };

if (!(await exists(helpSrc))) {
  console.error('web/help is missing — run `node scripts/import-help.mjs` first.');
  process.exit(1);
}
const counts = await buildHelpIndex();
console.log('help index: ' + Object.entries(counts).map(([l, n]) => `${l}=${n}`).join(' '));
await mkdir(path.dirname(helpDst), { recursive: true });
await cp(helpSrc, helpDst, { recursive: true });
console.log('copied help → dist/help');
