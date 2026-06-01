package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.gui.bus.Events;

/**
 * Closed-loop integrator that aligns the generator's DDS frequency with
 * the FFT bin grid by feeding back the per-frame error between the
 * commanded target frequency and the FFT's refined fundamental
 * estimate.
 *
 * <p>The error signal is the residual frequency drift between the DDS
 * oscillator and the capture clock — for typical USB audio interfaces
 * this is a stable offset on the order of 1 ppm.  Once locked the
 * fundamental's energy concentrates in a single bin and harmonic /
 * power readings become spectral-leakage-free.
 *
 * <h2>Update law (time-aware)</h2>
 * <p>A continuous-time integrator sampled at the (variable) interval
 * {@code dt} between corrections, derived from the analysis-window sample
 * positions so it tracks REAL elapsed time — not frame count:
 * <pre>
 *   dt   = (absStart − lastAbsStart) / sampleRate            // seconds
 *   k    = K_START + (K_END − K_START) · min(1, elapsed/RAMP_SECONDS)
 *   step = min(STEP_MAX, k · dt)                             // forward-Euler step
 *   correction ← correction − step · (detected − target)
 *   elapsed += dt
 * </pre>
 * Scaling the step by {@code dt} gives the loop a fixed real-time
 * convergence constant (≈ 1/k seconds) regardless of how long each tick
 * takes — a 5.5 s first frame and a 0.34 s overlapped frame integrate the
 * same amount per second.  {@code STEP_MAX} caps the forward-Euler step
 * well below the divergence bound (2.0), so a long gap (overrun /
 * discontinuity) can't push the loop unstable.  The gain ramps from a fast
 * catch-up (~5/s) to a quiet steady state (~0.5/s) over the first
 * {@code RAMP_SECONDS}.
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  All methods are intended to be called from the
 * SWT UI thread (where {@link Events#FFT_RESULT_AVAILABLE} is
 * dispatched).
 */
public final class FrequencyLockLoop {

    /** Per-second gain on Record start — a fast catch-up to the steady
     *  drift (≈ 1/5 s convergence). */
    private static final double K_START = 5.0;
    /** Per-second gain once converged — quiet, so per-frame measurement
     *  noise barely perturbs the lock (≈ 2 s convergence). */
    private static final double K_END   = 0.5;
    /** Seconds over which the gain ramps from {@link #K_START} to
     *  {@link #K_END}.  Time-based (not frame-based) so the transient lasts
     *  ~2 s at any tick rate. */
    private static final double RAMP_SECONDS = 2.0;
    /** Upper bound on the per-update step {@code k·dt} — the fraction of the
     *  observed error folded into the correction in one update.  Held well
     *  under the forward-Euler stability bound of 2.0 so a single long-gap
     *  frame (huge {@code dt}) can't over-correct or diverge. */
    private static final double STEP_MAX = 0.6;

    /** Accumulated correction in Hz — added to the commanded target
     *  frequency before sending to the DDS.  Survives across FFT results
     *  inside a single Record session and resets on Record stop / user
     *  frequency change. */
    private double  correction = 0.0;
    /** Seconds of signal elapsed since the last {@link #reset()} — drives
     *  the gain ramp. */
    private double  elapsed    = 0.0;
    /** Absolute sample index of the previous update's window start, for the
     *  {@code dt} between corrections.  {@link #haveLast} guards the first
     *  update (no prior reference yet). */
    private long    lastAbsStart;
    private boolean haveLast   = false;

    /** Current correction in Hz.  Add to the snap target before publishing
     *  to {@link Events#GENERATOR_FREQ_TRIM}. */
    public double getCorrection() {
        return correction;
    }

    /** Returns {@code true} once the gain ramp has fully converged. */
    public boolean isConverged() {
        return elapsed >= RAMP_SECONDS;
    }

    /** Feeds one FFT measurement into the loop and updates the correction.
     *  {@code target} is the frequency the generator was commanded to
     *  produce; {@code detected} is the FFT's refined fundamental estimate;
     *  {@code absStartSamples} / {@code sampleRate} give the window's
     *  absolute position so the real {@code dt} between corrections can be
     *  computed.  Skipped silently for non-finite inputs (a glitched frame
     *  must not corrupt the integrator) and for the first call after a reset
     *  (which only establishes the time reference). */
    public void update(double target, double detected, long absStartSamples, int sampleRate) {
        if (!Double.isFinite(target) || !Double.isFinite(detected) || sampleRate <= 0) return;
        if (!haveLast) {                       // first frame: establish the dt reference only
            lastAbsStart = absStartSamples;
            haveLast = true;
            return;
        }
        double dt = (absStartSamples - lastAbsStart) / (double) sampleRate;
        lastAbsStart = absStartSamples;
        if (!(dt > 0.0)) return;               // no signal time advanced — nothing to integrate
        double err  = detected - target;
        double k    = K_START + (K_END - K_START) * Math.min(1.0, elapsed / RAMP_SECONDS);
        double step = Math.min(STEP_MAX, k * dt);
        correction -= step * err;
        elapsed    += dt;
    }

    /** Zeroes the correction and restarts the gain ramp + time reference.
     *  Call on Record stop and on every user-initiated generator-frequency /
     *  FFT-length change — both invalidate the locked offset. */
    public void reset() {
        correction = 0.0;
        elapsed    = 0.0;
        haveLast   = false;
    }
}
