/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the .dpd (DAC predistortion) serialisation: the
// writeDpd() methods of org.edgo.audio.measure.dsp.HarmonicCompensation and
// IntermodCompensation, and the read side of
// org.edgo.audio.measure.generator.SignalGenerator
// (readDpd / isDualToneCorrectionFile / loadHarmonics / loadIntermod).
//
// The file is self-describing: a single-tone (harmonic) file's column header
// begins "harmonic;…"; a dual-tone (intermod) file's begins "a;b;…". Data rows
// use German-locale decimals (comma decimals, semicolon fields) so the
// generator's existing SINE_COMPENSATED loader reads them unchanged; the comment
// header lines use US-locale (dot) decimals. The "2nd freq for IMD" lives in the
// header as `# frequency2_hz=`; the THD vs IMD signature is the column header
// itself (and the wizard-supplied `# kind=` / file-name `THD`/`IMD` token).
//
// writeDpd produces a text string (UTF-8); readDpd parses a text string. All
// math uses the JS `number` (IEEE-754 binary64, bit-identical to the desktop
// `double`); accumulator/ratio arrays are Float64Array.

// ---------------------------------------------------------------------------
// Locale-faithful number formatting (mirrors Java printf %.<p>f / %.<p>e).
// ---------------------------------------------------------------------------

/** Java `%.<p>f` (US locale): fixed-point with `p` fraction digits, dot decimal. */
function fixedUs(value, p) {
  return value.toFixed(p);
}

/** Java `%.<p>f` in GERMAN locale: fixed-point with `p` digits, COMMA decimal. */
function fixedDe(value, p) {
  return value.toFixed(p).replace('.', ',');
}

/** Java `%.<p>e` in GERMAN locale: scientific with `p` mantissa digits, comma
 *  decimal, two-digit signed exponent (e.g. -1.0000000000e+00 → "-1,0000000000e+00").
 *  Java pads the exponent to a minimum of two digits and always carries a sign. */
function sciDe(value, p) {
  let s = value.toExponential(p);              // e.g. "-1.0000000000e+0"
  s = s.replace('.', ',');
  // Normalise the exponent to a signed, min-two-digit field like Java's %e.
  s = s.replace(/e([+-]?)(\d+)/, (_m, sign, digits) => {
    const sgn = sign === '-' ? '-' : '+';
    const d = digits.length < 2 ? digits.padStart(2, '0') : digits;
    return 'e' + sgn + d;
  });
  return s;
}

// ---------------------------------------------------------------------------
// Write — single tone (harmonic predistortion).
// ---------------------------------------------------------------------------

/**
 * Serialises a single-tone harmonic-predistortion correction to .dpd text —
 * byte-compatible with the CLI iterative-compensate output so the generator's
 * SINE_COMPENSATED loader reads it unchanged. The de-embed mirrors
 * HarmonicCompensation.toGeneratorCorrections: ÷ chain H(f_h), ADC dBFS → Vrms,
 * ratio to the DAC fundamental.
 *
 * `amplitudeVrms` IS the DAC fundamental (genAmplitudeVrms); the ratio columns
 * (amplitude_pct + re/im) are taken against it. The amplitude_dbv column carries
 * the de-embedded ABSOLUTE dBV.
 *
 * @param {import('../predistortion/harmonic-compensation.js').HarmonicCompensation} comp
 * @param {?string[]} extraHeaderLines provenance comment lines written first
 * @param {number} fundamentalHz fundamental frequency (Hz)
 * @param {number} fundamentalDbFs unused by the format (kept for signature parity)
 * @param {number} sampleRate sample rate (Hz)
 * @param {number} bitDepth output bit depth
 * @param {number} amplitudeVrms DAC fundamental output (Vrms)
 * @param {(f: number) => [number, number]} calResponseAt .frc response [magLin, phaseRad]
 * @param {number} adcFsVoltageRms ADC full-scale (Vrms)
 * @returns {string} the .dpd file contents
 */
export function writeHarmonicDpd(comp, extraHeaderLines, fundamentalHz, fundamentalDbFs,
    sampleRate, bitDepth, amplitudeVrms, calResponseAt, adcFsVoltageRms) {
  const fundDbV = amplitudeVrms > 0.0 ? 20.0 * Math.log10(amplitudeVrms) : 0.0;
  const out = [];
  if (extraHeaderLines != null) {
    for (const line of extraHeaderLines) out.push(line);
  }
  out.push(`# sample_rate_hz=${sampleRate}`);
  out.push(`# bit_depth=${bitDepth}`);
  out.push(`# amplitude_vrms=${fixedUs(amplitudeVrms, 10)}`);
  out.push(`# frequency_hz=${fixedUs(fundamentalHz, 10)}`);
  out.push('harmonic;frequency_hz;amplitude_dbv;amplitude_pct;phase_deg;re;im');
  // H1 sentinel row: amplitude_pct=100, phase_deg=-90, re=0, im=-1.
  out.push(`1;${fixedDe(fundamentalHz, 6)};${fixedDe(fundDbV, 4)};100,000000;-90,0000;`
    + `${sciDe(0.0, 10)};${sciDe(-1.0, 10)}`);
  for (let h = 0; h < comp.maxHarmonics; h++) {
    if (comp.accRe[h] === 0.0 && comp.accIm[h] === 0.0) continue;
    const cal = calResponseAt(comp.hFreqs[h]);
    const magDe = Math.hypot(comp.accRe[h], comp.accIm[h]) / (cal[0] > 0.0 ? cal[0] : 1.0);
    const vH = magDe * adcFsVoltageRms;                          // de-embedded harmonic, abs Vrms
    const amp = amplitudeVrms > 0.0 ? vH / amplitudeVrms : 0.0;
    const phase = Math.atan2(comp.accIm[h], comp.accRe[h]) - cal[1];
    const re = amp * Math.cos(phase), im = amp * Math.sin(phase);
    const phaseDeg = radToDeg(phase);
    const ampPct = amp * 100.0;
    const ampDbV = vH > 0.0 ? 20.0 * Math.log10(vH) : -300.0;
    out.push(`${h + 2};${fixedDe(comp.hFreqs[h], 6)};${fixedDe(ampDbV, 4)};`
      + `${fixedDe(ampPct, 9)};${fixedDe(phaseDeg, 4)};${sciDe(re, 10)};${sciDe(im, 10)}`);
  }
  return out.join('\n') + '\n';
}

// ---------------------------------------------------------------------------
// Write — dual tone (intermodulation predistortion).
// ---------------------------------------------------------------------------

/**
 * Serialises a dual-tone intermod-predistortion correction to .dpd text. Mirrors
 * the single-tone format but the per-row key is the (a, b) coefficient pair. The
 * re/im carry the ratio phasor the dual-tone loader consumes; amplitude_dbv
 * carries the de-embedded ABSOLUTE dBV. De-embed mirrors
 * IntermodCompensation.toGeneratorCorrections (unit response for b=0).
 *
 * @param {import('../predistortion/intermod-compensation.js').IntermodCompensation} comp
 * @param {?string[]} extraHeaderLines provenance comment lines written first
 * @param {number} f1Hz tone-1 frequency (Hz)
 * @param {number} f2Hz tone-2 frequency (Hz) — the "2nd freq for IMD"
 * @param {number} fundamentalDbFs unused by the format (kept for signature parity)
 * @param {number} sampleRate sample rate (Hz)
 * @param {number} bitDepth output bit depth
 * @param {number} amplitudeVrms total generator amplitude (Vrms) — header only
 * @param {(f: number) => [number, number]} calResponseAt .frc response [magLin, phaseRad]
 * @param {number} adcFsVoltageRms ADC full-scale (Vrms)
 * @param {number} dacFundamentalVrms DAC F1 fundamental output (Vrms) the ratio is taken against
 * @returns {string} the .dpd file contents
 */
export function writeIntermodDpd(comp, extraHeaderLines, f1Hz, f2Hz, fundamentalDbFs,
    sampleRate, bitDepth, amplitudeVrms, calResponseAt, adcFsVoltageRms, dacFundamentalVrms) {
  const out = [];
  if (extraHeaderLines != null) {
    for (const line of extraHeaderLines) out.push(line);
  }
  out.push(`# sample_rate_hz=${sampleRate}`);
  out.push(`# bit_depth=${bitDepth}`);
  out.push(`# amplitude_vrms=${fixedUs(amplitudeVrms, 10)}`);
  out.push(`# frequency1_hz=${fixedUs(f1Hz, 10)}`);
  out.push(`# frequency2_hz=${fixedUs(f2Hz, 10)}`);
  out.push('a;b;frequency_hz;amplitude_dbv;amplitude_pct;phase_deg;re;im');
  for (let g = 0; g < comp.accRe.length; g++) {
    if (comp.accRe[g] === 0.0 && comp.accIm[g] === 0.0) continue;
    const cal = comp.coefB[g] === 0 ? [1.0, 0.0] : calResponseAt(comp.freqHz[g]);
    const magDe = Math.hypot(comp.accRe[g], comp.accIm[g]) / (cal[0] > 0.0 ? cal[0] : 1.0);
    const vP = magDe * adcFsVoltageRms;
    const amp = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;
    const phase = Math.atan2(comp.accIm[g], comp.accRe[g]) - cal[1];
    const re = amp * Math.cos(phase), im = amp * Math.sin(phase);
    const phaseDeg = radToDeg(phase);
    const ampPct = amp * 100.0;
    const ampDbV = vP > 0.0 ? 20.0 * Math.log10(vP) : -300.0;
    out.push(`${comp.coefA[g]};${comp.coefB[g]};${fixedDe(comp.freqHz[g], 6)};`
      + `${fixedDe(ampDbV, 4)};${fixedDe(ampPct, 9)};${fixedDe(phaseDeg, 4)};`
      + `${sciDe(re, 10)};${sciDe(im, 10)}`);
  }
  return out.join('\n') + '\n';
}

// ---------------------------------------------------------------------------
// Read.
// ---------------------------------------------------------------------------

/**
 * Reads a .dpd correction file's text and returns the generator-ready
 * compensation. The format is self-describing (the first non-comment line's
 * column header is the THD/IMD signature): a single-tone (harmonic) file's header
 * begins "harmonic;…"; a dual-tone (intermod) file's begins "a;b;…".
 *
 * For a single-tone file, `frequency` (Hz) and `sampleRate` drive the system-delay
 * phase re-derivation from the H1 sentinel row (mirrors loadHarmonics); for a
 * dual-tone file they are ignored (the products follow the live tone frequencies)
 * and the stored re/im are applied directly (mirrors loadIntermod).
 *
 * @param {string} text the .dpd file contents
 * @param {number} frequency single-tone fundamental (Hz); ignored for dual-tone
 * @param {number} sampleRate sample rate (Hz); single-tone only (delay log)
 * @returns {{dualTone: boolean, amp: Float64Array, phi: Float64Array,
 *            harmonicNumbers: ?Int32Array, coefA: ?Int32Array, coefB: ?Int32Array}}
 */
export function readDpd(text, frequency, sampleRate) {
  if (isDualToneDpd(text)) {
    return loadIntermodDpd(text);
  }
  return loadHarmonicsDpd(text, frequency, sampleRate);
}

/** True when `text`'s first non-comment line (the column header) marks a dual-tone
 *  intermod file ("a;b;…") rather than a single-tone harmonic file ("harmonic;…"). */
export function isDualToneDpd(text) {
  const lines = text.split(/\r?\n/);
  for (let raw of lines) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    return line.toLowerCase().startsWith('a;b');
  }
  return false;
}

/** Parses a single-tone harmonic .dpd. Skips H1 (fundamental) but captures its
 *  phase for the system-delay re-derivation. Faithful port of
 *  SignalGenerator.loadHarmonics. */
function loadHarmonicsDpd(text, frequency, sampleRate) {
  // [ampRatio, phi_measured (rad), freqHz, harmonicNumber]
  const list = [];
  let phi1 = NaN;   // fundamental measured phase (rad) — used for delay compensation

  const lines = text.split(/\r?\n/);
  for (let raw of lines) {
    const line = raw.trim();
    // skip blank lines, comments, and the header row
    if (line.length === 0 || !isAsciiDigit(line.charAt(0))) continue;
    const cols = line.split(';');
    if (cols.length < 5) continue;
    const hIndex = parseInt(cols[0].trim(), 10);

    // Derive phase from re/im (full precision) or phase_deg column.
    let phi;
    if (cols.length >= 7) {
      const re = parseGerman(cols[5]);
      const im = parseGerman(cols[6]);
      phi = Math.atan2(im, re);
    } else {
      phi = degToRad(parseGerman(cols[4]));
    }

    if (hIndex === 1) {
      phi1 = phi;   // capture fundamental phase for delay compensation
      continue;
    }
    const ampPct = parseGerman(cols[3]);
    const freqHz = parseGerman(cols[1]);
    list.push([ampPct / 100.0, phi, freqHz, hIndex]);
  }

  if (Number.isNaN(phi1)) {
    // H1 row absent — assume DDS sine start phase (-π/2), delay unknown → no correction.
    phi1 = -Math.PI / 2.0;
  }

  // System delay compensation (see loadHarmonics):
  //   ωD = -(phi1_measured + π/2);  phi_init = phi_h_measured + freqHz·(ωD/frequency)
  const omegaD = -(phi1 + Math.PI / 2.0);
  const delayRadPerHz = omegaD / frequency;

  const n = list.length;
  const amp = new Float64Array(n);
  const hNums = new Int32Array(n);
  const phis = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const h = list[i];
    amp[i] = h[0];
    const phiH = h[1];
    const freqHz = h[2];
    const hNumber = h[3] | 0;
    phis[i] = phiH + freqHz * delayRadPerHz;
    hNums[i] = hNumber;
  }
  return { dualTone: false, amp, phi: phis, harmonicNumbers: hNums, coefA: null, coefB: null };
}

/** Parses a dual-tone intermod .dpd. The stored re/im are the final correction
 *  phasor (delay already baked in during accumulation), so amplitude = hypot(re,im)
 *  and phase = atan2(im,re) apply directly. Faithful port of
 *  SignalGenerator.loadIntermod. */
function loadIntermodDpd(text) {
  const coef = [];   // [a, b]
  const phas = [];   // [re, im]
  const lines = text.split(/\r?\n/);
  for (let raw of lines) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    const cols = line.split(';');
    if (cols.length < 8) continue;                       // skip the header / short rows
    const c0 = cols[0].trim().charAt(0);
    if (!isAsciiDigit(c0) && c0 !== '-') continue;       // header row "a;b;…"
    const a = parseInt(cols[0].trim(), 10);
    const b = parseInt(cols[1].trim(), 10);
    const re = parseGerman(cols[6]);
    const im = parseGerman(cols[7]);
    coef.push([a, b]);
    phas.push([re, im]);
  }
  const n = coef.length;
  const amp = new Float64Array(n);
  const aC = new Int32Array(n);
  const bC = new Int32Array(n);
  const phi = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const re = phas[i][0], im = phas[i][1];
    amp[i] = Math.hypot(re, im);
    phi[i] = Math.atan2(im, re);
    aC[i] = coef[i][0];
    bC[i] = coef[i][1];
  }
  return { dualTone: true, amp, phi, harmonicNumbers: null, coefA: aC, coefB: bC };
}

// ---------------------------------------------------------------------------
// Small helpers.
// ---------------------------------------------------------------------------

/** Java `Character.isDigit` for the ASCII range used by the loaders. */
function isAsciiDigit(ch) {
  return ch >= '0' && ch <= '9';
}

/** Parses a German-locale decimal (comma → dot), mirroring `replace(',', '.')`. */
function parseGerman(col) {
  return parseFloat(col.trim().replace(',', '.'));
}

function radToDeg(rad) {
  return rad * (180.0 / Math.PI);
}

function degToRad(deg) {
  return deg * (Math.PI / 180.0);
}
