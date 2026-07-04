/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.i18n.I18n (the translation
// helper backed by a java.util.ResourceBundle) plus the slice of
// java.util.Properties / java.text.MessageFormat behaviour it relies on.
//
// Contract preserved from the Java original:
//   - Bundles live at i18n/messages.properties (English, default) and
//     per-locale messages_<tag>.properties (e.g. messages_de.properties,
//     messages_zh_TW.properties).  In the browser these are fetched over HTTP
//     from web/i18n/.
//   - Lookup follows the ResourceBundle PARENT CHAIN: a key is resolved in the
//     most-specific bundle first, then each less-specific parent, finally the
//     base (English) bundle.  zh-TW -> zh -> base, de-AT -> de -> base, etc.
//   - A missing key returns the KEY ITSELF (never throws), so untranslated
//     strings are obvious at runtime without crashing the UI.
//   - t(key, ...args): when args are supplied the pattern is run through
//     MessageFormat so {0} / {1,number,#.##} placeholders substitute.
//
// .properties parsing matches java.util.Properties: '=' / ':' / whitespace
// separators, '#' and '!' comments, backslash escapes (\t \n \r \f \\ \uXXXX),
// line continuation via a trailing backslash, and leading-whitespace trim on
// continuation lines.

const BASE_DIR = 'i18n';
const BASE_FILE = 'messages.properties';

/** Per-locale parsed bundles, keyed by the file suffix ("" = base English).
 *  Each value is a Map<string,string> or null (failed/absent fetch). */
const cache = new Map();

/** The active fallback chain of parsed bundles, most-specific first, base last.
 *  Rebuilt by {@link setLocale}; defaults to base-only until a locale is set. */
let chain = [];

/** BCP-47 tag of the active locale (e.g. "de", "zh-TW"). */
let currentTag = 'en';

// ---------------------------------------------------------------------------
// .properties parsing (java.util.Properties subset)
// ---------------------------------------------------------------------------

/** Decodes the backslash escapes Java's Properties loader understands within a
 *  key or value: \t \n \r \f, \uXXXX, and \<anything-else> -> that char. */
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
      case 'u': {
        const hex = s.substr(i + 1, 4);
        out += String.fromCharCode(parseInt(hex, 16));
        i += 4;
        break;
      }
      default: out += c; // \= \: \# \\ \  etc. -> literal char
    }
  }
  return out;
}

/** Number of trailing backslashes immediately before the end of `line`;
 *  an ODD count means the logical line continues onto the next physical line. */
function trailingBackslashes(line) {
  let n = 0;
  for (let i = line.length - 1; i >= 0 && line[i] === '\\'; i--) n++;
  return n;
}

/**
 * Parse a .properties document into a Map, matching java.util.Properties.
 * @param {string} text raw file contents (ISO-8859-1 / ASCII with \\u escapes,
 *                       or UTF-8 — both decode the same for the \\u-escaped data
 *                       these bundles use).
 * @returns {Map<string,string>}
 */
export function parseProperties(text) {
  const map = new Map();
  // Normalise newlines; split into physical lines.
  const physical = text.replace(/\r\n?/g, '\n').split('\n');
  for (let i = 0; i < physical.length; i++) {
    // Skip leading whitespace of a logical line.
    let line = physical[i].replace(/^[ \t\f]+/, '');
    if (line === '') continue;
    const first = line[0];
    if (first === '#' || first === '!') continue; // comment

    // Stitch continuation lines (logical line ends on an even # of trailing '\').
    while (trailingBackslashes(line) % 2 === 1 && i + 1 < physical.length) {
      line = line.slice(0, -1) + physical[++i].replace(/^[ \t\f]+/, '');
    }

    // Find the key/value separator: first un-escaped '=' or ':', or the first
    // un-escaped run of whitespace.
    let sepIdx = -1;
    let sepIsKv = false; // '=' or ':' explicitly seen
    let valStart = -1;
    for (let j = 0; j < line.length; j++) {
      const ch = line[j];
      if (ch === '\\') { j++; continue; } // skip escaped char
      if (ch === '=' || ch === ':') { sepIdx = j; sepIsKv = true; break; }
      if (ch === ' ' || ch === '\t' || ch === '\f') {
        // Whitespace separator — but '=' / ':' after it still wins as the
        // real separator (Properties skips ws then optionally one '='/':').
        sepIdx = j;
        break;
      }
    }

    let key, value;
    if (sepIdx === -1) {
      key = line;
      value = '';
    } else {
      key = line.slice(0, sepIdx);
      // Skip the separator + any whitespace, then an optional single '='/':'
      // and its trailing whitespace.
      let k = sepIdx;
      if (!sepIsKv) {
        // advance over the whitespace run
        while (k < line.length && (line[k] === ' ' || line[k] === '\t' || line[k] === '\f')) k++;
        if (line[k] === '=' || line[k] === ':') k++;
      } else {
        k++; // past the '='/':'
      }
      while (k < line.length && (line[k] === ' ' || line[k] === '\t' || line[k] === '\f')) k++;
      valStart = k;
      value = line.slice(valStart);
    }
    map.set(unescape(key), unescape(value));
  }
  return map;
}

// ---------------------------------------------------------------------------
// Bundle fetching + parent chain
// ---------------------------------------------------------------------------

/** Fetches and parses one bundle by its file suffix ("" = base). Cached;
 *  a failed fetch caches null so we don't retry on every lookup. */
async function loadBundle(suffix) {
  if (cache.has(suffix)) return cache.get(suffix);
  const name = suffix === '' ? BASE_FILE : `messages_${suffix}.properties`;
  let parsed = null;
  try {
    const resp = await fetch(`${BASE_DIR}/${name}`);
    if (resp.ok) parsed = parseProperties(await resp.text());
  } catch {
    parsed = null;
  }
  cache.set(suffix, parsed);
  return parsed;
}

/** BCP-47 tag -> ordered list of file suffixes, most-specific first, ending
 *  with "" (base).  Mirrors ResourceBundle candidate-locale stripping:
 *  zh-TW -> ["zh_TW", "zh", ""];  de-AT -> ["de_AT", "de", ""];  en -> [""]. */
function suffixChainForTag(tag) {
  const norm = String(tag || '').replace(/-/g, '_');
  const parts = norm.split('_').filter(Boolean);
  const out = [];
  // English is the base bundle (no suffix), so an "en" request maps to base.
  if (parts.length === 1 && parts[0].toLowerCase() === 'en') {
    return [''];
  }
  for (let n = parts.length; n >= 1; n--) {
    out.push(parts.slice(0, n).join('_'));
  }
  out.push('');
  return out;
}

/**
 * Switch the active locale.  Fetches the locale's bundle and its parents and
 * installs the resolved fallback chain so subsequent {@link t} calls return
 * strings from the new language (falling back to base English per key).
 * @param {string} tag BCP-47 language tag (e.g. "de", "zh-TW", "en").
 * @returns {Promise<void>}
 */
export async function setLocale(tag) {
  if (!tag) return;
  currentTag = tag;
  const suffixes = suffixChainForTag(tag);
  const bundles = await Promise.all(suffixes.map(loadBundle));
  chain = bundles.filter((b) => b != null);
}

/**
 * Pre-load only the base English bundle and install it as the chain.  Call
 * once at startup so {@link t} works before any {@link setLocale}.
 * @returns {Promise<void>}
 */
export async function initBase() {
  const base = await loadBundle('');
  chain = base != null ? [base] : [];
  currentTag = 'en';
}

/** @returns {string} the active locale's BCP-47 tag. */
export function getLocale() {
  return currentTag;
}

// ---------------------------------------------------------------------------
// MessageFormat (java.text.MessageFormat subset)
// ---------------------------------------------------------------------------

/** Format one argument for a {n[,type[,style]]} element.  Supports the common
 *  cases the bundles use: plain {0}, and {0,number,...}.  Unknown types fall
 *  back to String.valueOf. */
function formatElement(arg, type, style) {
  if (type === 'number') {
    if (typeof arg !== 'number') arg = Number(arg);
    if (style === 'integer') return String(Math.round(arg));
    if (style === 'percent') return `${(arg * 100)}%`;
    if (style && /^[#0.,]+$/.test(style)) return formatDecimalPattern(arg, style);
    return String(arg);
  }
  return arg === null || arg === undefined ? '' : String(arg);
}

/** Minimal DecimalFormat for patterns like "#.##" / "0.00": honours the count
 *  of '#'/'0' after the decimal point as max/min fraction digits. */
function formatDecimalPattern(value, pattern) {
  const dot = pattern.indexOf('.');
  if (dot === -1) return String(Math.round(value));
  const frac = pattern.slice(dot + 1);
  const max = frac.length;
  const min = (frac.match(/0/g) || []).length;
  let s = value.toFixed(max);
  if (max > min) {
    // trim trailing zeros down to `min` fraction digits
    s = s.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
    const d = s.indexOf('.');
    const have = d === -1 ? 0 : s.length - d - 1;
    if (have < min) s = (d === -1 ? s + '.' : s) + '0'.repeat(min - have);
  }
  return s;
}

/**
 * Apply java.text.MessageFormat to `pattern` with positional `args`.
 * Handles single-quote literal/escape rules ('' -> ', '...' quotes a span),
 * {n}, and {n,type[,style]} elements.
 * @param {string} pattern
 * @param {Array<*>} args
 * @returns {string}
 */
export function messageFormat(pattern, args) {
  let out = '';
  let i = 0;
  const n = pattern.length;
  while (i < n) {
    const c = pattern[i];
    if (c === '\'') {
      if (pattern[i + 1] === '\'') { out += '\''; i += 2; continue; }
      // quoted span: copy verbatim until the next single quote
      i++;
      while (i < n && pattern[i] !== '\'') out += pattern[i++];
      i++; // skip closing quote (if any)
      continue;
    }
    if (c === '{') {
      let depth = 1;
      let j = i + 1;
      let body = '';
      while (j < n && depth > 0) {
        const cj = pattern[j];
        if (cj === '{') depth++;
        else if (cj === '}') { depth--; if (depth === 0) break; }
        body += cj;
        j++;
      }
      // body = "index[,type[,style]]"
      const comma1 = body.indexOf(',');
      const idxStr = (comma1 === -1 ? body : body.slice(0, comma1)).trim();
      const idx = parseInt(idxStr, 10);
      let type, style;
      if (comma1 !== -1) {
        const rest = body.slice(comma1 + 1);
        const comma2 = rest.indexOf(',');
        type = (comma2 === -1 ? rest : rest.slice(0, comma2)).trim();
        if (comma2 !== -1) style = rest.slice(comma2 + 1).trim();
      }
      const arg = args[idx];
      out += formatElement(arg, type, style);
      i = j + 1;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

// ---------------------------------------------------------------------------
// Translation lookup
// ---------------------------------------------------------------------------

/**
 * Translate `key`, walking the active parent chain (most-specific bundle
 * first, base English last).  When `args` are supplied the resolved pattern is
 * run through {@link messageFormat}.  A missing key returns the key itself.
 * @param {string} key  message key (e.g. "fft.title.expanded").
 * @param {...*} args    MessageFormat positional arguments.
 * @returns {string}
 */
export function t(key, ...args) {
  let pattern;
  for (const b of chain) {
    if (b.has(key)) { pattern = b.get(key); break; }
  }
  if (pattern === undefined) {
    console.warn(`Missing i18n key: ${key}`);
    return key;
  }
  if (args.length === 0) return pattern;
  return messageFormat(pattern, args);
}
