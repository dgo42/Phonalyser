/*
 * Phonalyser web — shared calibration persistence (issue 2.3).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Content-addressed store for .frc calibration files, built on the IndexedDB
 * helpers in file-store.js.  Keys use the prefix 'cal.' + SHA-256 hex so both
 * the FFT pane and the FreqResp pane can share one record when they load the
 * same file.  Bytes are stored gzip-compressed (CompressionStream 'gzip',
 * available in all modern browsers and Node >= 18) to cap IDB space usage on
 * large Nyquist/2 sweeps.
 *
 * API:
 *   putCal(bytes, name)              -> Promise<string>         SHA-256 hex hash
 *   getCal(hash)                     -> Promise<{bytes,name}|null>
 *   pruneCals(referencedHashes)      -> Promise<void>           remove unreferenced keys
 */

import { idbPut, idbGet, idbRemove, idbKeys } from './file-store.js';

/** IDB key prefix for calibration records. */
export const CAL_PREFIX = 'cal.';

// ---------------------------------------------------------------------------
// internal helpers
// ---------------------------------------------------------------------------

/**
 * SHA-256 of `bytes` as a lowercase hex string.
 * @param {ArrayBuffer|Uint8Array} bytes
 * @returns {Promise<string>}
 */
async function sha256Hex(bytes) {
  const buf = bytes instanceof Uint8Array ? bytes.buffer : bytes;
  const digest = await crypto.subtle.digest('SHA-256', buf);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * Gzip-compress `bytes` via the Streams API.
 * @param {ArrayBuffer|Uint8Array} bytes
 * @returns {Promise<ArrayBuffer>}
 */
async function gzip(bytes) {
  const blob = new Blob([bytes]);
  const compressed = new Response(
    blob.stream().pipeThrough(new CompressionStream('gzip'))
  );
  return compressed.arrayBuffer();
}

/**
 * Gunzip `bytes` via the Streams API.
 * @param {ArrayBuffer|Uint8Array} bytes
 * @returns {Promise<ArrayBuffer>}
 */
async function gunzip(bytes) {
  const blob = new Blob([bytes]);
  const decompressed = new Response(
    blob.stream().pipeThrough(new DecompressionStream('gzip'))
  );
  return decompressed.arrayBuffer();
}

// ---------------------------------------------------------------------------
// public API
// ---------------------------------------------------------------------------

/**
 * Stores a calibration file in IDB under 'cal.'+hash, deduplicating by SHA-256.
 * The bytes are gzip-compressed before storage; if the hash is already present
 * the existing record is left untouched.
 *
 * @param {ArrayBuffer|Uint8Array} bytes  Original .frc bytes (before compression).
 * @param {string} name                   Display name (file basename).
 * @returns {Promise<string>}             Lowercase hex SHA-256 of the original bytes.
 */
export async function putCal(bytes, name) {
  const hash = await sha256Hex(bytes);
  const key = CAL_PREFIX + hash;
  if (await idbGet(key) != null) return hash;   // dedup: already present
  const compressed = await gzip(bytes);
  await idbPut(key, name, compressed);
  return hash;
}

/**
 * Retrieves a calibration file by hash.  Returns null when not found.
 *
 * @param {string} hash  Lowercase hex SHA-256 (as returned by putCal).
 * @returns {Promise<{bytes: ArrayBuffer, name: string}|null>}
 */
export async function getCal(hash) {
  const rec = await idbGet(CAL_PREFIX + hash);
  if (rec == null) return null;
  const bytes = await gunzip(rec.data);
  return { bytes, name: rec.name };
}

/**
 * Removes every 'cal.'+hash record whose hash is NOT in `referencedHashes`.
 * Call this after startup restore on the union of hashes referenced by BOTH panes.
 *
 * @param {Set<string>} referencedHashes  The hashes that must be kept.
 * @returns {Promise<void>}
 */
export async function pruneCals(referencedHashes) {
  const stored = await idbKeys(CAL_PREFIX);
  for (const key of stored) {
    const hash = key.slice(CAL_PREFIX.length);
    if (!referencedHashes.has(hash)) await idbRemove(key);
  }
}
