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

// Web-ONLY removal transform. The desktop "Calibration provided by device"
// (calibrationFromDevice) card flag was removed from the WEB UI, so the WEB help must not
// document it. The copy loop overwrites preferences.html from Java each import (which still
// carries both mentions), so we strip them again every run. Two elements are removed from
// every language's preferences.html: (a) the yaml-key <li> documenting
// `calibrationFromDevice: true`, and (b) the card-editor properties <tr> whose label is the
// per-language "Calibration provided by device" phrase. The strip is idempotent — importing
// twice yields identical output, and a pass over already-stripped HTML is a no-op. Java
// sources are never touched, only the web/help copies.
const CFD_LABELS = 'Calibration provided by device|Kalibrierung vom Gerät bereitgestellt|Калібрування надається пристроєм';
// Tempered lazy tokens keep each match inside a single <li>/<tr> (never spanning a sibling
// element) and tolerate attributes/whitespace on the opening tag; the leading indentation and
// one trailing newline are consumed so no blank line is left behind.
const CFD_LI_RE = /[ \t]*<li\b[^>]*>(?:(?!<\/li>)[\s\S])*?calibrationFromDevice(?:(?!<\/li>)[\s\S])*?<\/li>[ \t]*\n?/g;
const CFD_TR_RE = new RegExp(`[ \\t]*<tr\\b[^>]*>(?:(?!<\\/tr>)[\\s\\S])*?(?:${CFD_LABELS})(?:(?!<\\/tr>)[\\s\\S])*?<\\/tr>[ \\t]*\\n?`, 'g');

// ── Web-ONLY removal transform: the QA40x analyzer backend ──────────────────────────────────
// The browser port has no vendor-USB path, so the QuantAsylum QA402 / QA403 (QA40x) analyzer
// backend is excluded from the web help ENTIRELY (established rule) — while the other desktop
// backends (WASAPI / WDM-KS / JavaSound) are KEPT. The Java help now documents QA40x and the
// copy loop overwrites each page from Java every import, so we strip QA40x again every run,
// leaving the surrounding backend prose intact and grammatical. Everything anchors on the
// language-invariant tokens (id="qa40x", QA40x, QuantAsylum, ref-libusb), so the de/uk
// translations match too. Idempotent: a second pass over stripped HTML is a no-op, and a full
// re-import (Java → strip) is deterministic. Java sources are never touched.
//
// audio-backend.html — the whole "<h2 id="qa40x"> … " chapter, up to (not including) the next <h2>.
const QA_CHAPTER_RE = /[ \t]*<h2 id="qa40x">[\s\S]*?(?=[ \t]*<h2 )/g;
// …the libusb reference footnote, and any citation that pointed at it (its only use is inside the
// removed chapter, so the sup strip is normally a no-op — kept for robustness + idempotency).
const QA_LIBUSB_LI_RE = /[ \t]*<li id="ref-libusb">[\s\S]*?<\/li>[ \t]*\n?/g;
const QA_LIBUSB_SUP_RE = /<sup><a href="#ref-libusb">\[\d+\]<\/a><\/sup>/g;
// preferences.html — the "<b>QA40x.</b> … " note block ("QA40x." is language-invariant).
const QA_NOTE_RE = /[ \t]*<div class="note"><b>QA40x\.<\/b>[\s\S]*?<\/div>[ \t]*\n?/g;
// theory/index.html — the "(… QA40x …)" aside inside the audio-backend chapter link.
const QA_PAREN_RE = / \([^)]*QA40x[^)]*\)/g;
// help-index.html (A-Z topic index + term index) — every <li> that names a QA40x term/topic,
// then any letter group (<h3>X</h3><ul></ul>) that removal leaves empty (the term "Q" is
// QA40x-only, so it would otherwise leave a dangling heading).
const QA_INDEX_LI_RE = /[ \t]*<li\b[^>]*>(?:(?!<\/li>)[\s\S])*?(?:QA40x|QA402|QA403|QuantAsylum)(?:(?!<\/li>)[\s\S])*?<\/li>[ \t]*\n?/g;
const QA_EMPTY_GROUP_RE = /[ \t]*<h3>[^<]*<\/h3>\s*<ul>\s*<\/ul>[ \t]*\n?/g;
// Where a pure strip would relocate a conjunction or a count across three translations, restore
// the maintainer-reviewed web-prior text instead of splicing (error-prone). Anchored on the
// language-invariant markers: the id="paths" heading + its intro <p>, and the <td>Backend</td>
// enumeration row. The .web product name is already applied by injectViewer() before this runs.
const QA_PATHS_RE = /[ \t]*<h2 id="paths">[\s\S]*?<\/h2>\s*<p>[\s\S]*?<\/p>/;
const QA_PATHS = {
  en: '  <h2 id="paths">The three driver paths</h2>\n'
    + '  <p>Sound hardware can be reached through several driver interfaces.\n'
    + '     Phonalyser.web implements three and lets you pick one in Preferences; all\n'
    + '     modules work identically on top of whichever is active.</p>',
  de: '  <h2 id="paths">Die drei Treiberpfade</h2>\n'
    + '  <p>Soundhardware ist über verschiedene Treiberinterfaces erreichbar.\n'
    + '     Phonalyser.web implementiert drei davon und lässt Sie in den Einstellungen\n'
    + '     einen auswählen; alle Module arbeiten identisch, unabhängig davon,\n'
    + '     welcher aktiv ist.</p>',
  uk: '  <h2 id="paths">Три шляхи драйверів</h2>\n'
    + '  <p>До звукового обладнання можна звертатись через кілька драйверних\n'
    + '     інтерфейсів.  Phonalyser.web реалізує три і дозволяє вибрати один у\n'
    + '     налаштуваннях; усі модулі працюють однаково поверх будь-якого\n'
    + '     активного шляху.</p>',
};
const QA_BACKEND_RE = /[ \t]*<tr><td>Backend<\/td>[\s\S]*?<\/tr>/;
const QA_BACKEND = {
  en: '    <tr><td>Backend</td>\n'
    + '        <td>WASAPI (default, exclusive bit-exact), WDM-KS (lowest-level,\n'
    + '            highest capture throughput) or JavaSound (portable / only option\n'
    + '            off Windows).  Changing it re-enumerates the device lists.</td></tr>',
  de: '    <tr><td>Backend</td>\n'
    + '        <td>WASAPI (Standard, exklusiv bit-exakt), WDM-KS (niedrigste Ebene,\n'
    + '            höchster Aufnahmedurchsatz) oder JavaSound (plattformübergreifend /\n'
    + '            einzige Option außerhalb von Windows).  Bei Änderung wird die\n'
    + '            Geräteliste neu eingelesen.</td></tr>',
  uk: '    <tr><td>Backend</td>\n'
    + '        <td>WASAPI (типово, ексклюзивний режим із побітовою точністю), WDM-KS (найнижчий\n'
    + '            рівень, найвища пропускна здатність захоплення) або JavaSound (переносний /\n'
    + '            єдиний варіант поза Windows).  Зміна значення повторно перераховує списки\n'
    + '            пристроїв.</td></tr>',
};

// changelog.html — the QA40x analyzer backend is a desktop-only feature the web cannot run, so its
// release-note bullet is dropped entirely. Anchored language-invariantly on the bold label, which
// starts with the product name in every locale (en "QA40x analyzer backend", de "QA40x-Analyzer-
// Backend", uk "QA40x …") — so `<li><b>QA40x…`. The "Web version catch-up" bullet STAYS; it only
// loses its parenthetical "(… QA40x …)" aside via the shared QA_PAREN_RE (which strips any
// QA40x-bearing parenthetical in any language), the restriction it describes kept, just no longer
// named after the vendor part. The catch-up bullet's label is "Web…", never "QA40x…", so the
// backend-bullet strip can't touch it. Anchor on QA40x appearing ANYWHERE inside the bold label
// (`<li><b>…QA40x…</b>`) — the translated word order puts it first (en/de) or last (uk "Бекенд
// аналізатора QA40x"), but it is always in the label. Run QA_PAREN_RE FIRST so the catch-up
// bullet's only QA40x (a parenthetical, never in its label) is already gone.
const QA_CHANGELOG_LI_RE = /[ \t]*<li><b>(?:(?!<\/b>)[\s\S])*?QA40x(?:(?!<\/b>)[\s\S])*?<\/b>(?:(?!<\/li>)[\s\S])*?<\/li>[ \t]*\n?/g;

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

// Strip the desktop-only "Calibration provided by device" (calibrationFromDevice) mentions
// from each language's preferences.html — the web UI no longer offers the flag.
async function stripCfdMentions() {
  for (const lang of langs) {
    const file = path.join(DST, lang, 'preferences.html');
    if (!await exists(file)) continue;
    const orig = await readFile(file, 'utf8');
    const html = orig.replace(CFD_LI_RE, '').replace(CFD_TR_RE, '');
    if (html !== orig) await writeFile(file, html);
  }
}
await stripCfdMentions();

// Strip the QA40x analyzer backend from the web help (see the block comment above the QA_*
// patterns): the browser has no vendor-USB path, so QA40x is excluded while WASAPI / WDM-KS /
// JavaSound stay. Touches each language's audio-backend, preferences, theory index and the A-Z
// help index; every rewrite is a no-op once already stripped, so the whole pass is idempotent.
async function stripQa40xMentions() {
  const rewrite = async (file, fn) => {
    if (!await exists(file)) return;
    const orig = await readFile(file, 'utf8');
    const html = fn(orig);
    if (html !== orig) await writeFile(file, html);
  };
  for (const lang of langs) {
    const root = path.join(DST, lang);
    await rewrite(path.join(root, 'theory', 'audio-backend.html'), (h) => h
      .replace(QA_CHAPTER_RE, '')
      .replace(QA_LIBUSB_SUP_RE, '')
      .replace(QA_LIBUSB_LI_RE, '')
      .replace(QA_PATHS_RE, () => QA_PATHS[lang] ?? QA_PATHS.en));
    await rewrite(path.join(root, 'preferences.html'), (h) => h
      .replace(QA_NOTE_RE, '')
      .replace(QA_BACKEND_RE, () => QA_BACKEND[lang] ?? QA_BACKEND.en));
    await rewrite(path.join(root, 'theory', 'index.html'), (h) => h
      .replace(QA_PAREN_RE, ''));
    await rewrite(path.join(root, 'help-index.html'), (h) => h
      .replace(QA_INDEX_LI_RE, '')
      .replace(QA_EMPTY_GROUP_RE, ''));
    await rewrite(path.join(root, 'changelog.html'), (h) => h
      .replace(QA_PAREN_RE, '')          // de-name the "(… QA40x …)" aside in the Web-catch-up bullet (any language)
      .replace(QA_CHANGELOG_LI_RE, '')); // drop the "QA40x …" backend feature bullet
  }
}
await stripQa40xMentions();

console.log(`imported help: ${SRC} -> ${DST}`);
console.log(`  languages: ${langs.join(', ')}  (img/ preserved, ?hl highlighter injected)`);
