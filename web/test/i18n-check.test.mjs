/*
 * Automated i18n consistency check for the web port.
 *
 * Goal (resolves a recurring dispute the agent must NOT eyeball again):
 *   The Java desktop app is the i18n BASELINE.  The web bundle
 *   web/i18n/messages.properties extends it with a small set of web-only
 *   "web.*" keys.  A web.* key is only legitimate when it has NO Java
 *   counterpart - i.e. its VALUE is not already provided verbatim by some
 *   non-web Java key.  If a web.* value duplicates an existing Java value,
 *   the web markup should reference the Java key instead of inventing a
 *   parallel web.* one.
 *
 * What this does:
 *   1. Parse the Java baseline   modules/phonalyser-gui/src/main/resources/i18n/messages.properties
 *   2. Parse the web bundle       web/i18n/messages.properties
 *   3. Scan ALL i18n references in web/index.html + web/js:
 *        - data-i18n="KEY"
 *        - data-i18n-title="KEY"
 *        - data-i18n-placeholder="KEY"   (HTML, same family)
 *        - data-i18n-browse="KEY"        (HTML, same family)
 *        - t('KEY') / t("KEY")           (JS calls)
 *        - somethingKey: 'KEY'           (JS, key handed to t() INDIRECTLY)
 *        - new Unit('KEY', ...)            (JS, key as a POSITIONAL argument)
 *   4. For EVERY referenced web.* key print: the key, its value, and whether
 *      that exact value exists under a non-web Java key (and which key).
 *   5. FAIL if:
 *        - any referenced key is undefined (not in web bundle, which already
 *          contains every Java key), OR
 *        - any referenced web.* value duplicates a non-web Java value
 *          (excluding the explicitly allowed web-only keys below).
 *
 * This file is part of `npm test` (the runner's glob is test/*.test.mjs).  It used to be
 * named i18n-check.mjs, which that glob does not match, so it only ever ran when someone
 * remembered to - and nobody did: twelve undefined qa40x.settings.* keys sailed past it
 * into the e2e.  Being in the suite is half the fix; seeing indirect references (step 3's
 * last two patterns) is the other half.  Still runnable on its own:
 *   cd web && node test/i18n-check.test.mjs
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(__dirname, '..', '..');

// The Java baseline moved with the 2026-07 Maven restructure (the GUI module owns the bundles);
// this path was left pointing at the pre-restructure root, so the test crashed at import - which
// is why it had been failing for the whole of R2.1/R2.2 (the one known-red test of both rounds).
const JAVA_PROPS = resolve(REPO, 'modules/phonalyser-gui/src/main/resources/i18n/messages.properties');
const WEB_PROPS = resolve(REPO, 'web/i18n/messages.properties');
const INDEX_HTML = resolve(REPO, 'web/index.html');
const JS_DIR = resolve(REPO, 'web/js');

/** The only web.* keys allowed to exist WITHOUT a Java counterpart whose value
 *  they happen to duplicate.  These are genuinely web-only controls. */
const ALLOWED_WEB_ONLY = new Set([
  // web.preferences.scanDevices is gone: the desktop grew a Scan devices button of its own, so the
  // control is no longer web-only and both apps now share preferences.devices.scan (B25).
  'web.preferences.adcFullScale',
  'web.preferences.adcFullScale.tooltip',
  'web.preferences.dacFullScale',
  'web.preferences.dacFullScale.tooltip',
  // A one-word caption of a web-only control whose text coincides with an unrelated desktop
  // string: the Tools menu item naming the preferences STORE reads like preferences.title
  // (the dialog's own title). Different controls, same word - so it keeps its own key rather
  // than borrowing the other's.
  'web.menu.tools.config.preferences',
]);

/** The bundle rule this file guards: a string the desktop also has carries the
 *  DESKTOP key unchanged - never a renamed twin - and a string only the browser
 *  needs is its own key with the web. prefix, in the block at the bottom of every
 *  locale file.  Every per-control key was resolved against the Java GUI: the ones
 *  with a counterpart took the Java key (the THD-band labels onto fft.thd.distMin /
 *  fft.thd.distMax), the ones whose desktop control carries no such text had their
 *  markup stripped (the inline Channel/Edge/Mode/Format labels, the icon-only
 *  file-browse, the preset placeholder/dropdown, the sash, and the Threads /
 *  Warm-up / De-embed / IMD-hint readouts now use a plain literal, and so does the
 *  display-persistence dropdown, which mirrors the desktop's hardcoded
 *  PersistenceMode.LABELS), and what genuinely has no desktop counterpart - the
 *  JSON config editor, the browser-only net diagnostics - is prefixed. */
const NOTED_WEB_ONLY = new Set();

/** The whitelist of web.* keys allowed to SHARE a value with a non-web key; any
 *  other web.* key must carry a value of its own (asserted below).  The number of
 *  web.* keys itself is not capped - see the inventory note further down. */
const ALLOWED_WEB_KEYS = new Set([...ALLOWED_WEB_ONLY, ...NOTED_WEB_ONLY]);

// ---------------------------------------------------------------------------
// .properties parsing (java.util.Properties subset - matches web/js/i18n/i18n.js)
// ---------------------------------------------------------------------------

function unescape(s) {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    let c = s[i];
    if (c !== '\\') { out += c; continue; }
    c = s[++i];
    if (c === undefined) break;
    switch (c) {
      case 't': out += '\t'; break;
      case 'n': out += '\n'; break;
      case 'r': out += '\r'; break;
      case 'f': out += '\f'; break;
      case 'u': out += String.fromCharCode(parseInt(s.substr(i + 1, 4), 16)); i += 4; break;
      default: out += c;
    }
  }
  return out;
}

/** Named/numeric HTML entities seen in these property files (kept minimal - the
 *  bundles use only this handful).  Decoding them lets a web.* value written with
 *  an entity compare equal to the same glyph in a Java value. */
const HTML_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'",
  hellip: '...', mdash: '-', ndash: '-', nbsp: ' ',
};

/** Decodes the named/numeric HTML entities above (used by {@link normalizeValue}). */
function decodeHtmlEntities(s) {
  return s.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (m, body) => {
    if (body[0] === '#') {
      const code = body[1] === 'x' || body[1] === 'X'
        ? parseInt(body.slice(2), 16)
        : parseInt(body.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : m;
    }
    const repl = HTML_ENTITIES[body.toLowerCase()];
    return repl !== undefined ? repl : m;
  });
}

/** Canonical form for value comparison (resolves the too-strict matching that
 *  missed real duplicates): Java escapes are already decoded by the parser's
 *  {@link unescape}; here we additionally decode HTML entities, collapse every
 *  internal whitespace run (incl. NBSP) to a single space, and trim the ends. */
function normalizeValue(s) {
  return decodeHtmlEntities(s).replace(/[\s ]+/g, ' ').trim();
}

function trailingBackslashes(line) {
  let n = 0;
  for (let i = line.length - 1; i >= 0 && line[i] === '\\'; i--) n++;
  return n;
}

/** @returns {Map<string,string>} */
function parseProperties(text) {
  const map = new Map();
  const physical = text.replace(/\r\n?/g, '\n').split('\n');
  for (let i = 0; i < physical.length; i++) {
    let line = physical[i].replace(/^[ \t\f]+/, '');
    if (line === '') continue;
    const first = line[0];
    if (first === '#' || first === '!') continue;
    while (trailingBackslashes(line) % 2 === 1 && i + 1 < physical.length) {
      line = line.slice(0, -1) + physical[++i].replace(/^[ \t\f]+/, '');
    }
    let sepIdx = -1;
    let sepIsKv = false;
    for (let j = 0; j < line.length; j++) {
      const ch = line[j];
      if (ch === '\\') { j++; continue; }
      if (ch === '=' || ch === ':') { sepIdx = j; sepIsKv = true; break; }
      if (ch === ' ' || ch === '\t' || ch === '\f') { sepIdx = j; break; }
    }
    let key, value;
    if (sepIdx === -1) { key = line; value = ''; }
    else {
      key = line.slice(0, sepIdx);
      let k = sepIdx;
      if (!sepIsKv) {
        while (k < line.length && ' \t\f'.includes(line[k])) k++;
        if (line[k] === '=' || line[k] === ':') k++;
      } else { k++; }
      while (k < line.length && ' \t\f'.includes(line[k])) k++;
      value = line.slice(k);
    }
    map.set(unescape(key), unescape(value));
  }
  return map;
}

// ---------------------------------------------------------------------------
// Reference scanning
// ---------------------------------------------------------------------------

function listJsFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = resolve(dir, name);
    if (statSync(full).isDirectory()) out.push(...listJsFiles(full));
    else if (name.endsWith('.js') || name.endsWith('.mjs')) out.push(full);
  }
  return out;
}

/** A dotted i18n key as it appears inside quotes.  Requiring at least one dot is what
 *  keeps the two INDIRECT patterns below from mistaking a non-i18n string for a key:
 *  every one of the bundle's keys is dotted, so a bare `'i2s'`-style storage/map key
 *  cannot be swept up and reported as undefined. */
const DOTTED_KEY = String.raw`[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+`;

const ATTR_RE = /data-i18n(?:-title|-placeholder|-browse)?\s*=\s*"([^"]+)"/g;
const T_CALL_RE = /\bt\(\s*(['"])([A-Za-z0-9_.]+)\1/g;

/** Keys that reach t() INDIRECTLY, as the value of a `...Key` property or variable rather
 *  than as a literal argument: qa40x-settings-dialog.js's `labelKey: 'qa40x.settings....'`
 *  rows and calibration-dialog.js's `titleKey`/`promptKey`/`tooltipKey` config objects.
 *  T_CALL_RE cannot see these - the call site is `t(labelKey)` - and that blind spot is
 *  exactly how twelve undefined qa40x.settings.* keys reached the e2e.
 *  The suffix is matched with a CAPITAL K, this codebase's camelCase convention, which
 *  also excludes fft-view.js's unrelated all-lowercase `rkey`. */
const KEY_PROP_RE = new RegExp(String.raw`\b\w+Key\s*[:=]\s*(['"])(${DOTTED_KEY})\1`, 'g');

/** Unit suffix keys, which reach t() as `new Unit(<key>, ...)`'s FIRST POSITIONAL argument
 *  (numeric-step-field.js's UNIT_FAMILIES table -> Unit.suffix()).  Same defect class as
 *  KEY_PROP_RE - a key with no `t('...')` at its site and no `...Key` name to match on.
 *  `new Unit(null, ...)` (UnitFamily.NONE, the suffix-less family) simply does not match. */
const UNIT_CTOR_RE = new RegExp(String.raw`\bnew Unit\(\s*(['"])(${DOTTED_KEY})\1`, 'g');

/** Collect referenced keys -> array of source labels. @returns {Map<string,Set<string>>} */
function collectReferences() {
  const refs = new Map();
  const add = (key, src) => {
    if (!refs.has(key)) refs.set(key, new Set());
    refs.get(key).add(src);
  };

  // HTML attributes (index.html)
  const html = readFileSync(INDEX_HTML, 'utf8');
  for (const m of html.matchAll(ATTR_RE)) add(m[1], 'index.html');

  // JS: t('KEY') calls, the two indirect forms, AND any data-i18n* attributes embedded
  // in JS template strings.
  for (const file of listJsFiles(JS_DIR)) {
    const text = readFileSync(file, 'utf8');
    const label = file.slice(REPO.length + 1).replace(/\\/g, '/');
    for (const m of text.matchAll(T_CALL_RE)) add(m[2], label);
    for (const m of text.matchAll(ATTR_RE)) add(m[1], label);
    for (const m of text.matchAll(KEY_PROP_RE)) add(m[2], label);
    for (const m of text.matchAll(UNIT_CTOR_RE)) add(m[2], label);
  }
  return refs;
}

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

const javaProps = parseProperties(readFileSync(JAVA_PROPS, 'utf8'));
const webProps = parseProperties(readFileSync(WEB_PROPS, 'utf8'));

// Reverse index of NON-web values -> the key(s) that hold them, NORMALIZED so a
// web.* value matches an existing key even when they differ only by HTML escaping
// or whitespace.  Indexes BOTH the Java baseline AND the web bundle's non-web
// (copied Java) keys - a web.* value duplicating either should reference the
// existing key, never invent a parallel web.* one.
const valueToKeys = new Map();
const indexNonWeb = (props) => {
  for (const [k, v] of props) {
    if (k.startsWith('web.')) continue;
    const nv = normalizeValue(v);
    if (!valueToKeys.has(nv)) valueToKeys.set(nv, []);
    if (!valueToKeys.get(nv).includes(k)) valueToKeys.get(nv).push(k);
  }
};
indexNonWeb(javaProps);
indexNonWeb(webProps);

const refs = collectReferences();
const refKeys = [...refs.keys()].sort();

console.log('='.repeat(78));
console.log('i18n-check - Java baseline vs web bundle vs referenced keys');
console.log('='.repeat(78));
console.log(`Java baseline keys : ${javaProps.size}`);
console.log(`Web bundle keys    : ${webProps.size}`);
console.log(`Referenced keys    : ${refKeys.length}`);
console.log('');

// 1) Undefined references (any referenced key absent from the web bundle).
// A literal ending in '.' is a DYNAMIC key prefix (e.g. t('tip.' + n + '.title')
// in tip-dialog.js) - satisfied when ANY bundle key starts with it.
const undefinedKeys = [];
for (const key of refKeys) {
  if (key.endsWith('.')) {
    if (![...webProps.keys()].some((k) => k.startsWith(key))) undefinedKeys.push(key);
    continue;
  }
  if (!webProps.has(key)) undefinedKeys.push(key);
}

// 2) EVERY web.* key in the bundle (referenced or not): value + duplicate verdict
// against the normalized non-web index (Java baseline + copied web keys).  A
// match means the markup should reference the existing key, not this web.* one.
const duplicateWebKeys = []; // web.* value duplicates a Java value (not allowed)
const allWebKeys = [...webProps.keys()].filter((k) => k.startsWith('web.')).sort();
console.log('-'.repeat(78));
console.log(`web.* keys in bundle (${allWebKeys.length}):`);
console.log('-'.repeat(78));
for (const key of allWebKeys) {
  const value = webProps.get(key);
  const dupKeys = valueToKeys.get(normalizeValue(value)) || [];
  const allowed = ALLOWED_WEB_ONLY.has(key);
  const referenced = refs.has(key);
  let verdict;
  if (dupKeys.length > 0) {
    verdict = `DUPLICATES key(s): ${dupKeys.join(', ')}`;
    if (allowed) verdict += '  [allowed web-only]';
    else duplicateWebKeys.push({ key, value, dupKeys, referenced });
  } else {
    verdict = allowed ? 'web-only (no value match) [allowed]'
                      : 'web-only (no value match)';
  }
  console.log(`  ${key}${referenced ? '' : '   (UNREFERENCED)'}`);
  console.log(`      value   : ${JSON.stringify(value)}`);
  console.log(`      verdict : ${verdict}`);
}
console.log('');

// 3) Web-only key inventory (INFORMATIONAL - no longer a hard cap).  A genuinely
// web-only web.* key (one whose value does NOT duplicate a non-web value) is
// legitimate however many there are - the 5-key whitelist is no longer a ceiling.
// The ONLY failures are (1) an undefined referenced key and (2) a web.* value
// that DUPLICATES a Java value (asserted below).  This list just surfaces the
// non-whitelisted genuinely-web-only keys so they stay visible in review.
const strayWebKeys = allWebKeys.filter((k) => !ALLOWED_WEB_KEYS.has(k));
if (strayWebKeys.length) {
  console.log(`NOTE: ${strayWebKeys.length} genuinely web-only web.* key(s) outside the `
    + `${ALLOWED_WEB_ONLY.size}-key whitelist (allowed - not a duplicate of any Java value):`);
  for (const k of strayWebKeys) {
    console.log(`  ${k} = ${JSON.stringify(webProps.get(k))}`);
  }
} else {
  console.log(`OK: all ${allWebKeys.length} web.* keys are whitelisted `
    + `(the ${ALLOWED_WEB_ONLY.size} allowed web-only keys).`);
}
console.log('-'.repeat(78));

// ---------------------------------------------------------------------------
// The gate
// ---------------------------------------------------------------------------

test('every referenced key is defined in the web bundle', () => {
  const detail = undefinedKeys
    .map((k) => `  ${k}   (referenced by: ${[...refs.get(k)].join(', ')})`)
    .join('\n');
  assert.deepEqual(undefinedKeys, [],
    `${undefinedKeys.length} referenced key(s) UNDEFINED in the web bundle:\n${detail}`);
});

test('no web.* value duplicates a non-web value', () => {
  const detail = duplicateWebKeys
    .map(({ key, value, dupKeys, referenced }) => `  ${key} = ${JSON.stringify(value)}`
      + `  ->  ${dupKeys.join(', ')}${referenced ? '' : '  [unreferenced - just delete]'}`)
    .join('\n');
  assert.deepEqual(duplicateWebKeys.map((d) => d.key), [],
    `${duplicateWebKeys.length} web.* key(s) duplicate an existing non-web value `
    + `(reference the existing key instead, or add to ALLOWED_WEB_ONLY):\n${detail}`);
});

/*
 * The scanner's own smoke test.  Both checks above are vacuously green against a scanner
 * that sees nothing, which is precisely the failure this file just came out of - so pin
 * one live reference per pattern.  Deleting any one of the four matchers in
 * collectReferences(), or narrowing its regex, fails HERE instead of going quietly blind.
 */
test('the reference scanner sees all four reference forms', () => {
  const seen = (key, pattern) => assert.ok(refs.has(key),
    `${pattern} found no reference to ${key} - the scanner has gone blind to that form`);

  seen('preferences.title', "data-i18n=");                        // ATTR_RE (index.html)
  seen('qa40x.settings.i2s', "t('...')");                           // T_CALL_RE
  seen('qa40x.settings.serialNumber', "somethingKey: '...'");       // KEY_PROP_RE
  seen('unit.percent', "new Unit('...', ...)");                       // UNIT_CTOR_RE

  // KEY_PROP_RE must stay narrow as well as wide: an all-lowercase `rkey` and a
  // non-dotted value are NOT i18n references, and sweeping them in would report
  // phantom undefined keys.
  const probe = (src) => [...src.matchAll(KEY_PROP_RE)].map((m) => m[2]);
  assert.deepEqual(probe("labelKey: 'a.b'"), ['a.b']);
  assert.deepEqual(probe("rkey = 'a.b'"), [], 'lowercase `rkey` is not an i18n key holder');
  assert.deepEqual(probe("storageKey: 'flat'"), [], 'a non-dotted value is not an i18n key');
});
