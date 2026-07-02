/*
 * Phonalyser web — (re-)import the Java HTML help into the web app.
 *
 * The Java help under ../src/main/resources/help is the source of truth for the help
 * STRUCTURE + theory text. The web keeps its OWN copy under web/help/ so it can diverge
 * exactly where the web UI differs from the desktop app:
 *   • screenshots are re-captured from the running WEB app (img/*.png — see
 *     scripts/capture-help-screenshots.mjs), and
 *   • search-index.js is REGENERATED at build time from the (possibly edited) HTML
 *     (see scripts/build-help-index.mjs) rather than carried over verbatim.
 *
 * Re-running is safe: every Java entry is refreshed, but each language's img/ folder is
 * PRESERVED once it exists (so web screenshots survive a re-import); on the very first
 * import the Java img/ is copied in as a placeholder until the web captures replace it.
 * Run:  node scripts/import-help.mjs
 * GNU AGPL v3 or later.
 */
import { cp, rm, readdir, mkdir, access, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));       // web/scripts
const SRC = path.resolve(here, '../../src/main/resources/help'); // Java help (source of truth)
const DST = path.resolve(here, '../help');                       // web/help (web's copy)

const exists = async (p) => { try { await access(p); return true; } catch { return false; } };

const langs = (await readdir(SRC, { withFileTypes: true })).filter((d) => d.isDirectory()).map((d) => d.name);
for (const lang of langs) {
  const srcLang = path.join(SRC, lang);
  const dstLang = path.join(DST, lang);
  await mkdir(dstLang, { recursive: true });
  for (const e of await readdir(srcLang, { withFileTypes: true })) {
    const s = path.join(srcLang, e.name);
    const d = path.join(dstLang, e.name);
    // Preserve web-captured screenshots: only seed img/ from Java when the web has none yet.
    if (e.name === 'img' && await exists(d)) continue;
    await rm(d, { recursive: true, force: true });
    await cp(s, d, { recursive: true });
  }
}
// Bake the ?hl= search-term highlighter into each language: copy the script in and add a
// <script src> to every page (the desktop HelpViewer injected it; static pages can't).
const VIEWER = path.join(here, 'help-viewer.js');
async function injectViewer(dir, langRoot) {
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { await injectViewer(full, langRoot); continue; }
    if (!e.name.endsWith('.html')) continue;
    let html = await readFile(full, 'utf8');
    if (html.includes('help-viewer.js')) continue;   // already injected
    const rel = path.relative(path.dirname(full), langRoot).replace(/\\/g, '/');
    const tag = `<script src="${rel ? rel + '/' : ''}help-viewer.js"></script>\n`;
    html = html.includes('</body>') ? html.replace('</body>', tag + '</body>') : html + tag;
    await writeFile(full, html);
  }
}
for (const lang of langs) {
  const langDir = path.join(DST, lang);
  await cp(VIEWER, path.join(langDir, 'help-viewer.js'));
  await injectViewer(langDir, langDir);
}

console.log(`imported help: ${SRC} -> ${DST}`);
console.log(`  languages: ${langs.join(', ')}  (img/ preserved, ?hl highlighter injected)`);
