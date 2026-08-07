/*
 * Phonalyser web - help build target (SEPARATE from the regular app build).
 *
 * Regenerates the lunr search index (window.HELP_DOCS per language) and copies
 * web/help -> the packaging's help folder. The regular `node build.mjs` deliberately does NOT
 * touch help, so a normal build stays fast; run this only when the help changed. It writes INTO
 * an existing output folder (the app build wipes it), so run it AFTER `node build.mjs`.
 *
 * TWO PACKAGINGS, the same flag build.mjs uses and with the same meaning:
 *   node scripts/build-help.mjs               -> docs/web/help          (the published app)
 *   node scripts/build-help.mjs --embedded    -> target/web-embedded/help  (what a Phonalyser
 *                                               server serves at /)
 * The embedded app had no help at all: nothing ever copied it there, so its Help window opened
 * on nothing. The relative layout is identical in both, so the viewer and the lunr
 * index resolve exactly as they do on the published site.
 *
 * Run:  npm run build:copy-help      (= node scripts/build-help.mjs)
 * GNU AGPL v3 or later.
 */
import { cp, mkdir, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { buildHelpIndex } from './build-help-index.mjs';

const EMBEDDED = process.argv.includes('--embedded');

const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const helpSrc = path.join(web, 'help');
const helpDst = EMBEDDED
  ? path.join(web, '..', 'target', 'web-embedded', 'help')
  : path.join(web, '..', 'docs', 'web', 'help');
const exists = async (p) => { try { await access(p); return true; } catch { return false; } };

if (!(await exists(helpSrc))) {
  console.error('web/help is missing - run `node scripts/import-help.mjs` first.');
  process.exit(1);
}
const counts = await buildHelpIndex();
console.log('help index: ' + Object.entries(counts).map(([l, n]) => `${l}=${n}`).join(' '));
await mkdir(path.dirname(helpDst), { recursive: true });
await cp(helpSrc, helpDst, { recursive: true });
console.log(`copied help -> ${EMBEDDED ? 'target/web-embedded/help' : 'docs/web/help'}`);
