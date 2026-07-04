/*
 * Phonalyser web — a tiny localStorage-backed file store (loaded .frc calibration
 * rows · loaded .dpd predistortion), so a file the user picked survives a reload.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The browser sandbox exposes only a picked file's NAME, not a re-readable OS path,
 * so a reload can't re-open it from disk (unlike the Java desktop, which persists the
 * path and re-reads the file). This module keeps the file's own bytes/text under a key
 * so the pane can restore its rows + reapply the calibration/predistortion at startup.
 *
 * API (the generator .dpd slots — SMALL text files):
 *   put(key, name, arrayBufferOrText) — store a named blob (text kept verbatim; an
 *                                       ArrayBuffer / typed array base64-encoded).
 *   get(key)  -> { name, data } | null — data is the original text or an ArrayBuffer.
 *   remove(key)                        — drop the entry.
 *   keys(prefix) -> string[]           — the stored keys starting with `prefix`.
 *
 * LARGE files (multi-MB .frc calibration text — the FFT calibration rows) use the
 * async IndexedDB-backed quartet idbPut / idbGet / idbRemove / idbKeys instead:
 * localStorage's ~5M-char per-origin quota made put() silently DROP a realistic
 * Nyquist/2-sweep .frc (the setItem QuotaExceededError is swallowed), which showed
 * up as "calibration rows all gone after reload" (#5). IndexedDB has no such
 * practical limit and stores strings / ArrayBuffers natively (no base64).
 *
 * Small + dependency-free by design (no bundler import beyond the Web Storage APIs).
 */

// One localStorage namespace so keys() can scan without walking unrelated app state.
const NS = 'phonalyser.filestore.';

/** base64-encode an ArrayBuffer / typed array (binary-safe, no data loss). */
function bytesToBase64(buf) {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf.buffer || buf);
  let bin = '';
  const CHUNK = 0x8000;   // avoid a call-stack overflow on large files (String.fromCharCode spread)
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(bin);
}

/** base64 → ArrayBuffer (the inverse of bytesToBase64). */
function base64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

/**
 * Stores `data` under `key` with its display `name`. A string is kept verbatim
 * (text-mode files: .frc / .dpd); an ArrayBuffer / typed array is base64-encoded
 * (binary-mode). No-op-safe if localStorage is unavailable (private mode / quota).
 */
export function put(key, name, data) {
  const binary = typeof data !== 'string';
  const rec = { name, binary, data: binary ? bytesToBase64(data) : data };
  try { localStorage.setItem(NS + key, JSON.stringify(rec)); } catch (_) { /* quota / unavailable */ }
}

/**
 * Returns `{ name, data }` for `key`, or null when absent / unparsable. `data` is
 * the original text (string) or an ArrayBuffer (binary), matching what was `put`.
 */
export function get(key) {
  let raw;
  try { raw = localStorage.getItem(NS + key); } catch (_) { return null; }
  if (raw == null) return null;
  try {
    const rec = JSON.parse(raw);
    return { name: rec.name, data: rec.binary ? base64ToBytes(rec.data) : rec.data };
  } catch (_) { return null; }
}

/** Removes the entry for `key` (idempotent). */
export function remove(key) {
  try { localStorage.removeItem(NS + key); } catch (_) { /* unavailable */ }
}

/** The stored keys beginning with `prefix` (with the internal namespace stripped). */
export function keys(prefix = '') {
  const out = [];
  let n = 0;
  try { n = localStorage.length; } catch (_) { return out; }
  for (let i = 0; i < n; i++) {
    let k;
    try { k = localStorage.key(i); } catch (_) { continue; }
    if (k && k.startsWith(NS)) {
      const bare = k.slice(NS.length);
      if (bare.startsWith(prefix)) out.push(bare);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// IndexedDB-backed variant for LARGE files (#5). One database, one object store;
// the store is its own namespace, so keys go in bare. Records are { name, data }
// with `data` a string or ArrayBuffer, stored via structured clone (loss-free,
// no base64). All four functions are async and reject on a real IDB failure —
// callers decide whether to surface or log it (unlike put()'s silent swallow,
// which is exactly what hid the localStorage quota loss).

const IDB_NAME = 'phonalyser.filestore';
const IDB_STORE = 'files';
let idbOpenPromise = null;

/** Lazily opens (and caches) the file-store database. */
function openIdb() {
  if (!idbOpenPromise) {
    idbOpenPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(IDB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(IDB_STORE);
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return idbOpenPromise;
}

/** Wraps one IDBRequest into a Promise. */
function idbRequest(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/** A fresh transaction's object store in the given mode. */
async function idbStore(mode) {
  const db = await openIdb();
  return db.transaction(IDB_STORE, mode).objectStore(IDB_STORE);
}

/** Stores `data` (string or ArrayBuffer / typed array) under `key` with its display `name`. */
export async function idbPut(key, name, data) {
  await idbRequest((await idbStore('readwrite')).put({ name, data }, key));
}

/** Returns `{ name, data }` for `key`, or null when absent. */
export async function idbGet(key) {
  const rec = await idbRequest((await idbStore('readonly')).get(key));
  return rec == null ? null : { name: rec.name, data: rec.data };
}

/** Removes the entry for `key` (idempotent). */
export async function idbRemove(key) {
  await idbRequest((await idbStore('readwrite')).delete(key));
}

/** The stored keys beginning with `prefix`. */
export async function idbKeys(prefix = '') {
  const all = await idbRequest((await idbStore('readonly')).getAllKeys());
  return all.filter((k) => typeof k === 'string' && k.startsWith(prefix));
}
