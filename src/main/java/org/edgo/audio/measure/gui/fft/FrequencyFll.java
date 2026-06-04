package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.gui.bus.Events;

/**
 * Gain-ramped, time-aware frequency-lock integrator that aligns the generator's
 * DDS frequency with the FFT bin grid by feeding back the per-frame error between
 * the commanded target and the FFT's refined fundamental estimate.
 *
 * <h2>Update law (time-aware)</h2>
 * <pre>
 *   dt   = (absStart − lastAbsStart) / sampleRate            // seconds
 *   k    = K_START + (K_END − K_START) · min(1, elapsed/RAMP_SECONDS)
 *   step = min(STEP_MAX, k · dt)                             // forward-Euler step
 *   correction ← correction − step · errEff
 * </pre>
 * Scaling the step by {@code dt} gives a fixed real-time convergence constant
 * (≈ 1/k s) regardless of tick rate — a 5.5 s first frame and a 0.34 s overlapped
 * frame integrate the same per second.  The gain ramps from a fast catch-up (5/s)
 * to a quiet steady state (0.5/s) over {@link #RAMP_SECONDS}.
 *
 * <h2>Transport-delay compensation</h2>
 * <p>A correction issued now does not appear fully in the measurement until the
 * analysis window has refreshed: at 0 % overlap that's the very next frame, at
 * 75 % overlap it takes ~4 frames, and after an overrun/re-sync (a long gap) it
 * has already fully arrived.  Without accounting for this the loop acts on a
 * half-propagated measurement and overshoots/oscillates (worst at large windows,
 * where the window time exceeds the loop time-constant).  So a Smith-predictor
 * model tracks how much of the correction the current measurement reflects
 * ({@code arrived}, a one-pole lag with time-constant = the window time
 * {@code fftSize/sampleRate}) and the loop acts on the FINAL error,
 * {@code (detected − target) + (correction − arrived)} — the in-flight,
 * not-yet-measured correction is folded in, so the loop stops integrating once it
 * has issued enough, instead of waiting for the slow window to catch up.  The
 * {@code dt/τ} ratio makes the per-frame arrival fraction track the overlap
 * automatically.
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  Called from the SWT UI thread (where
 * {@link Events#FFT_RESULT_AVAILABLE} is dispatched).
 */
public final class FrequencyFll implements FrequencyAligner {

    /** Per-second gain on Record start — a fast catch-up to the steady drift. */
    private static final double K_START      = 5.0;
    /** Per-second gain once converged — quiet, so per-frame noise barely perturbs the lock. */
    private static final double K_END        = 0.5;
    /** Seconds over which the gain ramps from {@link #K_START} to {@link #K_END}. */
    private static final double RAMP_SECONDS = 2.0;
    /** Upper bound on the per-update step {@code k·dt} — held under the forward-Euler
     *  stability bound (2.0) so a single long-gap frame can't over-correct. */
    private static final double STEP_MAX     = 0.6;

    private double  correction = 0.0;   // accumulated correction (Hz)
    private double  arrived    = 0.0;   // correction the current measurement reflects (transport-delay model)
    private double  elapsed    = 0.0;   // seconds of signal since reset() — drives the gain ramp
    private long    lastAbsStart;
    private boolean haveLast   = false;

    @Override
    public double getCorrection() {
        return correction;
    }

    /** Returns {@code true} once the gain ramp has fully converged. */
    public boolean isConverged() {
        return elapsed >= RAMP_SECONDS;
    }

    @Override
    public void update(double target, double detected, long absStartSamples, int sampleRate, int fftSize) {
        if (!Double.isFinite(target) || !Double.isFinite(detected) || sampleRate <= 0) {
            return;
        }
        if (!haveLast) {                       // first frame: establish the dt reference only
            lastAbsStart = absStartSamples;
            haveLast = true;
            return;
        }
        double dt = (absStartSamples - lastAbsStart) / (double) sampleRate;
        lastAbsStart = absStartSamples;
        if (!(dt > 0.0)) {
            return;                            // no signal time advanced — nothing to integrate
        }

        // Transport-delay model: advance how much of `correction` the measurement
        // reflects.  τ = window time; the per-frame fraction dt/τ tracks the overlap
        // (0 % → 1 frame, 75 % → ~4, a long gap → fully arrived).
        double tau   = (fftSize > 0) ? (double) fftSize / sampleRate : dt;
        arrived     += Math.min(1.0, dt / Math.max(tau, dt)) * (correction - arrived);
        double inFlight = correction - arrived;            // issued but not yet measured
        double errEff   = (detected - target) + inFlight;  // act on the FINAL (post-arrival) error

        double k    = K_START + (K_END - K_START) * Math.min(1.0, elapsed / RAMP_SECONDS);
        double step = Math.min(STEP_MAX, k * dt);
        correction -= step * errEff;
        elapsed    += dt;
    }

    @Override
    public void reset() {
        correction = 0.0;
        arrived    = 0.0;
        elapsed    = 0.0;
        haveLast   = false;
    }
}
