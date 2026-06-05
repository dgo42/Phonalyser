/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.edgo.audio.measure.gui.fft;

import java.util.ArrayList;
import java.util.List;

/**
 * Relay-feedback autotuner for the generator {@link FrequencyLockLoop}
 * (Åström–Hägglund → Ziegler–Nichols).
 *
 * <p>The frequency-lock plant — from the generator frequency-trim
 * correction {@code u} to the measured error {@code err = detected −
 * target} — is essentially unity gain with a measurement lag.  Driving it
 * with a hysteretic relay forces a sustained limit cycle; from the cycle's
 * period {@code Tu} and amplitude {@code a} the ultimate gain follows from
 * the relay describing function
 * <pre>
 *   Ku = 4·d / (π·√(a² − ε²))
 * </pre>
 * and Ziegler–Nichols maps {@code (Ku, Tu)} to PID gains in the loop's
 * units (Kp dimensionless, Ki = Kp/Ti [1/s], Kd = Kp·Td [s]).
 *
 * <h2>Why the relay is window-gated and sub-bin</h2>
 * <p>The detected fundamental is a two-clean-frame phase-difference
 * estimate over the <em>whole</em> FFT window (seconds long for a large
 * FFT).  A perturbation only shows up cleanly once a full window has
 * filled at the new frequency; a frequency step <em>within</em> a window
 * smears the tone and trips the analyser's discontinuity / overrun
 * re-sync.  So the relay
 * <ul>
 *   <li>keeps {@code d} and {@code ε} <b>bounded to a fraction of the bin
 *       width</b> (no peak-bin jump, minimal discontinuity), and</li>
 *   <li>only flips when <b>at least one full window</b> of fresh samples
 *       has elapsed since the previous flip, so the {@code err} it reacts
 *       to reflects a settled measurement.</li>
 * </ul>
 * Transition re-syncs are expected (the dither deliberately moves the
 * tone), so they are counted and surfaced but never abort the run; the
 * post-re-sync {@code detected} is valid and the period is timed from the
 * sample-domain flip instants, immune to the gap.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link Phase#NOISE} — hold the bias, watch {@code err} for
 *       {@code noiseSamples} frames; size ε and d from the jitter σ
 *       (bin-bounded) and find the lock center.</li>
 *   <li>{@link Phase#SETTLE} — run the gated relay, discard the first
 *       {@code settleCycles} limit cycles (start-up transient).</li>
 *   <li>{@link Phase#MEASURE} — average {@code Tu} and peak-to-peak err
 *       over {@code measureCycles} cycles → Ku → Ziegler–Nichols →
 *       {@link Phase#DONE}.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>Not synchronized — {@link #process} and {@link #onResync} run on the
 * SWT UI thread (FFT-result / re-sync dispatch); the dialog reads the live
 * getters from the same thread.
 */
public final class FllAutotuneSession {

    /** Coarse stage of the run, for the dialog's progress display. */
    public enum Phase { NOISE, SETTLE, MEASURE, DONE, FAILED }

    /** Mapping from the relay measurement (Ku, Tu) to PID gains.
     *
     *  <p>{@link #IMC} is the right choice for this <b>deadtime-dominated</b>
     *  loop (the FFT window + transport lag is ~one full cycle): a pure
     *  integral controller (Kp=Kd=0) sized from the measured deadtime
     *  θ=Tu/2 and gain K=4/(π·Ku) via λ-tuning, λ=3θ → gain margin ≈6 →
     *  <b>no overshoot</b> by construction.  No proportional term (which
     *  would feed back the stale, deadtime-delayed error) and no derivative
     *  (which would amplify the measurement noise into a growing
     *  oscillation).
     *
     *  <p>The Ziegler–Nichols variants below are kept for comparison but all
     *  over-tune a deadtime-dominated plant (they assume θ ≪ τ): CLASSIC is
     *  the ~25 %-overshoot textbook rule, the rest ring less but still
     *  oscillate here. */
    public enum Rule {
        IMC,              // λ-tuning, pure integral: Kp=0, Ki=1/(K(λ+θ)), Kd=0
        CLASSIC,          // Kp=.6Ku   Ti=Tu/2    Td=Tu/8
        PESSEN,           // Kp=.7Ku    Ti=Tu/2.5  Td=3Tu/20
        SOME_OVERSHOOT,   // Kp=.33Ku   Ti=Tu/2    Td=Tu/3
        NO_OVERSHOOT,     // Kp=.2Ku    Ti=Tu/2    Td=Tu/3
        TYREUS_LUYBEN     // Kp=.45Ku   Ti=2.2Tu   Td=Tu/6.3
    }

    /** λ = LAMBDA·θ closed-loop time constant for the {@link Rule#IMC}
     *  tuning.  ≥2 gives no overshoot; 3 leaves a comfortable robustness
     *  margin against the deadtime / gain estimate.  Package-visible so the
     *  test asserts against the constant, not a hard-coded copy. */
    static final double IMC_LAMBDA = 2.0;

    // Relay sizing, expressed in FFT bins so it scales with resolution and
    // can never jump the peak bin or explode on a noisy σ estimate.
    private static final double EPS_FLOOR_BINS = 0.10;   // ε ≥ 0.10 bin
    private static final double EPS_MAX_BINS   = 0.50;   // ε ≤ 0.50 bin
    private static final double D_FLOOR_BINS   = 0.40;   // d ≥ 0.40 bin
    private static final double D_MAX_BINS     = 2.00;   // d ≤ 2.00 bins

    private static final int RING = 512;

    // ---- configuration (defaults; the dialog may override via ctor) ----
    private final int    noiseSamples;
    private final int    settleCycles;
    private final int    measureCycles;
    private final double epsSigma;          // ε from epsSigma·σ (then bin-clamped)
    private final double relayEps;          // d from relayEps·ε (then bin-clamped)
    private final int    maxStallSamples;   // abort if no flip for this many frames
    private final Rule   rule;

    // ---- run state ----
    private final double biasHz;            // frozen drift-hold correction
    private Phase   phase = Phase.NOISE;
    private String  failMessage = "";

    private boolean haveNoiseStart = false;
    private double  noiseStartT = 0.0;      // s, first frame — settle reference
    private int     noiseCount = 0;
    private double  noiseSum = 0.0, noiseSumSq = 0.0;
    private double  center = 0.0;           // err operating point (NOISE mean)
    private double  epsHz = 0.0;            // ε once sized
    private double  dHz = 0.0;              // d once sized

    private int     relaySign = -1;         // +1 → u=+d, −1 → u=−d
    private double  windowSec = 0.0;        // current FFT window length (gate)
    private double  lastFlipT = 0.0;        // s, instant of the last relay flip
    private boolean haveTime = false;
    private double  lastCycleStartT = 0.0;  // s, at last flip-to-(−1)
    private double  cycErrMin = Double.POSITIVE_INFINITY;
    private double  cycErrMax = Double.NEGATIVE_INFINITY;
    private int     cyclesSeen = 0;         // completed cycles in the current phase
    private int     stall = 0;
    private int     resyncCount = 0;

    private double  lastTarget = Double.NaN, lastDetected = Double.NaN, lastErr = Double.NaN;

    private final List<Double> tuSamples = new ArrayList<>();
    private final List<Double> ppSamples = new ArrayList<>();
    private final List<String> warnings  = new ArrayList<>();

    // results (valid once DONE)
    private double ku = Double.NaN, tu = Double.NaN, aAmp = Double.NaN;
    private double kp = Double.NaN, ki = Double.NaN, kd = Double.NaN;

    // live waveform ring for the dialog plot (err and relay output)
    private final double[] ringErr = new double[RING];
    private final double[] ringOut = new double[RING];
    private int ringHead = 0, ringFill = 0;

    /** Builds a session that starts from {@code biasHz} (the current locked
     *  correction, frozen to hold the clock drift) using {@code rule}.
     *  Other knobs take robust defaults. */
    public FllAutotuneSession(double biasHz, Rule rule) {
        this(biasHz, rule, 12, 2, 4, 4.0, 3.0, 600);
    }

    public FllAutotuneSession(double biasHz, Rule rule,
                              int noiseSamples, int settleCycles, int measureCycles,
                              double epsSigma, double relayEps, int maxStallSamples) {
        this.biasHz          = biasHz;
        this.rule            = rule;
        this.noiseSamples    = Math.max(4, noiseSamples);
        this.settleCycles    = Math.max(0, settleCycles);
        this.measureCycles   = Math.max(1, measureCycles);
        this.epsSigma        = epsSigma;
        this.relayEps        = relayEps;
        this.maxStallSamples = maxStallSamples;
    }

    /** Feeds one FFT measurement and returns the generator correction (Hz)
     *  to publish this frame.  {@code fftSizeSamples} / {@code binHz} are
     *  the current window length (for the flip gate) and bin width (for the
     *  relay sizing).  During NOISE the bias is held; during the relay
     *  phases it is offset by ±d.  After DONE/FAILED it returns the bias. */
    public double process(double target, double detected, long absStartSamples,
                          int sampleRate, int fftSizeSamples, double binHz) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return biasHz;
        if (sampleRate <= 0 || !Double.isFinite(target) || !Double.isFinite(detected)) return biasHz;

        double t   = absStartSamples / (double) sampleRate;
        double err = detected - target;
        lastTarget = target; lastDetected = detected; lastErr = err;
        windowSec = fftSizeSamples > 0 ? fftSizeSamples / (double) sampleRate : 0.0;

        if (phase == Phase.NOISE) {
            // Discard the first full window: the install reset just snapped the
            // generator to the bin, so the window must refill before err is a
            // valid steady measurement (otherwise the transient inflates σ).
            if (!haveNoiseStart) { noiseStartT = t; haveNoiseStart = true; }
            if ((t - noiseStartT) < windowSec) { pushRing(err, 0.0); return biasHz; }
            noiseSum   += err;
            noiseSumSq += err * err;
            if (++noiseCount >= noiseSamples) {
                center = noiseSum / noiseCount;
                double var   = Math.max(0.0, noiseSumSq / noiseCount - center * center);
                double sigma = Math.sqrt(var);
                double bin   = binHz > 0 ? binHz : (sigma > 0 ? sigma : 1.0);
                epsHz = clamp(epsSigma * sigma, EPS_FLOOR_BINS * bin, EPS_MAX_BINS * bin);
                dHz   = clamp(relayEps * epsHz,  D_FLOOR_BINS  * bin, D_MAX_BINS  * bin);
                relaySign = (err - center) > 0 ? -1 : +1;
                phase = Phase.SETTLE;
                lastFlipT = t; lastCycleStartT = t; haveTime = true;
                resetCycleExtremes();
            }
            pushRing(err, 0.0);
            return biasHz;
        }

        // ---- SETTLE / MEASURE : window-gated hysteretic relay ----
        double e = err - center;
        cycErrMin = Math.min(cycErrMin, err);
        cycErrMax = Math.max(cycErrMax, err);

        boolean settled = (t - lastFlipT) >= windowSec;   // a full fresh window since the last flip
        boolean flipped = false;
        if (settled) {
            if (relaySign < 0 && e < -epsHz) {            // pushed down; crossed the lower band
                relaySign = +1; flipped = true;
            } else if (relaySign > 0 && e > epsHz) {      // pushed up; crossed the upper band
                relaySign = -1; flipped = true;
                onCycleComplete(t);                       // full cycle = (−1)→(+1)→(−1)
            }
        }
        if (flipped) { lastFlipT = t; stall = 0; }
        else if (++stall > maxStallSamples) fail("autotune.fail.nolimitcycle");

        if (!haveTime) { lastCycleStartT = t; lastFlipT = t; haveTime = true; }

        double u = relaySign * dHz;
        pushRing(err, u);
        return biasHz + u;
    }

    /** Records a capture re-sync (overrun / discontinuity).  The dither
     *  deliberately moves the tone, so a re-sync at the transition is
     *  expected: it is counted and surfaced but never taints a cycle or
     *  aborts the run (the post-re-sync detected is valid and the period is
     *  timed from sample-domain flip instants). */
    public void onResync(String messageKey) {
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        resyncCount++;
        if (messageKey != null) warnings.add(messageKey);
    }

    private void onCycleComplete(double t) {
        double period = t - lastCycleStartT;
        double pp     = cycErrMax - cycErrMin;
        lastCycleStartT = t;
        resetCycleExtremes();
        if (!(period > 0.0) || !Double.isFinite(pp)) return;

        cyclesSeen++;
        if (phase == Phase.SETTLE) {
            if (cyclesSeen >= settleCycles) { phase = Phase.MEASURE; cyclesSeen = 0; }
            return;
        }
        tuSamples.add(period);
        ppSamples.add(pp);
        if (tuSamples.size() >= measureCycles) finish();
    }

    private void finish() {
        tu = mean(tuSamples);
        aAmp = 0.5 * mean(ppSamples);
        double denom = Math.sqrt(Math.max(1e-12, aAmp * aAmp - epsHz * epsHz));
        ku = 4.0 * dHz / (Math.PI * denom);
        applyRule();
        phase = Phase.DONE;
    }

    private void applyRule() {
        if (rule == Rule.IMC) {
            // Deadtime-dominated plant: pure integral, λ-tuned for no overshoot.
            double theta  = 0.5 * tu;                       // deadtime ≈ Tu/2
            double k      = 4.0 / (Math.PI * ku);           // plant gain (relay DF)
            double lambda = IMC_LAMBDA * theta;             // desired closed-loop τ
            kp = 0.0;
            ki = (k > 0 && (lambda + theta) > 0) ? 1.0 / (k * (lambda + theta)) : 0.0;
            kd = 0.0;
            return;
        }
        double ti, td;
        switch (rule) {
            case PESSEN:         kp = 0.70 * ku; ti = tu / 2.5; td = 0.15 * tu;        break;
            case SOME_OVERSHOOT: kp = 0.33 * ku; ti = tu / 2.0; td = tu / 3.0;         break;
            case NO_OVERSHOOT:   kp = 0.20 * ku; ti = tu / 2.0; td = tu / 3.0;         break;
            case TYREUS_LUYBEN:  kp = 0.45 * ku; ti = 2.2 * tu; td = tu / 6.3;         break;
            case CLASSIC:
            default:             kp = 0.60 * ku; ti = tu / 2.0; td = tu / 8.0;         break;
        }
        ki = ti > 0 ? kp / ti : 0.0;
        kd = kp * td;
    }

    private void fail(String key) {
        phase = Phase.FAILED;
        failMessage = key;
        warnings.add(key);
    }

    private void resetCycleExtremes() {
        cycErrMin = Double.POSITIVE_INFINITY;
        cycErrMax = Double.NEGATIVE_INFINITY;
    }

    private void pushRing(double err, double out) {
        ringErr[ringHead] = err;
        ringOut[ringHead] = out;
        ringHead = (ringHead + 1) % RING;
        if (ringFill < RING) ringFill++;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double mean(List<Double> xs) {
        double s = 0.0; for (double x : xs) s += x; return xs.isEmpty() ? 0.0 : s / xs.size();
    }

    // ---------------------------------------------------------------- getters
    public Phase   getPhase()           { return phase; }
    public boolean isActive()           { return phase == Phase.NOISE || phase == Phase.SETTLE || phase == Phase.MEASURE; }
    public boolean isDone()             { return phase == Phase.DONE; }
    public boolean isFailed()           { return phase == Phase.FAILED; }
    public String  getFailMessage()     { return failMessage; }

    public double  getRelayAmpHz()      { return dHz; }
    public double  getHysteresisHz()    { return epsHz; }
    public double  getCenterHz()        { return center; }
    public int     getResyncCount()     { return resyncCount; }
    public List<String> getWarnings()   { return warnings; }

    public double  getMeasuredFrequencyHz() { return lastDetected; }
    public double  getLastTargetHz()        { return lastTarget; }
    public double  getLastErrHz()           { return lastErr; }

    public double  getMeasuredTuSeconds() { return tu; }
    public double  getAmplitudeHz()       { return aAmp; }
    public double  getUltimateGainKu()    { return ku; }
    public double  getKp()                { return kp; }
    public double  getKi()                { return ki; }
    public double  getKd()                { return kd; }
    public Rule    getRule()              { return rule; }

    /** Total limit cycles the run will observe (settle + measure). */
    public int getTargetCycles() { return settleCycles + measureCycles; }

    /** Cycles completed across SETTLE+MEASURE so far, for progress. */
    public int getCyclesObserved() {
        return phase == Phase.SETTLE ? cyclesSeen
             : phase == Phase.MEASURE ? settleCycles + tuSamples.size()
             : phase == Phase.DONE ? getTargetCycles() : 0;
    }

    /** 0..1 progress: ~10 % for the noise probe, the rest across the cycles. */
    public double getProgressFraction() {
        if (phase == Phase.DONE)   return 1.0;
        if (phase == Phase.FAILED) return 0.0;
        if (phase == Phase.NOISE)  return 0.10 * (noiseCount / (double) noiseSamples);
        int target = Math.max(1, getTargetCycles());
        return 0.10 + 0.90 * Math.min(1.0, getCyclesObserved() / (double) target);
    }

    /** Copies the most-recent err samples (oldest→newest) into {@code err}
     *  and the matching relay output into {@code out}; returns the count. */
    public int snapshotWaveform(double[] err, double[] out) {
        int n = Math.min(ringFill, Math.min(err.length, out.length));
        int start = (ringHead - n + RING) % RING;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % RING;
            err[i] = ringErr[idx];
            out[i] = ringOut[idx];
        }
        return n;
    }
}
