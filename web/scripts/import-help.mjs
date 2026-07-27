/*
 * Phonalyser web — (re-)import the Java HTML help into the web app.
 *
 * The Java help under ../modules/phonalyser-gui/src/main/resources/help is the source of truth for the help
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
// Java help (source of truth).  It ships with the GUI module, hence modules/.
const SRC = path.resolve(here, '../../modules/phonalyser-gui/src/main/resources/help');
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

// (c) QA40x transport: the desktop reaches the analyzer through the native
// libusb library, the browser through the WebUSB API — a real difference in how
// the instrument is opened and in what the user must have installed, so the
// imported chapter would otherwise send web users hunting for a libusb that does
// not exist and never mention the Chrome/secure-context/WinUSB prerequisites.
// Three targeted rewrites per language (the sentence, the prerequisites note and
// the reference entry), anchored on the language-invariant #ref-libusb markers.
// Applied BEFORE the product rename, so "Phonalyser" below still becomes
// "Phonalyser.web". A wholesale strip of the QA40x section was the old approach
// and was wrong: the web DOES have this backend, it just reaches it differently.
const WEBUSB_REF = '<a href="https://developer.mozilla.org/en-US/docs/Web/API/WebUSB_API">WebUSB</a>';
const QA40X_WEB_SUBS = {
  en: [
    [/(?:through the USB library)\s*libusb<sup><a href="#ref-libusb">\[3\]<\/a><\/sup>[\s\S]*?<\/p>/,
     `through the browser's <b>WebUSB</b> interface<sup><a href="#ref-webusb">[3]</a></sup>, speaking the same vendor protocol the manufacturer's own software does.</p>`],
    [/<div class="note"><b>libusb[\s\S]*?<\/div>/,
     `<div class="note"><b>What the browser needs.</b>  WebUSB is implemented by Chrome and Edge only — Firefox and Safari do not support it, and the QA40x backend simply does not appear there.  The page must be served over <code>https://</code> or <code>localhost</code>, and the first connection needs a click: the browser opens its own device chooser, which only you can confirm.  On Windows the analyzer must be bound to a WinUSB-class driver — a per-machine step a web page cannot perform for you.  As with exclusive mode, one application owns the instrument at a time: the QA402 / QA403 must not be open in the QuantAsylum software, or in another tab, while Phonalyser uses it.</div>`],
    [/<li id="ref-libusb">[\s\S]*?<\/li>/,
     `<li id="ref-webusb">The browser API the QA40x backend uses to reach the analyzer — ${WEBUSB_REF}.</li>`],
  ],
  de: [
    [/(?:über die USB-Bibliothek)\s*libusb<sup><a href="#ref-libusb">\[3\]<\/a><\/sup>[\s\S]*?<\/p>/,
     `über die <b>WebUSB</b>-Schnittstelle des Browsers<sup><a href="#ref-webusb">[3]</a></sup> erreicht und dabei dasselbe Herstellerprotokoll spricht wie die herstellereigene Software.</p>`],
    [/<div class="note"><b>libusb[\s\S]*?<\/div>/,
     `<div class="note"><b>Was der Browser braucht.</b>  WebUSB ist nur in Chrome und Edge implementiert — Firefox und Safari unterstützen es nicht, dort erscheint das QA40x-Backend gar nicht.  Die Seite muss über <code>https://</code> oder <code>localhost</code> ausgeliefert werden, und die erste Verbindung erfordert einen Klick: Der Browser öffnet seinen eigenen Geräteauswahldialog, den nur Sie bestätigen können.  Unter Windows muss der Analysator an einen WinUSB-Klassentreiber gebunden sein — ein Schritt pro Rechner, den eine Webseite nicht für Sie ausführen kann.  Wie beim Exklusivmodus besitzt jeweils eine Anwendung das Instrument: Der QA402 / QA403 darf währenddessen weder in der QuantAsylum-Software noch in einem anderen Tab geöffnet sein.</div>`],
    [/<li id="ref-libusb">[\s\S]*?<\/li>/,
     `<li id="ref-webusb">Die Browser-Schnittstelle, über die das QA40x-Backend den Analysator erreicht — ${WEBUSB_REF}.</li>`],
  ],
  uk: [
    [/(?:через USB-бібліотеку)\s*libusb<sup><a href="#ref-libusb">\[3\]<\/a><\/sup>[\s\S]*?<\/p>/,
     `через інтерфейс <b>WebUSB</b> браузера<sup><a href="#ref-webusb">[3]</a></sup>, використовуючи той самий протокол виробника, що й його власне програмне забезпечення.</p>`],
    [/<div class="note"><b>libusb[\s\S]*?<\/div>/,
     `<div class="note"><b>Що потрібно браузеру.</b>  WebUSB реалізовано лише в Chrome та Edge — Firefox і Safari його не підтримують, і бекенд QA40x там просто не з'являється.  Сторінка має віддаватися через <code>https://</code> або <code>localhost</code>, а перше під'єднання потребує кліку: браузер відкриває власний діалог вибору пристрою, підтвердити який можете лише ви.  У Windows аналізатор має бути прив'язаний до драйвера класу WinUSB — це крок для кожної машини, який вебсторінка не може виконати за вас.  Як і в ексклюзивному режимі, приладом одночасно володіє одна програма: QA402 / QA403 не має бути відкритий ані в програмі QuantAsylum, ані в іншій вкладці.</div>`],
    [/<li id="ref-libusb">[\s\S]*?<\/li>/,
     `<li id="ref-webusb">Браузерний інтерфейс, через який бекенд QA40x звертається до аналізатора — ${WEBUSB_REF}.</li>`],
  ],
};
// The Preferences page says the analyzer "appears in the list only when a
// QA402 / QA403 is connected to the PC"; in the browser it also requires WebUSB
// support, and the app gates the entry on exactly that.
const QA40X_PREFS_SUBS = {
  en: [[/connected to the PC/, 'connected and the browser supports WebUSB']],
  de: [[/mit dem PC verbunden ist/, 'verbunden ist und der Browser WebUSB unterstützt']],
  uk: [[/під'єднано до ПК/, "під'єднано, а браузер підтримує WebUSB"]],
};

async function injectViewer(dir, langRoot, lang) {
  for (const e of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { await injectViewer(full, langRoot, lang); continue; }
    if (!e.name.endsWith('.html')) continue;
    const orig = await readFile(full, 'utf8');
    let html = orig;
    for (const [find, repl] of (QA40X_WEB_SUBS[lang] ?? [])) html = html.replace(find, repl);
    if (e.name === 'preferences.html') {
      for (const [find, repl] of (QA40X_PREFS_SUBS[lang] ?? [])) html = html.replace(find, repl);
    }
    html = html.replace(PRODUCT_RENAME, 'Phonalyser.web');
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
  await injectViewer(langDir, langDir, lang);
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
