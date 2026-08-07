/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// The device catalog is NOT hard-coded here - it is the Java single source of truth,
// src/main/resources/devices.yaml, shipped VERBATIM next to the app (web/devices.yaml,
// re-copied every build) and parsed at runtime. This module owns that load:
//
//   parseDevicesYaml(text)  - a minimal, dependency-free YAML reader for exactly the
//                             devices.yaml subset (see below), returning the store
//                             document shape { formatVersion, contentVersion, audioDevices }.
//   loadDeviceCatalog(url)  - fetch + parse; null on any failure (the app then boots with
//                             the user's localStorage cards and no fresh seed).
//
// The document the parser returns is the exact vocabulary DeviceProfileStore._readProfile
// consumes (the same key-for-key shape a persisted store carries): top-level formatVersion /
// contentVersion ints and an audioDevices list of cards, each with name, match (a string list),
// and input / output endpoint blocks (channels, ranges of { label, fsVrms }, activeRange,
// activeRangeRight). fsVrms is a { left, right } pair OR a scalar
// shorthand (both channels); activeRange is a scalar row label (LINKED / MONO) OR a
// { left, right } map (INDEPENDENT). Unknown keys are preserved - the store reader decides
// what to keep. See the devices.yaml header comment for the full schema + upgrade behaviour.
//
// The seed-merge is gated on contentVersion in DeviceProfileStore (runs once when the catalog's
// contentVersion exceeds the store's recorded one, strictly greater - mirroring the desktop).

/** Wraps a parse failure with the 1-based source line for a pinpointable error. */
function parseError(line, msg) {
  return new Error(`devices.yaml parse error at line ${line}: ${msg}`);
}

const NUMBER_RE = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** Strips a trailing (or full-line) `#` comment, honouring single / double quotes so a `#`
 *  inside a quoted string is never mistaken for a comment. */
function stripComment(line) {
  let inS = false, inD = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inS) { if (c === "'") inS = false; continue; }
    if (inD) { if (c === '"') inD = false; continue; }
    if (c === "'") { inS = true; continue; }
    if (c === '"') { inD = true; continue; }
    if (c === '#' && (i === 0 || /\s/.test(line[i - 1]))) return line.slice(0, i);
  }
  return line;
}

/** Tokenises the source into non-blank, comment-free lines with their leading-space indent
 *  and 1-based line number: { indent, text, line }. */
function preprocess(text) {
  const rawLines = text.split(/\r\n|\r|\n/);
  const out = [];
  for (let n = 0; n < rawLines.length; n++) {
    const stripped = stripComment(rawLines[n]);
    if (stripped.trim() === '') continue;               // blank or comment-only
    const indent = stripped.length - stripped.replace(/^ +/, '').length;
    const body = stripped.slice(indent).replace(/\s+$/, '');
    out.push({ indent, text: body, line: n + 1 });
  }
  return out;
}

/** Removes matching surrounding quotes (no-op for an unquoted token). */
function unquote(s) {
  if (s.length >= 2 && s[0] === '"' && s[s.length - 1] === '"') {
    return s.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, '\\');
  }
  if (s.length >= 2 && s[0] === "'" && s[s.length - 1] === "'") {
    return s.slice(1, -1).replace(/''/g, "'");
  }
  return s;
}

/** A flow scalar: quoted string, boolean, null, number (full double precision via parseFloat),
 *  or a bare string (e.g. INDEPENDENT / LINKED). */
function parseScalar(s) {
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) return unquote(s);
  if (s === 'true') return true;
  if (s === 'false') return false;
  if (s === 'null' || s === '~' || s === '') return null;
  if (NUMBER_RE.test(s)) return parseFloat(s);
  return s;
}

/** Splits a flow-collection body on top-level commas (ignoring commas nested in { } / [ ] or
 *  inside quotes), returning the trimmed non-empty parts. */
function splitTopLevel(s, line) {
  const parts = [];
  let depth = 0, inS = false, inD = false, start = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inS) { if (c === "'") inS = false; continue; }
    if (inD) { if (c === '"') inD = false; continue; }
    if (c === "'") { inS = true; continue; }
    if (c === '"') { inD = true; continue; }
    if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') depth--;
    else if (c === ',' && depth === 0) { parts.push(s.slice(start, i)); start = i + 1; }
  }
  parts.push(s.slice(start));
  if (depth !== 0) throw parseError(line, 'unbalanced brackets in flow collection');
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

/** Splits a `key: value` (or bare `key:`) at its first top-level colon; throws when none. */
function splitKey(text, line) {
  let inS = false, inD = false, depth = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inS) { if (c === "'") inS = false; continue; }
    if (inD) { if (c === '"') inD = false; continue; }
    if (c === "'") { inS = true; continue; }
    if (c === '"') { inD = true; continue; }
    if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') depth--;
    else if (c === ':' && depth === 0 && (i === text.length - 1 || /\s/.test(text[i + 1]))) {
      return { key: unquote(text.slice(0, i).trim()), rest: text.slice(i + 1).trim() };
    }
  }
  throw parseError(line, `expected "key: value" but found "${text}"`);
}

/** True when a block-list item's text begins a nested block map (`key: ...`) rather than a flow
 *  scalar / map / list. */
function isMapEntry(text) {
  if (text.startsWith('{') || text.startsWith('[') || text.startsWith('"') || text.startsWith("'")) return false;
  let inS = false, inD = false, depth = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inS) { if (c === "'") inS = false; continue; }
    if (inD) { if (c === '"') inD = false; continue; }
    if (c === "'") { inS = true; continue; }
    if (c === '"') { inD = true; continue; }
    if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') depth--;
    else if (c === ':' && depth === 0 && (i === text.length - 1 || /\s/.test(text[i + 1]))) return true;
  }
  return false;
}

/** A flow node: [ ... ] list, { ... } map (one level of nesting is enough for devices.yaml), or a
 *  scalar. */
function parseFlow(s, line) {
  s = s.trim();
  if (s.startsWith('[')) {
    if (!s.endsWith(']')) throw parseError(line, 'unterminated flow list');
    const inner = s.slice(1, -1).trim();
    return inner === '' ? [] : splitTopLevel(inner, line).map((p) => parseFlow(p, line));
  }
  if (s.startsWith('{')) {
    if (!s.endsWith('}')) throw parseError(line, 'unterminated flow map');
    const inner = s.slice(1, -1).trim();
    const obj = {};
    if (inner !== '') {
      for (const part of splitTopLevel(inner, line)) {
        const { key, rest } = splitKey(part, line);
        obj[key] = parseFlow(rest, line);
      }
    }
    return obj;
  }
  return parseScalar(s);
}

const isDash = (text) => text === '-' || text.startsWith('- ');

/** Dispatches a block node at {@code indent} to a sequence (leading `-`) or a mapping. */
function parseBlock(tokens, st, indent) {
  return isDash(tokens[st.i].text) ? parseBlockSeq(tokens, st, indent) : parseBlockMap(tokens, st, indent);
}

/** Reads consecutive `key: value` lines at exactly {@code indent} into an object; an empty value
 *  descends into a deeper block (map or sequence), else the flow value is parsed inline. */
function parseBlockMap(tokens, st, indent) {
  const map = {};
  while (st.i < tokens.length) {
    const tok = tokens[st.i];
    if (tok.indent !== indent || isDash(tok.text)) break;
    const { key, rest } = splitKey(tok.text, tok.line);
    st.i++;
    if (rest === '') {
      map[key] = (st.i < tokens.length && tokens[st.i].indent > indent)
        ? parseBlock(tokens, st, tokens[st.i].indent) : null;
    } else {
      map[key] = parseFlow(rest, tok.line);
    }
  }
  return map;
}

/** Reads consecutive `- ` items at exactly {@code indent}. A flow / scalar item is parsed inline;
 *  a `key: ...` item re-homes the dash line as the first entry of a nested block map (at the column
 *  where the item's keys align) so its continuation lines fold in. */
function parseBlockSeq(tokens, st, indent) {
  const arr = [];
  while (st.i < tokens.length) {
    const tok = tokens[st.i];
    if (tok.indent !== indent || !isDash(tok.text)) break;
    const afterDash = tok.text.slice(1);
    const spaces = afterDash.length - afterDash.replace(/^ +/, '').length;
    const itemText = afterDash.replace(/^ +/, '');
    if (itemText === '') {
      st.i++;
      arr.push((st.i < tokens.length && tokens[st.i].indent > indent)
        ? parseBlock(tokens, st, tokens[st.i].indent) : null);
      continue;
    }
    if (isMapEntry(itemText)) {
      const childIndent = indent + 1 + spaces;
      tokens[st.i] = { indent: childIndent, text: itemText, line: tok.line };
      arr.push(parseBlockMap(tokens, st, childIndent));
    } else {
      arr.push(parseFlow(itemText, tok.line));
      st.i++;
    }
  }
  return arr;
}

/**
 * Parses the devices.yaml subset into the store document { formatVersion, contentVersion,
 * audioDevices }. Pure (no shared state between calls); throws with a 1-based line number on
 * malformed input.
 */
export function parseDevicesYaml(text) {
  if (typeof text !== 'string') throw new Error('parseDevicesYaml: expected a string');
  const tokens = preprocess(text);
  if (tokens.length === 0) return { audioDevices: [] };
  const st = { i: 0 };
  const doc = parseBlockMap(tokens, st, tokens[0].indent);
  if (st.i < tokens.length) throw parseError(tokens[st.i].line, `unexpected content "${tokens[st.i].text}"`);
  return doc;
}

/**
 * Fetches and parses the shipped devices.yaml, returning the store document (the shape the
 * DeviceProfileStore `catalog` option expects). On any fetch / parse failure it logs and returns
 * null, so the caller can boot the store with no seed.
 */
export async function loadDeviceCatalog(url = 'devices.yaml') {
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status} fetching ${url}`);
    return parseDevicesYaml(await res.text());
  } catch (e) {
    console.error('Failed to load device catalog from', url, '-', e && e.message);
    return null;
  }
}
