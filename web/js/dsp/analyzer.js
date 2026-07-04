/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Pure metric computation over a single-sided magnitude spectrum (dBFS).
// Stateless — the averaging/accumulation lives in the engine; this only reads
// out the figures of merit (fundamental level, THD, harmonic table, noise floor).

/** Peak linear amplitude within ±2 bins of `bin` (robust to a fraction-of-a-bin offset). */
export function peakAmp(magDb, bin) {
  let best = -300;
  for (let k = Math.max(1, bin - 2); k <= bin + 2 && k < magDb.length; k++) best = Math.max(best, magDb[k]);
  return Math.pow(10, best / 20);
}

const dB = (lin) => 20 * Math.log10(lin || 1e-12);

/**
 * @param {Float64Array} magDb  single-sided magnitude spectrum in dBFS (index = bin).
 * @param {number} fundBin      fundamental bin.
 * @param {number} binW         Hz per bin.
 * @param {number} maxHarm      highest harmonic to include (default 10).
 * @returns metrics object.
 */
export function computeMetrics(magDb, fundBin, binW, maxHarm = 10) {
  const fundA = peakAmp(magDb, fundBin);
  const harmonics = [];
  let harmPow = 0;
  for (let h = 2; h <= maxHarm; h++) {
    const b = fundBin * h;
    if (b > magDb.length - 1) break;
    const a = peakAmp(magDb, b);
    harmPow += a * a;
    harmonics.push({ h, hz: b * binW, db: dB(a) });
  }
  const thd = fundA > 0 ? Math.sqrt(harmPow) / fundA : 0;

  // Noise floor = median dBFS excluding DC, the fundamental and its harmonics (±3 bins).
  const excl = new Set();
  for (let h = 1; h <= maxHarm; h++) for (let d = -3; d <= 3; d++) excl.add(fundBin * h + d);
  const noise = [];
  for (let k = 4; k < magDb.length; k++) if (!excl.has(k)) noise.push(magDb[k]);
  noise.sort((a, b) => a - b);
  const floorDb = noise.length ? noise[noise.length >> 1] : -240;

  return {
    fundDbfs: dB(fundA),
    fundHz: fundBin * binW,
    thd, thdDb: dB(thd),
    harmonics,
    noiseFloorDb: floorDb,
    snrDb: dB(fundA) - floorDb,
  };
}
