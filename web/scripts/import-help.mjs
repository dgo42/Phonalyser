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

// Web-ONLY help pages: they live only under web/help/<lang>/, have no Java source, and
// document a browser-specific concern the desktop app doesn't have. The copy loop above
// never touches them (it only mirrors Java entries — web-only files simply survive), but
// the Java-sourced index.html is OVERWRITTEN every import and therefore carries no link to
// them, so we re-inject a TOC entry here. `after` is the anchor href (a language-invariant
// link already in the Modules TOC) to insert AFTER; `title` is the per-language link text.
const WEB_ONLY_PAGES = [{
  file: 'web-input-device.html',
  after: 'preferences.html',
  title: {
    en: '<b>Windows input device</b> — set the capture (and playback) sample rate the browser can\'t choose',
    de: '<b>Windows-Eingabegerät</b> — die Abtastrate für Aufnahme (und Wiedergabe) setzen, die der Browser nicht wählen kann',
    uk: '<b>Вхідний пристрій Windows</b> — задати частоту дискретизації захоплення (та відтворення), яку браузер не обирає',
  },
}];

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
// Per-page rewrites: (a) the web product name — the help renders the app as
// "Phonalyser.web" (an intended divergence, sync-java-to-web skill rule 8); the
// negative lookbehind keeps URLs (github.com/dgo42/Phonalyser) intact and the
// lookahead makes the rename idempotent. (b) Bake the ?hl= search-term
// highlighter into each language: copy the script in and add a <script src> to
// every page (the desktop HelpViewer injected it; static pages can't).
const VIEWER = path.join(here, 'help-viewer.js');
const PRODUCT_RENAME = /(?<!\/)\bPhonalyser\b(?!\.web)/g;
async function injectViewer(dir, langRoot) {
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { await injectViewer(full, langRoot); continue; }
    if (!e.name.endsWith('.html')) continue;
    const orig = await readFile(full, 'utf8');
    let html = orig.replace(PRODUCT_RENAME, 'Phonalyser.web');
    if (!html.includes('help-viewer.js')) {
      const rel = path.relative(path.dirname(full), langRoot).replace(/\\/g, '/');
      const tag = `<script src="${rel ? rel + '/' : ''}help-viewer.js"></script>\n`;
      html = html.includes('</body>') ? html.replace('</body>', tag + '</body>') : html + tag;
    }
    if (html !== orig) await writeFile(full, html);
  }
}
for (const lang of langs) {
  const langDir = path.join(DST, lang);
  await cp(VIEWER, path.join(langDir, 'help-viewer.js'));
  await injectViewer(langDir, langDir);
}

// Re-inject the TOC link(s) for every web-only page into each language's (freshly
// overwritten) index.html, idempotently: skip a page whose href already appears, else insert
// its <a> line immediately after the anchor link, matching that link's indentation.
async function injectTocLinks() {
  for (const lang of langs) {
    const idx = path.join(DST, lang, 'index.html');
    if (!await exists(idx)) continue;
    let html = await readFile(idx, 'utf8');
    const orig = html;
    for (const page of WEB_ONLY_PAGES) {
      if (html.includes(`href="${page.file}"`)) continue;   // already linked — idempotent
      const anchorRe = new RegExp(`([ \\t]*)(<a href="${page.after}">[\\s\\S]*?</a>)`);
      const m = anchorRe.exec(html);
      if (!m) { console.warn(`  ! ${lang}/index.html: anchor '${page.after}' not found; ${page.file} link not injected`); continue; }
      const indent = m[1];
      const link = `\n${indent}<a href="${page.file}">${page.title[lang] ?? page.title.en}</a>`;
      html = html.slice(0, m.index + m[0].length) + link + html.slice(m.index + m[0].length);
    }
    if (html !== orig) await writeFile(idx, html);
  }
}
await injectTocLinks();

console.log(`imported help: ${SRC} -> ${DST}`);
console.log(`  languages: ${langs.join(', ')}  (img/ preserved, ?hl highlighter injected)`);
