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
 * <h2>Update law</h2>
 * <p>An exponentially-weighted integrator with a ramping smoothing
 * factor:
 * <pre>
 *   α = ALPHA_START + (ALPHA_END − ALPHA_START) · min(1, n / RAMP_FRAMES)
 *   correction ← correction − (1 − α) · (detected − target)
 *   n++
 * </pre>
 * Early frames apply a large step (~50 % of the observed error) so the
 * loop catches up to the steady-state drift quickly; later frames apply
 * tiny corrections (~5 % of error) so single-frame noise doesn't yank
 * the lock.  After {@code RAMP_FRAMES} the gain is constant.
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  All methods are intended to be called from the
 * SWT UI thread (where {@link Events#FFT_RESULT_AVAILABLE} is
 * dispatched).
 */
public final class FrequencyLockLoop {

    /** Initial smoothing factor — first frame applies (1−α) = 50 % of
     *  the observed error to the correction, so the loop catches up
     *  fast on Record start. */
    private static final double ALPHA_START = 0.5;
    /** Final smoothing factor — converged loop applies (1−α) = 5 %
     *  of per-frame error, so jitter and noise don't perturb the
     *  locked correction. */
    private static final double ALPHA_END   = 0.95;
    /** Number of frames over which α ramps from {@link #ALPHA_START}
     *  to {@link #ALPHA_END}.  At ~10 FFT frames/sec the ramp settles
     *  in ~2 seconds — long enough for a clean lock, short enough that
     *  the user doesn't notice a drawn-out transient. */
    private static final int    RAMP_FRAMES = 20;

    /** Accumulated correction in Hz — added to the commanded target
     *  frequency before sending to the DDS.  Survives across FFT
     *  results inside a single Record session and resets on Record
     *  stop / user-initiated frequency change. */
    private double correction = 0.0;
    /** Number of {@link #update(double, double)} calls since the last
     *  {@link #reset()} — drives the α ramp. */
    private int    frameCount = 0;

    /** Current correction in Hz.  Add to the snap target before
     *  publishing to {@link Events#GENERATOR_FREQ_TRIM}. */
    public double getCorrection() {
        return correction;
    }

    /** Returns {@code true} once the ramp has fully converged.  Callers
     *  can use this to enable steady-state-only behaviours (e.g.
     *  refusing to update if {@code |err|} stays above some bound after
     *  this point would indicate the loop has lost lock). */
    public boolean isConverged() {
        return frameCount >= RAMP_FRAMES;
    }

    /** Feeds one FFT measurement into the loop and updates the
     *  correction.  {@code target} is the frequency the generator was
     *  commanded to produce; {@code detected} is the FFT's refined
     *  fundamental estimate (e.g. {@code Result.fundamentalHzRefined}).
     *  Skipped silently when either is non-finite — keeps a glitched
     *  frame from corrupting the integrator. */
    public void update(double target, double detected) {
        if (!Double.isFinite(target) || !Double.isFinite(detected)) return;
        double err = detected - target;
        double alpha = ALPHA_START
                + (ALPHA_END - ALPHA_START) * Math.min(1.0, frameCount / (double) RAMP_FRAMES);
        correction -= (1.0 - alpha) * err;
        frameCount++;
    }

    /** Zeroes the correction and restarts the α ramp.  Call on Record
     *  stop and on every user-initiated generator-frequency / FFT-length
     *  change — both invalidate the locked offset. */
    public void reset() {
        correction = 0.0;
        frameCount = 0;
    }
}
