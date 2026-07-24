/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.fft.FftResult.
//
// Container for all FFT analysis outputs. A standalone class so each instance is
// fully independent of the FftAnalyzer that produced it — the FFT worker hands
// off results on one tick while the previous one is still being painted.
//
// Field names, defaults and the helper-method math (ensureArrays, deepCopy,
// noisePeakFloorDbFs, localNoiseFloorDbFs, rawHarmonicDbFs, captureRawPeaks)
// mirror the Java class one-for-one. All spectral math uses Float64Array (the
// browser `number` is IEEE-754 binary64, bit-identical to the desktop `double`).

/** Width (bins) of each flank `localNoiseFloorDbFs()` samples just beyond the
 *  fundamental's skirt — the "near range". Wide enough to clear a broad skirt
 *  (±32–50 bins) and give a stable median. */
const LOCAL_FLOOR_FLANK_BINS = 64;

export class FftResult {
  constructor() {
    /** FFT frame length (power of 2). */
    this.fftSize = 0;
    /** Sample rate of the analyzed signal in Hz. */
    this.sampleRate = 0;
    /** Number of frames that were coherently averaged. */
    this.frameCount = 0;
    /** Frequency resolution in Hz per bin (= sampleRate / fftSize). */
    this.freqResolution = 0;
    /** √(bin bandwidth in Hz) — non-null only for results reconstructed from a
     *  saved file ({@code # bin_bw_hz=} header); null for live results. */
    this.binBwSqrt = null;
    /** Window function token (the WindowType enum name, e.g. "BH4"). */
    this.windowType = null;
    /** Overlap token (the FftOverlap enum name, e.g. "PCT_0"). */
    this.overlap = null;

    // Single-sided spectrum, bins 0 … fftSize/2.
    /** @type {?Float64Array} */ this.amplitudeDbFs = null;
    /** @type {?Float64Array} */ this.phaseDeg = null;
    /** @type {?Float64Array} */ this.re = null;
    /** @type {?Float64Array} */ this.im = null;

    // Fundamental
    this.fundamentalBin = 0;
    this.fundamentalHz = 0;
    /** Phase-difference refined frequency in Hz — sub-bin accurate (~1e-5 bin). */
    this.fundamentalHzRefined = 0;
    /** Sub-bin refined frequency (Hz) of the second tone in a dual-/multi-tone
     *  signal; NaN for single-tone (no second-tone hint supplied). */
    this.fundamental2HzRefined = NaN;
    this.fundamentalDbFs = 0;
    this.fundamentalLinear = 0;

    /** Tracked mains fundamental (Hz) for frequency-domain mains rejection, or
     *  NaN when mains suppression is off / unlocked. */
    this.mainsF0Hz = NaN;
    /** Pinned coherent fundamental bin (κ) for the plot-time re-derive, or NaN
     *  for a single (non-averaged) tick. */
    this.coherentKappa = NaN;
    /** Channel this result was analyzed for (true = left). */
    this.channelLeft = true;
    /** Absolute sample index of this result's analysis-window start. */
    this.samplesAbsStart = 0;
    /** Live capture writePos when this result was produced. */
    this.writePos = 0;
    /** The producing worker's reset epoch at analysis start. */
    this.epoch = 0;

    // Harmonics (index 0 = 2nd harmonic, …)
    this.harmonicCount = 0;
    /** @type {?Int32Array}   */ this.harmonicBins = null;
    /** @type {?Float64Array} */ this.harmonicHz = null;
    /** @type {?Float64Array} */ this.harmonicDbFs = null;
    /** @type {?Float64Array} */ this.harmonicPct = null;

    // Raw (pre-.frc-de-embed) complex phasors of the fundamental and of each
    // distortion peak — drive the DAC-predistortion de-embed.
    this.rawFundRe = NaN;
    this.rawFundIm = NaN;
    /** @type {?Float64Array} */ this.rawPeakRe = null;
    /** @type {?Float64Array} */ this.rawPeakIm = null;

    // Dual-tone intermod-product grid (integer coefficients + product bin,
    // aligned across the three arrays). Empty when not dual-tone / not engaged.
    /** @type {?Int32Array} */ this.imdProductA = null;
    /** @type {?Int32Array} */ this.imdProductB = null;
    /** @type {?Int32Array} */ this.imdProductBin = null;

    // Metrics — mutable so post-processing (e.g. ADC / frequency-response
    // correction) can rewrite them.
    this.thdPct = 0;
    this.thdDb = 0;
    this.thdNDb = 0;
    this.snrDb = 0;
    /** Unweighted SINAD: 10·log10(refLin² / (noisePower + Σ harmonic power)). */
    this.sinadDb = 0;
    /** Lower bound of the SNR frequency range (Hz); 0 = no limit. */
    this.snrFreqMin = 0;
    /** Upper bound of the SNR frequency range (Hz); 0 = no limit. */
    this.snrFreqMax = 0;
    /** True = coherent (complex) averaging; false = incoherent (power) averaging. */
    this.coherentAveraging = false;
    /** Sum of amplLinear[k]² for noise bins inside the SNR band (unweighted). */
    this.noisePower = 0;
    /** Normalized equivalent noise bandwidth of the analysis window, in bins
     *  (Hann: 1.5) — stamped at analysis time so a band-change recompute applies
     *  the same noise-integral correction to THIS spectrum regardless of the
     *  window selected by then. */
    this.windowNenbwBins = 0;
    /** IEC 61672 A-weighted sibling of noisePower — same SNR band, same zone rescale, divided by windowNenbwBins; feeds the A-suffixed N+D / THD+N / SINAD->ENOB readouts. SNR and N stay on noisePower. */
    this.awNoisePower = 0;
    /** Average noise floor: RMS amplitude of a single noise bin in dBFS. */
    this.avgNoiseFloorDbFs = 0;
    /** Pre-correction (BLUE-dot) snapshot: [0] = freqs[], [1] = dBFs[] with index
     *  0 = fundamental, 1..harmonicCount = H2..HN. null when no cal is loaded. */
    /** @type {?Array<Float64Array>} */ this.preCorrectionPeaks = null;
    /** Half-width of the dynamic fundamental exclusion zone (Hz, one side). */
    this.fundamentalDynExclusionHz = 0;
    /** User-supplied true fundamental level (dBFS) — manual override anchored at
     *  the input boundary, never overwritten by post-processing. NaN when absent. */
    this.fundamentalTrueDbFs = NaN;

    // Frame-rejection diagnostics.
    /** Number of frames rejected this tick; 0 = clean. */
    this.rejectedFrames = 0;
    /** Total frames examined this tick (rejection-ratio denominator). */
    this.rejectionTotalFrames = 0;
    /** true = phase-coherence rejection; false = R-invariant rejection. */
    this.rejectionPhaseCoherence = false;
    /** max |R−A|/A in % (R-invariant) or max phase deviation in deg (phase). */
    this.rejectionDetail = 0;

    // Spectral-discontinuity debug snapshots (set by the worker, not the analyzer).
    this.gates = null;
    /** @type {?Float64Array} */ this.gateBlockDbFs = null;
    /** @type {?Float64Array} */ this.gateRejectDbFs = null;
    this.gateRejectGates = null;
  }

  /** Ensures the bin / harmonic arrays are sized for the next analysis. Reuses
   *  existing arrays when their length already matches; otherwise reallocates.
   *  Called before the analyzer writes the bin arrays so it can use re / im /
   *  amplitudeDbFs / phaseDeg directly as output buffers — no per-tick alloc. */
  ensureArrays(binCount, harmonicCount) {
    if (this.amplitudeDbFs == null || this.amplitudeDbFs.length !== binCount) {
      this.amplitudeDbFs = new Float64Array(binCount);
      this.phaseDeg = new Float64Array(binCount);
      this.re = new Float64Array(binCount);
      this.im = new Float64Array(binCount);
    }
    if (this.harmonicBins == null || this.harmonicBins.length !== harmonicCount) {
      this.harmonicBins = new Int32Array(harmonicCount);
      this.harmonicHz = new Float64Array(harmonicCount);
      this.harmonicDbFs = new Float64Array(harmonicCount);
      this.harmonicPct = new Float64Array(harmonicCount);
    }
  }

  /** Re-adopt a plain transfer object (worker postMessage / _resultToMessage snapshot, or a
   *  structuredClone) as a real FftResult so its methods are callable again. Copies the plain's
   *  own fields onto a fresh instance (typed arrays by reference; scalars by value); fields the
   *  transfer omitted keep their constructor defaults. A no-op if already an FftResult. */
  static adopt(plain) {
    return plain instanceof FftResult ? plain : Object.assign(new FftResult(), plain);
  }

  /** Returns an independent copy of this result. All scalars copied by value;
   *  every typed array cloned so an in-place mutation of the source doesn't
   *  perturb the copy. */
  deepCopy() {
    const c = new FftResult();
    c.fftSize = this.fftSize;
    c.sampleRate = this.sampleRate;
    c.frameCount = this.frameCount;
    c.freqResolution = this.freqResolution;
    c.binBwSqrt = this.binBwSqrt;
    c.windowType = this.windowType;
    c.overlap = this.overlap;
    c.amplitudeDbFs = this.amplitudeDbFs != null ? this.amplitudeDbFs.slice() : null;
    c.phaseDeg = this.phaseDeg != null ? this.phaseDeg.slice() : null;
    c.re = this.re != null ? this.re.slice() : null;
    c.im = this.im != null ? this.im.slice() : null;
    c.fundamentalBin = this.fundamentalBin;
    c.fundamentalHz = this.fundamentalHz;
    c.fundamentalHzRefined = this.fundamentalHzRefined;
    c.fundamental2HzRefined = this.fundamental2HzRefined;
    c.fundamentalDbFs = this.fundamentalDbFs;
    c.fundamentalLinear = this.fundamentalLinear;
    c.mainsF0Hz = this.mainsF0Hz;
    c.coherentKappa = this.coherentKappa;
    c.channelLeft = this.channelLeft;
    c.samplesAbsStart = this.samplesAbsStart;
    c.writePos = this.writePos;
    c.epoch = this.epoch;
    c.harmonicCount = this.harmonicCount;
    c.harmonicBins = this.harmonicBins != null ? this.harmonicBins.slice() : null;
    c.harmonicHz = this.harmonicHz != null ? this.harmonicHz.slice() : null;
    c.harmonicDbFs = this.harmonicDbFs != null ? this.harmonicDbFs.slice() : null;
    c.harmonicPct = this.harmonicPct != null ? this.harmonicPct.slice() : null;
    c.rawFundRe = this.rawFundRe;
    c.rawFundIm = this.rawFundIm;
    c.rawPeakRe = this.rawPeakRe != null ? this.rawPeakRe.slice() : null;
    c.rawPeakIm = this.rawPeakIm != null ? this.rawPeakIm.slice() : null;
    c.imdProductA = this.imdProductA != null ? this.imdProductA.slice() : null;
    c.imdProductB = this.imdProductB != null ? this.imdProductB.slice() : null;
    c.imdProductBin = this.imdProductBin != null ? this.imdProductBin.slice() : null;
    c.thdPct = this.thdPct;
    c.thdDb = this.thdDb;
    c.thdNDb = this.thdNDb;
    c.snrDb = this.snrDb;
    c.sinadDb = this.sinadDb;
    c.snrFreqMin = this.snrFreqMin;
    c.snrFreqMax = this.snrFreqMax;
    c.coherentAveraging = this.coherentAveraging;
    c.noisePower = this.noisePower;
    c.windowNenbwBins = this.windowNenbwBins;
    c.awNoisePower = this.awNoisePower;
    c.avgNoiseFloorDbFs = this.avgNoiseFloorDbFs;
    c.fundamentalDynExclusionHz = this.fundamentalDynExclusionHz;
    c.fundamentalTrueDbFs = this.fundamentalTrueDbFs;
    c.rejectedFrames = this.rejectedFrames;
    c.rejectionTotalFrames = this.rejectionTotalFrames;
    c.rejectionPhaseCoherence = this.rejectionPhaseCoherence;
    c.rejectionDetail = this.rejectionDetail;
    c.gates = this.gates;                 // immutable snapshot — share the reference
    c.gateBlockDbFs = this.gateBlockDbFs; // debug snapshots — share (not mutated)
    c.gateRejectDbFs = this.gateRejectDbFs;
    c.gateRejectGates = this.gateRejectGates;
    if (this.preCorrectionPeaks != null) {
      const src = this.preCorrectionPeaks;
      const dst = new Array(src.length);
      for (let i = 0; i < src.length; i++) dst[i] = src[i] != null ? src[i].slice() : null;
      c.preCorrectionPeaks = dst;
    }
    return c;
  }

  /**
   * Noise floor as the spectrum actually SHOWS it (dBFS): the high-percentile
   * "top of the grass" with the fundamental, the harmonics and their skirts
   * removed. Decays as the coherent average deepens. NaN when no spectrum.
   *
   * MEMOIZED per instance: the spectrum is immutable once emitted (the .frc
   * de-embed applies before any consumer sees the result), and the predistortion
   * wizard polls this at 100 ms (Java PredistortionWizardDialog:346) — Java
   * re-sorts a primitive double[] each tick and gets away with it; the web pays
   * once per NEW result instead. The percentile comes from an O(n) k-th order
   * statistic (same element Java's Arrays.sort → noise[idx] yields, :369-371).
   */
  noisePeakFloorDbFs() {
    if (this._noisePeakFloor !== undefined) return this._noisePeakFloor;
    if (this.amplitudeDbFs == null || !(this.freqResolution > 0)) return NaN;
    const n = this.amplitudeDbFs.length;
    const excl = new Uint8Array(n);
    const fundHalf = Math.max(1, Math.ceil(this.fundamentalDynExclusionHz / this.freqResolution));
    this._markSignalBins(excl, this.fundamentalBin, fundHalf);
    const harmHalf = Math.max(3, Math.trunc(fundHalf / 8));
    if (this.harmonicBins != null) {
      for (let i = 0; i < this.harmonicCount && i < this.harmonicBins.length; i++) {
        this._markSignalBins(excl, this.harmonicBins[i], harmHalf);
      }
    }
    const noise = new Float64Array(n);
    let cnt = 0;
    for (let b = 1; b < n; b++) {
      if (!excl[b] && Number.isFinite(this.amplitudeDbFs[b])) noise[cnt++] = this.amplitudeDbFs[b];
    }
    if (cnt === 0) { this._noisePeakFloor = NaN; return NaN; }
    const idx = Math.min(cnt - 1, Math.round(0.999 * (cnt - 1)));
    this._noisePeakFloor = this._kthSmallest(noise, cnt, idx);
    return this._noisePeakFloor;
  }

  /** Exact k-th order statistic of a[0..cnt) (the value a full ascending sort
   *  would put at index k — Java FftResult:369-371), via median-of-3 Hoare
   *  quickselect: O(n) and comparator-free, where the previous comparator sort
   *  over ~1M bins cost hundreds of ms per call at large FFT sizes. Partially
   *  reorders a (callers pass a scratch copy). */
  _kthSmallest(a, cnt, k) {
    let lo = 0, hi = cnt - 1;
    while (lo < hi) {
      // Median-of-3 pivot: order a[lo], a[mid], a[hi] in place.
      const mid = (lo + hi) >> 1;
      if (a[mid] < a[lo]) { const t = a[mid]; a[mid] = a[lo]; a[lo] = t; }
      if (a[hi] < a[lo]) { const t = a[hi]; a[hi] = a[lo]; a[lo] = t; }
      if (a[hi] < a[mid]) { const t = a[hi]; a[hi] = a[mid]; a[mid] = t; }
      const p = a[mid];
      // Hoare partition around p.
      let i = lo, j = hi;
      while (i <= j) {
        while (a[i] < p) i++;
        while (a[j] > p) j--;
        if (i <= j) { const t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
      }
      if (k <= j) hi = j;
      else if (k >= i) lo = i;
      else return a[k];
    }
    return a[lo];
  }

  /**
   * Robust noise floor (dBFS) in the NEAR RANGE of the fundamental — the median
   * of two LOCAL_FLOOR_FLANK_BINS-wide flanks just beyond the fundamental's
   * dynamic skirt. A sharpness probe, not a wide-band floor. NaN when the flanks
   * hold no usable bins.
   */
  localNoiseFloorDbFs() {
    if (this.amplitudeDbFs == null || !(this.freqResolution > 0) || this.fundamentalBin <= 0) return NaN;
    const n = this.amplitudeDbFs.length;
    const skirt = Math.max(1, Math.ceil(this.fundamentalDynExclusionHz / this.freqResolution));
    const band = new Float64Array(2 * LOCAL_FLOOR_FLANK_BINS);
    let cnt = 0;
    for (let b = this.fundamentalBin - skirt - LOCAL_FLOOR_FLANK_BINS; b < this.fundamentalBin - skirt; b++) {
      if (b >= 1 && b < n && Number.isFinite(this.amplitudeDbFs[b])) band[cnt++] = this.amplitudeDbFs[b];
    }
    for (let b = this.fundamentalBin + skirt + 1; b <= this.fundamentalBin + skirt + LOCAL_FLOOR_FLANK_BINS; b++) {
      if (b >= 1 && b < n && Number.isFinite(this.amplitudeDbFs[b])) band[cnt++] = this.amplitudeDbFs[b];
    }
    if (cnt === 0) return NaN;
    const sorted = band.subarray(0, cnt).slice().sort((x, y) => x - y);
    return sorted[Math.trunc(cnt / 2)];
  }

  /**
   * Harmonic i's level (dBFS) as ORIGINALLY measured — before any .frc
   * de-embedding. Index 0 = H2. NaN when unavailable.
   */
  rawHarmonicDbFs(i) {
    if (this.preCorrectionPeaks != null && this.preCorrectionPeaks.length > 1
        && this.preCorrectionPeaks[1] != null && 1 + i < this.preCorrectionPeaks[1].length) {
      return this.preCorrectionPeaks[1][1 + i];
    }
    return (this.harmonicDbFs != null && i < this.harmonicDbFs.length) ? this.harmonicDbFs[i] : NaN;
  }

  /**
   * Stamps the RAW (pre-.frc-de-embed) fundamental + distortion-peak phasors off
   * the current re/im into rawFundRe / rawPeakRe for the DAC-predistortion
   * correction — call while re/im still hold the raw averaged spectrum, i.e.
   * BEFORE any in-place .frc de-embed. The peak array carries the HARMONIC bins
   * for a single tone and the de-rotated INTERMOD-PRODUCT bins (imdProductBin)
   * for a dual tone.
   */
  captureRawPeaks() {
    if (this.re == null || this.im == null || this.freqResolution <= 0 || !(this.fundamentalHzRefined > 0)) {
      return;
    }
    const fundBin = Math.round(this.fundamentalHzRefined / this.freqResolution);
    if (fundBin <= 0 || fundBin >= this.re.length) return;

    // Scale the captured phasors by the FFT amplitude factor so |phasor| equals
    // the ABSOLUTE level (dBFS-linear) at that bin. The factor is constant across
    // the spectrum; deriving it from the fundamental bin is exact.
    const fundMag = Math.hypot(this.re[fundBin], this.im[fundBin]);
    const conv = (this.amplitudeDbFs != null && fundMag > 0.0)
      ? Math.pow(10.0, this.amplitudeDbFs[fundBin] / 20.0) / fundMag : 1.0;
    this.rawFundRe = this.re[fundBin] * conv;
    this.rawFundIm = this.im[fundBin] * conv;

    const dualTone = this.imdProductBin != null && this.imdProductBin.length > 0;
    let peakBins;
    if (dualTone) {
      peakBins = this.imdProductBin;
    } else {
      const n = Math.max(0, this.harmonicCount);
      peakBins = new Int32Array(n);
      for (let i = 0; i < n; i++) {
        peakBins[i] = Math.round((i + 2) * this.fundamentalHzRefined / this.freqResolution);
      }
    }
    this.rawPeakRe = new Float64Array(peakBins.length);
    this.rawPeakIm = new Float64Array(peakBins.length);
    for (let i = 0; i < peakBins.length; i++) {
      const b = peakBins[i];
      if (b > 0 && b < this.re.length) {
        this.rawPeakRe[i] = this.re[b] * conv;
        this.rawPeakIm[i] = this.im[b] * conv;
      }
    }
  }

  /** Marks center ± half bins as signal (excluded from the noise set). */
  _markSignalBins(excl, center, half) {
    if (center <= 0) return;
    const lo = Math.max(0, center - half);
    const hi = Math.min(excl.length - 1, center + half);
    for (let b = lo; b <= hi; b++) excl[b] = 1;
  }
}
