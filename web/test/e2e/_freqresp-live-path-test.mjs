/*
 * Phonalyser web - FreqResp LIVE-PATH deconvolution test (no browser).
 * Temp (_-prefixed) verification that the sweep runner's INVARIANT - the played
 * buffer IS the deconvolution reference, at the CAPTURE rate - recovers a clean
 * response, and that BREAKING that invariant (rendering the reference at a
 * different rate than the sweep was clocked at, the pre-fix web bug) produces the
 * comb-noise garbage the user reported.
 *
 * This exercises the exact buffer the host now builds in captureAndDeconvolve:
 *   played = [lead-in silence] + [renderWindowedLogSweep · peakScale] + [tail]
 * then applies a twin-T 1 kHz notch DUT to `played` to synthesize the captured Y,
 * and deconvolves Y against the RAW renderLogSweep reference (fade applied inside
 * computeFromLogSweep) - reference == the body of `played` by construction.
 *
 * Run: node test/e2e/_freqresp-live-path-test.mjs   (exits non-zero on any FAIL)
 */
import { renderLogSweep, renderWindowedLogSweep, sweepFadeSamples } from '../../js/freqresp/farina-sweep.js';
import { computeFromLogSweep, binAlignedFreqs } from '../../js/freqresp/deconvolve.js';
import { nextPow2 } from '../../js/dsp/mathutil.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('PASS: ' + msg); } else { fail++; console.log('FAIL: ' + msg); } };

// ── Capture / sweep parameters (a 192 kHz-class setup, like the user's) ───────
const SR = 192000;
const F0 = 20, F1 = 90000;              // sweep well up the band (notch DUT at 1 kHz)
const DUR_SEC = 1.0;
const LEAD_IN_SEC = 0.05;
const SWEEP_SAMPLES = Math.round(DUR_SEC * SR);
const LEAD_IN = Math.round(LEAD_IN_SEC * SR);
const TAIL = Math.round(0.5 * SR);
const FADE = sweepFadeSamples(SWEEP_SAMPLES);
const AMP_VRMS = 0.5, ADC_FS_VRMS = 1.0, DAC_FS_PEAK = Math.SQRT2;
const SWEEP_POINTS = 512;

// The host's peak scale: Vrms -> normalised DAC peak (amplitude = Vrms/(dacFsPeak·(1/√2))).
const peakScale = AMP_VRMS / (DAC_FS_PEAK * (1.0 / Math.SQRT2));

// ── The played buffer, built EXACTLY as FreqRespHost.captureAndDeconvolve does ──
function buildPlayed(sampleRate) {
  const windowed = renderWindowedLogSweep(F0, F1, SWEEP_SAMPLES, sampleRate, FADE);
  const played = new Float64Array(LEAD_IN + SWEEP_SAMPLES + TAIL);
  for (let i = 0; i < SWEEP_SAMPLES; i++) played[LEAD_IN + i] = windowed[i] * peakScale;
  return played;
}

// ── Twin-T style notch DUT: a 2nd-order notch biquad at f0, applied in time. ──
// H(s) = (s²+ω₀²)/(s²+ (ω₀/Q) s + ω₀²) - the classic RBJ notch (Q sets width).
function notchBiquad(sampleRate, f0Hz, Q) {
  const w0 = 2 * Math.PI * f0Hz / sampleRate;
  const cw = Math.cos(w0), sw = Math.sin(w0);
  const alpha = sw / (2 * Q);
  const b0 = 1, b1 = -2 * cw, b2 = 1;
  const a0 = 1 + alpha, a1 = -2 * cw, a2 = 1 - alpha;
  return { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
}
function applyBiquad(x, c) {
  const y = new Float64Array(x.length);
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < x.length; i++) {
    const xi = x[i];
    const yi = c.b0 * xi + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
    x2 = x1; x1 = xi; y2 = y1; y1 = yi;
    y[i] = yi;
  }
  return y;
}

console.log('\n== FreqResp LIVE-path deconvolution test (192 kHz, 1 kHz notch DUT) ==');
console.log(`SR=${SR} sweep=${SWEEP_SAMPLES} leadIn=${LEAD_IN} band=${F0}-${F1} Hz points≤${SWEEP_POINTS}\n`);

const sweepRef = renderLogSweep(F0, F1, SWEEP_SAMPLES, SR);   // the deconvolution reference X
const played = buildPlayed(SR);                               // == reference body by construction

const NOTCH_HZ = 1000, NOTCH_Q = 8;
const dutCoef = notchBiquad(SR, NOTCH_HZ, NOTCH_Q);
// Capture a UNITY loopback through the notch: Y = notch(played). No added delay here -
// the IR-peak delay removal handles any bulk delay in the real world; the point of THIS
// test is the reference==playback rate invariant.
const yCap = applyBiquad(played, dutCoef);

const deconvM = nextPow2(Math.max(yCap.length, LEAD_IN + SWEEP_SAMPLES));
const binHz = SR / deconvM;
const freqs = binAlignedFreqs(F0, F1, binHz, SWEEP_POINTS);
const cal = computeFromLogSweep(yCap, sweepRef, LEAD_IN, SR, freqs, AMP_VRMS, ADC_FS_VRMS, FADE);

const dbOf = (m) => (m > 0 ? 20 * Math.log10(m) : -300);

// ── (1) HF passband is flat ~0 dB (the user's Java-clean reference) ───────────
let hfMax = 0, hfN = 0;
for (let i = 0; i < freqs.length; i++) {
  if (freqs[i] < 5000 || freqs[i] > 60000) continue;
  hfN++;
  hfMax = Math.max(hfMax, Math.abs(dbOf(cal.magLin[i])));
}
ok(hfN > 0, `HF band 5-60 kHz has ${hfN} output points`);
ok(hfMax <= 1.0, `HF passband flat ~0 dB (max |dev| ${hfMax.toFixed(3)} dB ≤ 1.0) - no comb-noise, no HF spike`);

// ── (2) Deep notch AT 1 kHz, and ONLY there ──────────────────────────────────
let notchDb = 0, notchF = 0;
for (let i = 0; i < freqs.length; i++) {
  const d = dbOf(cal.magLin[i]);
  if (d < notchDb) { notchDb = d; notchF = freqs[i]; }
}
ok(Math.abs(notchF - NOTCH_HZ) < NOTCH_HZ * 0.15,
  `deepest dip lands at 1 kHz (${notchF.toFixed(1)} Hz, within 15%)`);
// The SG smoothing over the coarse log grid rounds a high-Q null off (Java disables SG
// for the tune-notch grid for the same reason); a clear dip well below the flat passband
// is what proves the DUT feature survives the LIVE path.
ok(notchDb <= -10, `notch is a clear dip (${notchDb.toFixed(1)} dB ≤ -10) - the DUT null is recovered`);

// ── (3) The pre-fix bug: sweep PLAYED at outRate, CAPTURED at inRate -> garbage ─
// The old web path clocked the DDS sweep at the OUTPUT context rate and captured at the
// INPUT rate, so the physical sweep spanned sweepSamples/outRate seconds and the ADC
// re-sampled it to a DIFFERENT length (× inRate/outRate) whose f-vs-sample trajectory no
// longer matches the reference. Model that by resampling the captured Y by inRate/outRate
// (nearest-sample), then deconvolve against the SR reference - it must NOT be flat.
const OUT_RATE = 176400;   // a plausible different granted output rate on a 192k box
const ratio = SR / OUT_RATE;
const badLen = Math.round(yCap.length * ratio);
const yBad = new Float64Array(badLen);
for (let i = 0; i < badLen; i++) {
  const src = Math.min(yCap.length - 1, Math.round(i / ratio));
  yBad[i] = yCap[src];
}
const calBad = computeFromLogSweep(yBad, sweepRef, LEAD_IN, SR, freqs, AMP_VRMS, ADC_FS_VRMS, FADE);
let badMax = 0;
for (let i = 0; i < freqs.length; i++) {
  if (freqs[i] < 5000 || freqs[i] > 60000) continue;
  badMax = Math.max(badMax, Math.abs(dbOf(calBad.magLin[i])));
}
ok(badMax > 3.0,
  `sweep-clock mismatch (the pre-fix bug) is NOT flat (max |dev| ${badMax.toFixed(1)} dB > 3) - confirms the fix's single-clock invariant matters`);

// ── (4) The playSweepBuffer clock-TAG bug + its fix ───────────────────────────
// The runtime path: captureAndDeconvolve renders `played` at the CAPTURE rate (SR)
// and calls engine.playSweepBuffer(played, SR), which opens an output AudioContext.
// The browser may GRANT that context a DIFFERENT rate than requested (the output
// device is a separate clock, often 44.1/48 kHz in Windows even on a 192 kHz input
// box). An AudioBufferSourceNode resamples buffer.sampleRate -> ctx.sampleRate at
// playback. So the AudioBuffer MUST be tagged with the rate the sweep was AUTHORED
// at (SR), not ctx.sampleRate:
//   * correct tag -> Web Audio resamples SR->ctxRate, the sweep plays over its correct
//     PHYSICAL duration (N/SR s) and its f-vs-time law matches the reference -> clean.
//   * wrong tag (buffer tagged ctx.sampleRate, the pre-fix code) -> the engine treats
//     the SR-authored samples as ctxRate-native (NO resample), the sweep plays at the
//     wrong speed (N/ctxRate s) -> captured f-vs-time no longer matches -> comb garbage.
// Model the physical round-trip: emit the played buffer through a context at CTX_RATE,
// then re-capture at SR. A buffer tagged R plays over N/R physical seconds; captured
// at SR that is round(N/R*SR) samples. We emulate the resample with linear interp.
const CTX_RATE = 96000;   // a plausible granted output rate on a 192 kHz-input box
function resampleLinear(x, srcRate, dstRate) {
  const n = Math.round(x.length * dstRate / srcRate);
  const out = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const s = i * srcRate / dstRate, s0 = Math.floor(s), f = s - s0;
    const a = x[Math.min(x.length - 1, s0)], b = x[Math.min(x.length - 1, s0 + 1)];
    out[i] = a + (b - a) * f;
  }
  return out;
}
// A notch DUT whose null sits inside a band CTX_RATE can carry (Nyquist 48 kHz).
const NARROW_F1 = 40000;
const sweepRefN = renderLogSweep(F0, NARROW_F1, SWEEP_SAMPLES, SR);
const windowedN = renderWindowedLogSweep(F0, NARROW_F1, SWEEP_SAMPLES, SR, FADE);
const playedN = new Float64Array(LEAD_IN + SWEEP_SAMPLES);
for (let i = 0; i < SWEEP_SAMPLES; i++) playedN[LEAD_IN + i] = windowedN[i] * peakScale;
const freqsN = binAlignedFreqs(F0, NARROW_F1, SR / nextPow2(Math.max(playedN.length, LEAD_IN + SWEEP_SAMPLES)), SWEEP_POINTS);
const hfDev = (cal) => {
  let m = 0;
  for (let i = 0; i < freqsN.length; i++) {
    if (freqsN[i] < 5000 || freqsN[i] > 30000) continue;
    m = Math.max(m, Math.abs(dbOf(cal.magLin[i])));
  }
  return m;
};
// FIX (correct tag): buffer.sampleRate = SR -> engine resamples to CTX_RATE for
// playback, so the physical sweep still spans N/SR s. Round-trip = SR->CTX->SR ≈ identity.
{
  const emitted = resampleLinear(playedN, SR, CTX_RATE);   // engine resamples SR->ctx
  const recaptured = resampleLinear(emitted, CTX_RATE, SR); // device->capture at SR
  const y = applyBiquad(recaptured, notchBiquad(SR, NOTCH_HZ, NOTCH_Q));
  const cal = computeFromLogSweep(y, sweepRefN, LEAD_IN, SR, freqsN, AMP_VRMS, ADC_FS_VRMS, FADE);
  const dev = hfDev(cal);
  ok(dev <= 3.0, `correct buffer clock tag survives an output-rate mismatch (HF |dev| ${dev.toFixed(2)} dB ≤ 3) - the playSweepBuffer fix`);
}
// BUG (wrong tag): buffer tagged CTX_RATE, samples authored at SR -> NO resample, the
// SR-authored N samples emit over N/CTX_RATE physical seconds (sweep too fast/slow).
{
  const emitted = playedN;                                   // no resample (wrong tag)
  const recaptured = resampleLinear(emitted, CTX_RATE, SR);  // device->capture at SR
  const y = applyBiquad(recaptured, notchBiquad(SR, NOTCH_HZ, NOTCH_Q));
  const cal = computeFromLogSweep(y, sweepRefN, LEAD_IN, SR, freqsN, AMP_VRMS, ADC_FS_VRMS, FADE);
  const dev = hfDev(cal);
  ok(dev > 3.0, `wrong buffer clock tag (pre-fix playSweepBuffer) IS garbage (HF |dev| ${dev.toFixed(1)} dB > 3) - reproduces the reported comb`);
}

// ── (5) The dual-clock fix: author at the ACTUAL granted rate, no band cap ────────
// The real defect was that captureAndDeconvolve authored the reference / played buffer
// / grid at the NOMINAL requested rate, read BEFORE the measurement capture reported the rate
// to the rate the browser actually granted the ADC - so the reference was time-scaled
// against a capture at a different rate (comb nulls + "runs faster"). The fix reads the
// rate AFTER capture opens and authors everything at THAT actual rate; there is NO output
// band cap (the DAC runs on its own clock, as on the desktop's two-device setup). When
// reference and capture share the rate the deconvolution is clean - sections (1)-(2) above
// exercise exactly that matched-rate path (flat passband + recovered notch), which is the
// invariant the actual-rate fix guarantees. The earlier granted-output-Nyquist "cap" model
// was removed with the cap itself.

console.log(`\n${fail === 0 ? 'ALL PASSED' : fail + ' FAILED'}  (${pass} passed, ${fail} failed)\n`);
process.exit(fail === 0 ? 0 : 1);
