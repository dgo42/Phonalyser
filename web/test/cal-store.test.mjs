/*
 * Phonalyser web - cal-store round-trip + dedup contract test (issue 2.3).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/cal-store.test.mjs` (Node >= 18: crypto.subtle,
 * CompressionStream, Blob and Response are all globals). cal-store.js builds on
 * file-store.js's IndexedDB helpers, which Node has no native implementation of,
 * so this test installs a tiny in-memory IndexedDB shim on globalThis BEFORE
 * importing the module under test. The shim covers only the narrow surface
 * file-store.js uses (open + one object store, put/get/delete/getAllKeys).
 */

// --- crypto.subtle: global in Node >= 20, needs webcrypto wiring on 18 ---
if (!globalThis.crypto || !globalThis.crypto.subtle) {
  const { webcrypto } = await import('node:crypto');
  globalThis.crypto = webcrypto;
}

// --- minimal in-memory IndexedDB shim (one DB, one keyed object store) ---
function installIdbShim() {
  const stores = new Map();   // storeName -> Map<key, value>

  function req(resultFn) {
    const r = { onsuccess: null, onerror: null, result: undefined, error: null };
    queueMicrotask(() => {
      try { r.result = resultFn(); if (r.onsuccess) r.onsuccess(); }
      catch (e) { r.error = e; if (r.onerror) r.onerror(); }
    });
    return r;
  }

  function makeStore(name) {
    if (!stores.has(name)) stores.set(name, new Map());
    const map = stores.get(name);
    return {
      put: (value, key) => req(() => { map.set(key, value); return key; }),
      get: (key) => req(() => (map.has(key) ? map.get(key) : undefined)),
      delete: (key) => req(() => { map.delete(key); return undefined; }),
      getAllKeys: () => req(() => [...map.keys()]),
    };
  }

  const db = {
    createObjectStore: (name) => makeStore(name),
    transaction: (name) => ({ objectStore: () => makeStore(name) }),
  };

  globalThis.indexedDB = {
    open: () => {
      const r = { onupgradeneeded: null, onsuccess: null, onerror: null, result: db, error: null };
      queueMicrotask(() => {
        if (r.onupgradeneeded) r.onupgradeneeded();
        if (r.onsuccess) r.onsuccess();
      });
      return r;
    },
  };
}
installIdbShim();

const { putCal, getCal, pruneCals } = await import('../js/io/cal-store.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const enc = new TextEncoder();
const dec = new TextDecoder();

// --- round-trip: putCal -> getCal returns the ORIGINAL bytes + name ---
const frcA = enc.encode('# .frc calibration A\n20 0.0 0.0\n20000 -1.5 3.2\n');
const frcB = enc.encode('# a DIFFERENT calibration B\n50 -0.1 0.0\n');

const hashA = await putCal(frcA, 'calA.frc');
const hashB = await putCal(frcB, 'calB.frc');

ok(typeof hashA === 'string' && /^[0-9a-f]{64}$/.test(hashA), 'putCal returns a 64-hex SHA-256');
ok(hashA !== hashB, 'different bytes produce different hashes');

const gotA = await getCal(hashA);
ok(gotA != null, 'getCal(hashA) found');
ok(gotA.name === 'calA.frc', 'getCal returns the stored name');
ok(dec.decode(gotA.bytes) === dec.decode(frcA), 'getCal round-trips the original bytes');

const gotB = await getCal(hashB);
ok(gotB != null && dec.decode(gotB.bytes) === dec.decode(frcB), 'getCal(hashB) round-trips too');

// --- getCal on an unknown hash -> null ---
const missing = await getCal('0'.repeat(64));
ok(missing === null, 'getCal on an absent hash returns null');

// --- dedup: same bytes (even via a different name) hash-collapse to one record ---
const hashA2 = await putCal(frcA, 'calA-renamed.frc');
ok(hashA2 === hashA, 'dedup: identical bytes yield the same hash');
// The dedup path leaves the EXISTING record untouched (original name kept).
const gotA2 = await getCal(hashA);
ok(gotA2.name === 'calA.frc', 'dedup keeps the first-stored name (no overwrite)');

// A Uint8Array view and its ArrayBuffer of the same content hash identically.
const hashAbuf = await putCal(frcA.buffer, 'calA.frc');
ok(hashAbuf === hashA, 'ArrayBuffer and Uint8Array of the same bytes share a hash');

// --- pruneCals: drops unreferenced, keeps referenced ---
await pruneCals(new Set([hashA]));   // keep A, drop B
ok((await getCal(hashA)) != null, 'pruneCals keeps a referenced hash');
ok((await getCal(hashB)) === null, 'pruneCals removes an unreferenced hash');

console.log(`cal-store.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
