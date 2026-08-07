/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.SignalMeasurements, plus the
// reconstructed |F1-F2| beat envelope from org.edgo.audio.measure.gui.scope.ScopeView
// (reconstructBeatSignal) - DSP only, no canvas drawing.
//
// compute() does a fast pass for min/max/mean/RMS and rising-edge crossings of
// the half-amplitude threshold, derives a coarse period, then refines the
// frequency with multi-stage Goertzel single-bin DFTs (with a Hann-windowed
// leakage-debiasing final pass). period/frequency/dutyCycle are NaN when the
// buffer holds less than one full cycle.

/**
 * @typedef {Object} SignalMeasurements
 * @property {number} vpp        volts peak-to-peak
 * @property {number} vrms       volts RMS (AC component only)
 * @property {number} vmean      DC offset in volts
 * @property {number} period     seconds (NaN if unknown)
 * @property {number} riseTime   seconds, 10% -> 90% on rising edges (NaN if unknown)
 * @property {number} fallTime   seconds, 90% -> 10% on falling edges (NaN if unknown)
 * @property {number} frequency  Hz (NaN if unknown)
 * @property {number} dutyCycle  fraction [0, 1] (NaN if unknown)
 * @property {number} dualF1     Hz, dual-tone tone 1 as captured (NaN if not measured)
 * @property {number} dualF2     Hz, dual-tone tone 2 as captured (NaN if not measured)
 */

function make(vpp, vrms, vmean, period, riseTime, fallTime, frequency, dutyCycle,
              dualF1 = NaN, dualF2 = NaN) {
  return { vpp, vrms, vmean, period, riseTime, fallTime, frequency, dutyCycle, dualF1, dualF2 };
}

/**
 * Goertzel single-bin DFT magnitude at `freq`. Subtracts the DC mean so the
 * result reflects only the AC component at that frequency.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n
 * @param {number} sampleRate
 * @param {number} freq
 * @param {number} mean
 * @returns {number}
 */
function goertzelMagnitude(data, n, sampleRate, freq, mean) {
  const omega = 2 * Math.PI * freq / sampleRate;
  const coeff = 2 * Math.cos(omega);
  let q1 = 0, q2 = 0;
  for (let i = 0; i < n; i++) {
    const q0 = (data[i] - mean) + coeff * q1 - q2;
    q2 = q1;
    q1 = q0;
  }
  return Math.sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff);
}

/**
 * Three-stage Goertzel refinement centred on `estimate`: each scan shrinks the
 * step by ~20× from the previous, then a final parabolic interpolation across
 * three adjacent fine bins gives sub-step precision.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n
 * @param {number} sampleRate
 * @param {number} estimate
 * @param {number} mean
 * @param {number} halfWidth
 * @returns {number}
 */
function refineAroundEstimate(data, n, sampleRate, estimate, mean, halfWidth) {
  const stage1Probes = 100;
  const stage1Step = halfWidth * 2.0 / stage1Probes;
  let bestFreq = estimate;
  let bestMag = goertzelMagnitude(data, n, sampleRate, estimate, mean);
  for (let k = -stage1Probes / 2; k <= stage1Probes / 2; k++) {
    if (k === 0) continue;
    const f = estimate + k * stage1Step;
    if (f <= 0 || f >= sampleRate / 2.0) continue;
    const mag = goertzelMagnitude(data, n, sampleRate, f, mean);
    if (mag > bestMag) { bestMag = mag; bestFreq = f; }
  }

  const stage2Step = stage1Step / 20.0;
  const stage2Probes = 40;
  for (let k = -stage2Probes / 2; k <= stage2Probes / 2; k++) {
    if (k === 0) continue;
    const f = bestFreq + k * stage2Step;
    if (f <= 0 || f >= sampleRate / 2.0) continue;
    const mag = goertzelMagnitude(data, n, sampleRate, f, mean);
    if (mag > bestMag) { bestMag = mag; bestFreq = f; }
  }

  const stage3Step = stage2Step / 20.0;
  const stage3Probes = 40;
  for (let k = -stage3Probes / 2; k <= stage3Probes / 2; k++) {
    if (k === 0) continue;
    const f = bestFreq + k * stage3Step;
    if (f <= 0 || f >= sampleRate / 2.0) continue;
    const mag = goertzelMagnitude(data, n, sampleRate, f, mean);
    if (mag > bestMag) { bestMag = mag; bestFreq = f; }
  }

  // Parabolic interpolation across three adjacent stage-3 bins. Skipped when the
  // bracket lies outside the band or the three samples don't form a
  // downward-opening parabola.
  const leftF = bestFreq - stage3Step;
  const rightF = bestFreq + stage3Step;
  if (leftF > 0 && rightF < sampleRate / 2.0) {
    const leftMag = goertzelMagnitude(data, n, sampleRate, leftF, mean);
    const rightMag = goertzelMagnitude(data, n, sampleRate, rightF, mean);
    const denom = leftMag - 2.0 * bestMag + rightMag;
    if (denom < 0) {
      const delta = 0.5 * (leftMag - rightMag) / denom;
      if (delta > -1.0 && delta < 1.0) {
        return bestFreq + delta * stage3Step;
      }
    }
  }
  return bestFreq;
}

/**
 * Broad-band Goertzel scan over the full audio band as a fallback for the
 * crossing-based primary path: a coarse pass over a 0.1 s sub-window sweeps the
 * band at its natural FFT-bin resolution; the coarse peak is then refined.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n
 * @param {number} sampleRate
 * @param {number} mean
 * @returns {number} refined frequency, or -1.0 if none
 */
function scanForFundamental(data, n, sampleRate, mean) {
  let coarseN = Math.min(n, Math.trunc(sampleRate * 0.1));   // 0.1 s sub-window
  if (coarseN < 64) coarseN = n;
  const coarseBinHz = sampleRate / coarseN;                  // = 10 Hz at 48 kHz
  const maxScanFreq = sampleRate / 4.0;
  const minScanFreq = Math.max(5.0, coarseBinHz);

  let bestFreq = -1, bestMag = -1;
  for (let f = minScanFreq; f < maxScanFreq; f += coarseBinHz) {
    const mag = goertzelMagnitude(data, coarseN, sampleRate, f, mean);
    if (mag > bestMag) { bestMag = mag; bestFreq = f; }
  }
  if (bestFreq <= 0) return -1.0;

  return refineAroundEstimate(data, n, sampleRate, bestFreq, mean, coarseBinHz * 2.0);
}

/**
 * Returns a Hann-windowed, DC-removed copy of the first `n` samples. Used for
 * the final leakage-debiased frequency refinement.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n
 * @param {number} mean
 * @returns {Float64Array}
 */
function hannWindowed(data, n, mean) {
  const w = new Float64Array(n);
  const scale = 2 * Math.PI / (n - 1);
  for (let i = 0; i < n; i++) {
    const h = 0.5 - 0.5 * Math.cos(scale * i);
    w[i] = (data[i] - mean) * h;
  }
  return w;
}

/**
 * Computes oscilloscope-style measurements over the first `n` samples of `data`
 * (normalised samples in [-1, +1]). `peakVolts` converts ±1.0 into the
 * full-scale ADC voltage swing - i.e. adcFsVoltageRms · √2.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n
 * @param {number} sampleRate
 * @param {number} peakVolts
 * @returns {SignalMeasurements}
 */
export function compute(data, n, sampleRate, peakVolts, broadband = true) {
  if (n < 4) {
    return make(0, 0, 0, NaN, NaN, NaN, NaN, NaN);
  }
  let sum = 0;
  let sumSq = 0;
  let min = data[0];
  let max = data[0];
  for (let i = 0; i < n; i++) {
    const v = data[i];
    sum += v;
    sumSq += v * v;
    if (v < min) min = v;
    if (v > max) max = v;
  }
  let mean = sum / n;
  // AC RMS (= signal RMS after removing the DC bias). variance = E[X²] − E[X]².
  let variance = sumSq / n - mean * mean;
  let rms = Math.sqrt(Math.max(0.0, variance));
  const vpp = (max - min) * peakVolts;
  let vmean = mean * peakVolts;
  let vrms = rms * peakVolts;

  // Half-amplitude midpoint - crossing threshold for both period detection and
  // duty cycle. Independent of any DC bias on the input.
  // (Java stores threshold as float; clamp to Math.fround for bit-faithful
  // comparisons against the float samples.)
  const threshold = Math.fround((min + max) * 0.5);
  let firstCross = -1;
  let lastCross = -1;
  let crossCount = 0;
  let highCount = 0;
  for (let i = 1; i < n; i++) {
    if (data[i] >= threshold) highCount++;
    if (data[i - 1] < threshold && data[i] >= threshold) {
      if (firstCross < 0) firstCross = i;
      lastCross = i;
      crossCount++;
    }
  }

  // Vmean / Vrms over an INTEGER number of periods. The fixed-length window
  // holds a fractional cycle count, and that fraction adds an amplitude-
  // proportional, capture-phase-random residual to the mean (up to A/(π·cycles)
  // - ~mV at full scale over 0.25 s) that swamps the noise floor in the Vmean
  // statistics. The rising mid-threshold crossings bound whole periods, and the
  // signal sits AT its mean there, so the whole-sample boundary error is
  // second-order (sub-µV) - Vmean then moves only with the noise floor. No
  // crossings (DC / noise-only) -> the full-window figures above stand.
  if (crossCount >= 2) {
    const pn = lastCross - firstCross;
    let pSum = 0;
    let pSumSq = 0;
    for (let i = firstCross; i < lastCross; i++) {
      const v = data[i];
      pSum += v;
      pSumSq += v * v;
    }
    mean = pSum / pn;
    variance = pSumSq / pn - mean * mean;
    rms = Math.sqrt(Math.max(0.0, variance));
    vmean = mean * peakVolts;
    vrms = rms * peakVolts;
  }

  // Rise/fall time: average over all complete 10%↔90% transitions, bracket-based
  // with linear interpolation for sub-sample resolution.
  let riseTime = NaN;
  let fallTime = NaN;
  if (max - min > 1e-6) {
    const lo = Math.fround(min + 0.1 * (max - min));
    const hi = Math.fround(min + 0.9 * (max - min));
    let pendingLo = -1;
    let pendingHi = -1;
    let riseSum = 0, fallSum = 0;
    let riseCount = 0, fallCount = 0;
    for (let i = 1; i < n; i++) {
      const prev = data[i - 1];
      const v = data[i];
      if (prev < lo && v >= lo) {
        pendingLo = (i - 1) + (lo - prev) / (v - prev);
      }
      if (prev < hi && v >= hi && pendingLo >= 0) {
        const hiT = (i - 1) + (hi - prev) / (v - prev);
        riseSum += (hiT - pendingLo);
        riseCount++;
        pendingLo = -1;
      }
      if (prev > hi && v <= hi) {
        pendingHi = (i - 1) + (prev - hi) / (prev - v);
      }
      if (prev > lo && v <= lo && pendingHi >= 0) {
        const loT = (i - 1) + (prev - lo) / (prev - v);
        fallSum += (loT - pendingHi);
        fallCount++;
        pendingHi = -1;
      }
    }
    if (riseCount > 0) riseTime = (riseSum / riseCount) / sampleRate;
    if (fallCount > 0) fallTime = (fallSum / fallCount) / sampleRate;
  }

  let period = NaN;
  let frequency = NaN;
  let duty = NaN;
  if (crossCount >= 2) {
    // Primary: period from rising-edge spacing of the half-amplitude threshold,
    // then Goertzel refinement around that estimate.
    const coarsePeriod = (lastCross - firstCross) / (crossCount - 1);
    const coarseFreq = sampleRate / coarsePeriod;
    const refinedA = refineAroundEstimate(data, n, sampleRate, coarseFreq, mean,
                                          coarseFreq * 0.25);
    const magA = goertzelMagnitude(data, n, sampleRate, refinedA, mean);
    const rmsAc = Math.sqrt(Math.max(0, variance));
    const qA = (rmsAc > 1e-12) ? magA / n / rmsAc : 0;

    let bestFreq = refinedA;
    let bestQ = qA;
    // Fallback broad-band scan, used only when crossings look unreliable. It is the
    // costly per-bin Goertzel sweep; when `broadband` is false (the live measurement
    // loop) it is SKIPPED so a weak/noisy signal leaves frequency NaN cheaply and the
    // sweep runs off-thread instead (osc-freq-worker), matching Java ScopeMeasurementWorker.
    if (broadband && qA < 0.1) {
      const refinedB = scanForFundamental(data, n, sampleRate, mean);
      if (refinedB > 0) {
        const magB = goertzelMagnitude(data, n, sampleRate, refinedB, mean);
        const qB = (rmsAc > 1e-12) ? magB / n / rmsAc : 0;
        if (qB > qA) {
          bestFreq = refinedB;
          bestQ = qB;
        }
      }
    }

    // Quality gate: pure sine ratio ≈ 1/√2 ≈ 0.707; threshold of 0.1 admits
    // rectangles down to ~1% / up to ~99% duty.
    if (bestQ >= 0.1 && bestFreq > 0) {
      // Leakage-debiased final estimate on a Hann-windowed copy (suppresses the
      // negative-frequency image whose leakage biases the rectangular-window
      // Goertzel peak on short buffers).
      const windowed = hannWindowed(data, n, mean);
      bestFreq = refineAroundEstimate(windowed, n, sampleRate, bestFreq, 0.0,
                                      Math.max(5.0, bestFreq * 0.01));
      frequency = bestFreq;
      period = 1.0 / bestFreq;
      duty = highCount / n;
    }
  }
  return make(vpp, vrms, vmean, period, riseTime, fallTime, frequency, duty);
}

/**
 * Returns a copy of `m` with every time-domain field set to NaN (period, rise
 * time, fall time, frequency, duty cycle). Used for dual-tone signals, which
 * have no meaningful single-value period/frequency/duty. Vpp/Vrms/Vmean stay
 * valid since they're meaningful in every signal mode.
 * @param {SignalMeasurements} m
 * @returns {SignalMeasurements}
 */
export function withoutTimes(m) {
  return make(m.vpp, m.vrms, m.vmean, NaN, NaN, NaN, NaN, NaN, m.dualF1, m.dualF2);
}

/**
 * Returns a copy of `m` with `frequency` (and the matching `period`) replaced,
 * keeping every other field. Used to swap in a comb-bias-free frequency
 * re-measured on the raw signal.
 * @param {SignalMeasurements} m
 * @param {number} freq
 * @returns {SignalMeasurements}
 */
export function withFrequency(m, freq) {
  return make(m.vpp, m.vrms, m.vmean,
              (freq > 0 ? 1.0 / freq : NaN), m.riseTime, m.fallTime, freq, m.dutyCycle,
              m.dualF1, m.dualF2);
}

/**
 * Returns a copy of `m` with the two dual-tone frequencies `f1` / `f2` replaced,
 * keeping every other field. The scope worker uses this to swap in the two tones
 * as re-measured on the raw (as-captured) signal, so the residual fit can
 * subtract them at their true ADC-domain frequencies rather than the generator's
 * commanded (DAC-domain, clock-offset) values. Faithful port of
 * SignalMeasurements.withDualTones.
 * @param {SignalMeasurements} m
 * @param {number} f1
 * @param {number} f2
 * @returns {SignalMeasurements}
 */
export function withDualTones(m, f1, f2) {
  return make(m.vpp, m.vrms, m.vmean,
              m.period, m.riseTime, m.fallTime, m.frequency, m.dutyCycle,
              f1, f2);
}

/**
 * Returns a copy of `m` with Vmean / Vrms recomputed over an INTEGER number of
 * whole |F1−F2| BEAT periods of `data`, for the dual-tone case.
 *
 * Why: a dual tone sin(F1·t)+sin(F2·t) = 2·sin((F1+F2)/2·t)·cos((F1−F2)/2·t) has
 * a slow |F1−F2| beat envelope on top of the carrier. The plain compute() bounds
 * its Vmean window by the CARRIER's half-amplitude rising crossings - but those
 * crossings do NOT fall on whole beat periods, so the window holds a fractional
 * beat cycle whose amplitude-proportional residual jitters the mean by tens of µV
 * tick-to-tick (the ±50 µV jump). The beat is the slowest structure in the signal;
 * bounding the integration to whole beat periods drops that residual to the noise
 * floor (Vmean avg < 1 µV) - the beat-envelope analogue of compute()'s whole-
 * carrier-period bounding. Vpp is untouched (a peak, not an integral).
 *
 * The window is the largest integer multiple of the beat period `sampleRate/beatHz`
 * that fits in `n`. Falls back to `m` unchanged when the beat is non-finite or when
 * less than one whole beat period fits the buffer (nothing better to integrate over).
 *
 * @param {SignalMeasurements} m   the already-computed measurement (carries dualF1/F2)
 * @param {Float32Array|Float64Array|number[]} data  the measured window samples
 * @param {number} n           valid length of `data`
 * @param {number} sampleRate
 * @param {number} beatHz      |F1 − F2|, the beat frequency (Hz)
 * @param {number} peakVolts   ±1.0 -> full-scale volts
 * @returns {SignalMeasurements}
 */
export function withBeatPeriodMeanRms(m, data, n, sampleRate, beatHz, peakVolts) {
  if (!(beatHz > 0) || !(sampleRate > 0) || n < 4) return m;
  const beatSamples = sampleRate / beatHz;
  if (!(beatSamples >= 2)) return m;
  // Largest whole number of beat periods that fits the window.
  const periods = Math.floor(n / beatSamples);
  if (periods < 1) return m;
  // Round the fractional whole-beat span to the nearest sample boundary. The
  // sub-sample truncation error is second-order in the residual (the signal is
  // NOT at a fixed value at a beat boundary, unlike a carrier zero-crossing, but
  // over `periods` whole beats the boundary error averages down as 1/periods).
  const win = Math.min(n, Math.round(periods * beatSamples));
  if (win < 2) return m;
  let sum = 0, sumSq = 0;
  for (let i = 0; i < win; i++) {
    const v = data[i];
    sum += v;
    sumSq += v * v;
  }
  const mean = sum / win;
  const variance = sumSq / win - mean * mean;
  const rms = Math.sqrt(Math.max(0.0, variance));
  return make(m.vpp, rms * peakVolts, mean * peakVolts,
              m.period, m.riseTime, m.fallTime, m.frequency, m.dutyCycle,
              m.dualF1, m.dualF2);
}

/**
 * Re-pins an already-located fundamental `seedHz` to a precise frequency by a
 * Hann-windowed Goertzel peak search over the narrow band
 * [seedHz − halfHz, seedHz + halfHz] of `data`. Used by the comb-seed ->
 * raw-refine scope frequency path: the mains comb locates WHICH peak is the
 * fundamental (a good seed), but its notches bias the frequency; the raw signal
 * is un-biased and carries no competing component inside this narrow band.
 * Returns NaN for a non-finite seed.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} n
 * @param {number} sampleRate
 * @param {number} seedHz
 * @param {number} halfHz
 * @returns {number}
 */
export function refineFrequencyAround(data, n, sampleRate, seedHz, halfHz) {
  if (!(seedHz > 0) || n < 4) return NaN;
  let sum = 0;
  for (let i = 0; i < n; i++) sum += data[i];
  const win = hannWindowed(data, n, sum / n);
  return refineAroundEstimate(win, n, sampleRate, seedHz, 0.0, halfHz);
}

/**
 * Reconstructs the signed beat modulator of a dual-tone signal - the slow
 * cos((F1-F2)/2·t) factor that envelopes the carrier in
 * sin(F1·t) + sin(F2·t) = 2·sin((F1+F2)/2·t)·cos((F1-F2)/2·t).
 *
 * Faithful port of ScopeView.reconstructBeatSignal. Three steps: (1) rectify +
 * cascaded boxcar LP to extract the abs envelope; (2) raw-signal peak for
 * amplitude scaling; (3) synchronous I/Q detection of the (F1-F2) component in
 * the abs envelope to recover the modulator phase, then synthesise a clean
 * cos((F1-F2)/2·t − φ_m) at peak `rawPeak`.
 *
 * The Java version reuses grow-only scratch arrays across paints; here `scratch`
 * may be passed to avoid per-call allocation, otherwise fresh buffers are made.
 * Returns a length-`available` Float32Array (zeros on early return).
 *
 * @param {Float32Array|Float64Array|number[]} data sample buffer
 * @param {number} available  valid length of `data`
 * @param {number} sampleRate
 * @param {number} f1Hz
 * @param {number} f2Hz
 * @param {{beatOut?:Float32Array, beatTmp?:Float32Array, beatAbsLp?:Float32Array}} [scratch]
 *        optional reusable grow-only scratch (mutated in place)
 * @returns {Float32Array} the reconstructed signed modulator (length `available`)
 */
export function reconstructBeatSignal(data, available, sampleRate, f1Hz, f2Hz, scratch) {
  const sc = scratch || {};
  if (!sc.beatOut || sc.beatOut.length < available) {
    sc.beatOut = new Float32Array(available);
    sc.beatTmp = new Float32Array(available);
    sc.beatAbsLp = new Float32Array(available);
  }
  const out = sc.beatOut;
  out.fill(0, 0, available);   // early returns must yield silence
  const beatHz = Math.abs(f2Hz - f1Hz);
  if (!(beatHz > 0) || sampleRate <= 0) return out;
  // L = quarter-beat-period samples - bracketed so a tiny beat doesn't blow past
  // the buffer length and a huge beat doesn't collapse to L = 1.
  const lFromBeat = Math.round(sampleRate / (4.0 * beatHz));
  const lMin = Math.max(2, Math.round(sampleRate / Math.max(1.0, f1Hz + f2Hz)));
  const lMaxFromBuf = Math.max(2, Math.trunc(available / 4));
  const L = Math.max(lMin, Math.min(lFromBeat, lMaxFromBuf));
  if (available <= 2 * L) return out;
  const halfL = Math.trunc(L / 2);

  // --- Step 1: rectify + boxcar LP applied TWICE in cascade -> sinc² abs
  // envelope. Each pass emits at its window centre (zero net group delay).
  // Boundaries filled with the nearest valid value.
  const tmp = sc.beatTmp;
  let sum = 0.0;
  for (let i = 0; i < L; i++) sum += Math.abs(data[i]);
  for (let i = L; i < available; i++) {
    tmp[i - halfL] = sum / L;
    sum += Math.abs(data[i]) - Math.abs(data[i - L]);
  }
  const tmpFirst = tmp[halfL];
  const tmpLast = tmp[available - halfL - 1];
  for (let i = 0; i < halfL; i++) tmp[i] = tmpFirst;
  for (let i = available - halfL; i < available; i++) tmp[i] = tmpLast;

  const absLp = sc.beatAbsLp;
  sum = 0.0;
  for (let i = 0; i < L; i++) sum += tmp[i];
  for (let i = L; i < available; i++) {
    absLp[i - halfL] = sum / L;
    sum += tmp[i] - tmp[i - L];
  }
  const firstValid = absLp[halfL];
  const lastValid = absLp[available - halfL - 1];
  for (let i = 0; i < halfL; i++) absLp[i] = firstValid;
  for (let i = available - halfL; i < available; i++) absLp[i] = lastValid;

  // --- Step 2: raw-signal peak for amplitude scaling (≈ |F1| + |F2|).
  let rawPeak = 0;
  for (let i = halfL; i < available - halfL; i++) {
    const a = Math.abs(data[i]);
    if (a > rawPeak) rawPeak = a;
  }
  if (!(rawPeak > 0)) return out;

  // --- Step 3: synchronous detection of the (F1-F2) component in absLp.
  // |cos((F1-F2)/2·t − φ_m)| has first AC harmonic at (F1-F2) with phase 2·φ_m;
  // atan2(Q, I) = 2·φ_m, so φ_m = phase / 2.
  let dc = 0.0;
  let validN = 0;
  for (let i = halfL; i < available - halfL; i++) {
    dc += absLp[i];
    validN++;
  }
  if (validN <= 0) return out;
  dc /= validN;
  const omega = 2.0 * Math.PI * beatHz / sampleRate;
  let iSum = 0.0;
  let qSum = 0.0;
  // Phase-recurrence oscillator instead of per-sample Math.cos/Math.sin.
  const rotC = Math.cos(omega), rotS = Math.sin(omega);
  let c = Math.cos(omega * halfL), s = Math.sin(omega * halfL);
  for (let i = halfL; i < available - halfL; i++) {
    const ac = absLp[i] - dc;
    iSum += ac * c;
    qSum += ac * s;
    const cNext = c * rotC - s * rotS;
    s = s * rotC + c * rotS;
    c = cNext;
  }
  const phase = Math.atan2(qSum, iSum);
  const omegaMod = omega / 2.0;
  const phaseMod = phase / 2.0;
  const rotMc = Math.cos(omegaMod), rotMs = Math.sin(omegaMod);
  let mc = Math.cos(-phaseMod), ms = Math.sin(-phaseMod);
  for (let i = 0; i < available; i++) {
    out[i] = rawPeak * mc;
    const mcNext = mc * rotMc - ms * rotMs;
    ms = ms * rotMc + mc * rotMs;
    mc = mcNext;
  }
  return out;
}

// ---------------------------------------------------------------------------
// Measurement-table statistics + number formatting.
//
// Faithful ports of:
//   org.edgo.audio.measure.gui.scope.MeasurementStats  (Welford avg/min/max/σ)
//   org.edgo.audio.measure.gui.scope.MeasurementRow    (unit-prefix formatters)
//
// The Java MeasurementStats is a pure online accumulator. ScopeView keeps the
// rolling window itself (it walks measWorker.walkRecentHistory(cutoff) over
// oscMeasurementAverageSeconds and feeds a fresh MeasurementStats each rebuild).
// Here we fold that rolling window into the same class: push(tNs, value) appends
// a timestamped sample, rebuild(windowSeconds) replays the entries newer than
// now-window through the Welford accumulator and returns {mean,min,max,sigma}.
// ---------------------------------------------------------------------------

const NS_PER_SECOND = 1e9;

// Hard ceiling on the rolling history so push() can NEVER grow without bound,
// independent of how often rebuild() (which prunes to the live window) runs and
// independent of oscMeasurementAverageSeconds (which can be 0 = "keep all" -> no
// age cutoff). The averaging window is at most a handful of seconds; even at the
// ~60 fps free-running paint rate (file mode pushes every frame) a 600 s / 64 k
// guard is orders of magnitude beyond any real window while still bounding RAM.
const HISTORY_MAX_AGE_NS = 600 * NS_PER_SECOND;
const HISTORY_MAX_ENTRIES = 1 << 16;

/**
 * Online avg / min / max / variance accumulator (Welford's method) used by the
 * oscilloscope measurement table, plus a rolling timestamped history so a window
 * of recent samples can be re-accumulated on demand. NaN inputs are skipped so
 * missing-cycle samples don't poison the window stats.
 *
 * Faithful port of org.edgo.audio.measure.gui.scope.MeasurementStats; the
 * push/rebuild/clear rolling-window API mirrors ScopeView.walkRecentHistory.
 */
export class MeasurementStats {
  constructor() {
    this._count = 0;
    this._mean = 0;
    this._m2 = 0;
    this._min = Number.POSITIVE_INFINITY;
    this._max = Number.NEGATIVE_INFINITY;
    // Rolling history: parallel arrays of nanosecond timestamps and values.
    this._tNs = [];
    this._vals = [];
  }

  /** Welford add of a single sample; NaN is skipped (matches Java). */
  add(v) {
    if (Number.isNaN(v)) return;
    this._count++;
    const delta = v - this._mean;
    this._mean += delta / this._count;
    this._m2 += delta * (v - this._mean);
    if (v < this._min) this._min = v;
    if (v > this._max) this._max = v;
  }

  getMean() { return this._count > 0 ? this._mean : NaN; }
  getMin() { return this._count > 0 ? this._min : NaN; }
  getMax() { return this._count > 0 ? this._max : NaN; }
  getSigma() { return this._count > 1 ? Math.sqrt(this._m2 / (this._count - 1)) : NaN; }

  /** Reset the Welford accumulator (not the history) to the empty state. */
  _resetAccumulator() {
    this._count = 0;
    this._mean = 0;
    this._m2 = 0;
    this._min = Number.POSITIVE_INFINITY;
    this._max = Number.NEGATIVE_INFINITY;
  }

  /** Append a timestamped sample to the rolling history, trimming entries that
   *  have aged past the hard ceiling ON PUSH so the parallel arrays stay bounded
   *  even when rebuild() (the live-window prune) runs less often than push() or
   *  the configured window is 0 ("keep all"). The live-window prune in rebuild()
   *  still does the precise oscMeasurementAverageSeconds trim; this is only the
   *  safety bound against unbounded growth. */
  push(tNs, value) {
    this._tNs.push(tNs);
    this._vals.push(value);
    const ts = this._tNs;
    let start = 0;
    const n = ts.length;
    const cutoff = tNs - HISTORY_MAX_AGE_NS;
    while (start < n && ts[start] < cutoff) start++;
    if (n - start > HISTORY_MAX_ENTRIES) start = n - HISTORY_MAX_ENTRIES;
    if (start > 0) { this._tNs = ts.slice(start); this._vals = this._vals.slice(start); }
  }

  /**
   * Re-accumulate the history entries newer than now-window through a fresh
   * Welford pass and return the stats. Stale entries (older than the cutoff)
   * are pruned. `windowSeconds` <= 0 means "use everything still stored".
   * @param {number} windowSeconds
   * @returns {{mean:number, min:number, max:number, sigma:number}}
   */
  rebuild(windowSeconds) {
    const ts = this._tNs;
    const n = ts.length;
    let cutoff = Number.NEGATIVE_INFINITY;
    if (windowSeconds > 0 && n > 0) {
      cutoff = ts[n - 1] - windowSeconds * NS_PER_SECOND;
    }
    // Find the first index still within the window - WITHOUT dropping the older
    // entries (Java walkRecentHistory walks its ring non-destructively): widening
    // the averaging pref back must recover the still-stored history. The hard
    // age/size bound in push() alone trims storage.
    let start = 0;
    while (start < n && ts[start] < cutoff) start++;
    this._resetAccumulator();
    const vals = this._vals;
    for (let i = start; i < vals.length; i++) this.add(vals[i]);
    return { mean: this.getMean(), min: this.getMin(), max: this.getMax(), sigma: this.getSigma() };
  }

  /** Drop all history and reset the accumulator. */
  clear() {
    this._tNs.length = 0;
    this._vals.length = 0;
    this._resetAccumulator();
  }
}

// --- Number formatters (port of MeasurementRow.fmt / fmtFreq). ---------------

/**
 * Generic magnitude-based decimal formatter (port of MeasurementRow.fmt):
 * NaN -> "---", 0 -> "0.000", >=1000 -> 1 decimal, >=100 -> 2 decimals,
 * otherwise 3 decimals. Java uses String.format (HALF_UP); toFixed is HALF_EVEN
 * but the difference is sub-LSB and irrelevant for these readouts.
 */
function fmt(v) {
  if (Number.isNaN(v)) return '---';
  const a = Math.abs(v);
  if (a === 0) return '0.000';
  if (a >= 1000) return v.toFixed(1);
  if (a >= 100) return v.toFixed(2);
  return v.toFixed(3);
}

/** Largest |magnitude| across cur + the window stats; drives the unit prefix. */
function peakMag(cur, stats) {
  let a = Number.isNaN(cur) ? 0 : Math.abs(cur);
  if (!Number.isNaN(stats.mean)) a = Math.max(a, Math.abs(stats.mean));
  if (!Number.isNaN(stats.max)) a = Math.max(a, Math.abs(stats.max));
  if (!Number.isNaN(stats.min)) a = Math.max(a, Math.abs(stats.min));
  return a;
}

/** Pick {unit, scale} for a volts magnitude (V / mV / μV), per MeasurementRow. */
function voltsPrefix(m) {
  if (m >= 1) return { unit: 'V', scale: 1 };
  if (m >= 1e-3) return { unit: 'mV', scale: 1e3 };
  if (m >= 1e-6) return { unit: 'μV', scale: 1e6 };
  return { unit: 'V', scale: 1 };
}

/** Pick {unit, scale} for a time magnitude (s / ms / μs), per MeasurementRow. */
function timePrefix(m) {
  if (m >= 1) return { unit: 's', scale: 1 };
  if (m >= 1e-3) return { unit: 'ms', scale: 1e3 };
  if (m >= 1e-6) return { unit: 'μs', scale: 1e6 };
  return { unit: 's', scale: 1 };
}

/**
 * Format a single voltage value, auto-scaling the unit prefix off its own
 * magnitude. Returns "value unit" (e.g. "1.234 mV"). NaN -> "---".
 * @param {number} v volts
 * @returns {string}
 */
export function fmtVolts(v) {
  if (Number.isNaN(v)) return '---';
  const p = voltsPrefix(Math.abs(v));
  return fmt(v * p.scale) + ' ' + p.unit;
}

/**
 * Format a single time value, auto-scaling the unit prefix off its own
 * magnitude. Returns "value unit" (e.g. "2.500 ms"). NaN -> "---".
 * @param {number} s seconds
 * @returns {string}
 */
export function fmtTime(s) {
  if (Number.isNaN(s)) return '---';
  const p = timePrefix(Math.abs(s));
  return fmt(s * p.scale) + ' ' + p.unit;
}

/**
 * Frequency formatter (port of MeasurementRow.fmtFreq): NaN -> "---",
 * >=1 MHz -> 0 decimals, >=100 kHz -> 1 decimal, otherwise 2 decimals.
 * @param {number} v Hz
 * @returns {string}
 */
export function fmtFreq(v) {
  if (Number.isNaN(v)) return '---';
  const a = Math.abs(v);
  if (a >= 1_000_000) return v.toFixed(0);
  if (a >= 100_000) return v.toFixed(1);
  return v.toFixed(2);
}

/**
 * Percentage formatter (port of MeasurementRow.forPct): scales by 100 and
 * appends '%' using the generic decimal rules. NaN -> "---".
 * @param {number} x fraction [0, 1]
 * @returns {string}
 */
export function fmtPct(x) {
  if (Number.isNaN(x)) return '---';
  return fmt(x * 100) + '%';
}

// --- Row builders (port of MeasurementRow.forVolts/forTime/forFreq/forPct). ---

/**
 * @typedef {Object} RowStats
 * @property {number} mean
 * @property {number} min
 * @property {number} max
 * @property {number} sigma
 */

/**
 * @typedef {Object} MeasurementRow
 * @property {string} name
 * @property {string} cur
 * @property {string} avg
 * @property {string} min
 * @property {string} max
 * @property {string} sigma
 */

function row(name, cur, avg, min, max, sigma) {
  return { name, cur, avg, min, max, sigma };
}

/** Format cur + window stats through `fmt`, scaled by `scale`. */
function scaledRow(name, scale, cur, stats) {
  return row(
    name,
    fmt(cur * scale),
    fmt(stats.mean * scale),
    fmt(stats.min * scale),
    fmt(stats.max * scale),
    fmt(stats.sigma * scale),
  );
}

/**
 * Volts row (port of MeasurementRow.forVolts): the unit prefix is chosen once
 * from the peak magnitude across cur + stats so every column lines up.
 * @param {string} base row label
 * @param {number} curVal volts
 * @param {RowStats} stats
 * @returns {MeasurementRow}
 */
export function forVolts(base, curVal, stats) {
  const p = voltsPrefix(peakMag(curVal, stats));
  return scaledRow(base + ', ' + p.unit, p.scale, curVal, stats);
}

/**
 * Time row (port of MeasurementRow.forTime).
 * @param {string} base row label
 * @param {number} curVal seconds
 * @param {RowStats} stats
 * @returns {MeasurementRow}
 */
export function forTime(base, curVal, stats) {
  const p = timePrefix(peakMag(curVal, stats));
  return scaledRow(base + ', ' + p.unit, p.scale, curVal, stats);
}

/**
 * Frequency row (port of MeasurementRow.forFreq): each column uses fmtFreq.
 * @param {string} base row label
 * @param {number} curVal Hz
 * @param {RowStats} stats
 * @returns {MeasurementRow}
 */
export function forFreq(base, curVal, stats) {
  return row(
    base + ', Hz',
    fmtFreq(curVal),
    fmtFreq(stats.mean),
    fmtFreq(stats.min),
    fmtFreq(stats.max),
    fmtFreq(stats.sigma),
  );
}

/**
 * Percentage row (port of MeasurementRow.forPct): scaled by 100.
 * @param {string} base row label
 * @param {number} curVal fraction [0, 1]
 * @param {RowStats} stats
 * @returns {MeasurementRow}
 */
export function forPct(base, curVal, stats) {
  return scaledRow(base + ', %', 100, curVal, stats);
}
