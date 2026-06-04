package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.gui.bus.Events;

/**
 * Closed-loop PID controller that aligns the generator's DDS frequency
 * with the FFT bin grid by feeding back the per-frame error between the
 * commanded target frequency and the FFT's refined fundamental estimate.
 *
 * <p>The error signal is the residual frequency drift between the DDS
 * oscillator and the capture clock — for typical USB audio interfaces a
 * stable offset on the order of 1 ppm.  The plant is essentially unity
 * gain with a measurement lag, so the integral term carries the
 * steady-state offset (zero-error lock), the proportional term gives the
 * fast catch-up on Record start, and the derivative term adds damping.
 *
 * <h2>Update law (time-aware PID)</h2>
 * <p>Sampled at the (variable) interval {@code dt} between corrections,
 * derived from the analysis-window sample positions so it tracks REAL
 * elapsed time — not frame count:
 * <pre>
 *   dt        = (absStart − lastAbsStart) / sampleRate              // seconds
 *   err       = detected − target
 *   integral += err · dt                                           // anti-wound
 *   deriv     = (err − prevErr) / max(dt, DT_MIN)
 *   out       = Kp·err + Ki·integral + Kd·deriv
 *   correction = −clamp(out, ±CORRECTION_MAX_HZ)
 * </pre>
 * The integral is wound only within {@code ±CORRECTION_MAX_HZ/Ki}
 * (anti-windup) and the output is clamped, so a long gap (overrun /
 * discontinuity → a large {@code dt}) can't wind up or diverge.  Kp/Ki/Kd
 * are settable (loaded from Preferences, written by the autotune wizard).
 *
 * <h2>Threading</h2>
 * <p>Not synchronized.  All methods are intended to be called from the
 * SWT UI thread (where {@link Events#FFT_RESULT_AVAILABLE} is
 * dispatched).
 */
public final class FrequencyPid implements FrequencyAligner {

    /** Output saturation (Hz).  The lock only ever needs ~ppm·target
     *  (sub-Hz for audio), so this is a generous safety/anti-windup bound,
     *  not a normal operating limit. */
    private static final double CORRECTION_MAX_HZ = 5.0;
    /** Floor (s) on the derivative's {@code dt} so a fast tick can't blow
     *  up {@code Δerr/dt}. */
    private static final double DT_MIN = 0.05;

    /** Proportional / integral (per second) / derivative (seconds) gains.
     *  Defaults approximate the proven time-aware integrator (P halves the
     *  error immediately, I nulls the rest in ~1/Ki s); the autotune
     *  wizard overwrites them. */
    private double kp = 0.5;
    private double ki = 0.5;
    private double kd = 0.0;

    private double  integral   = 0.0;   // ∫err·dt  (anti-wound)
    private double  prevErr     = 0.0;
    private boolean havePrev    = false;
    private double  correction  = 0.0;  // cached controller output (Hz)
    private long    lastAbsStart;
    private boolean haveLast    = false;

    /** Sets the PID gains (Kp, Ki [1/s], Kd [s]).  Loaded from Preferences
     *  on Record start and updated by the autotune wizard. */
    public void setGains(double kp, double ki, double kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }

    public double getKp() { return kp; }
    public double getKi() { return ki; }
    public double getKd() { return kd; }

    /** Current correction in Hz.  Add to the snap target before publishing
     *  to {@link Events#GENERATOR_FREQ_TRIM}. */
    @Override
    public double getCorrection() {
        return correction;
    }

    /** Feeds one FFT measurement into the loop and updates the correction.
     *  {@code target} is the frequency the generator was commanded to
     *  produce; {@code detected} is the FFT's refined fundamental estimate;
     *  {@code absStartSamples} / {@code sampleRate} give the window's
     *  absolute position so the real {@code dt} between corrections can be
     *  computed.  Skipped silently for non-finite inputs (a glitched frame
     *  must not corrupt the integrator) and for the first call after a reset
     *  (which only establishes the time reference). */
    @Override
    public void update(double target, double detected, long absStartSamples, int sampleRate, int fftSize) {
        if (!Double.isFinite(target) || !Double.isFinite(detected) || sampleRate <= 0) return;
        if (!haveLast) {                       // first frame: establish the dt reference only
            lastAbsStart = absStartSamples;
            haveLast = true;
            return;
        }
        double dt = (absStartSamples - lastAbsStart) / (double) sampleRate;
        lastAbsStart = absStartSamples;
        if (!(dt > 0.0)) return;               // no signal time advanced — nothing to integrate

        double err = detected - target;
        integral += err * dt;
        if (ki > 0.0) {                        // anti-windup: keep Ki·integral within the output bound
            double iMax = CORRECTION_MAX_HZ / ki;
            integral = Math.max(-iMax, Math.min(iMax, integral));
        }
        double deriv = havePrev ? (err - prevErr) / Math.max(dt, DT_MIN) : 0.0;
        prevErr  = err;
        havePrev = true;

        double out = kp * err + ki * integral + kd * deriv;
        correction = -Math.max(-CORRECTION_MAX_HZ, Math.min(CORRECTION_MAX_HZ, out));
    }

    /** Zeroes the controller state (correction, integral, time reference).
     *  Call on Record stop and on every user-initiated generator-frequency /
     *  FFT-length change — both invalidate the locked offset.  Keeps the
     *  gains. */
    @Override
    public void reset() {
        correction = 0.0;
        integral   = 0.0;
        prevErr    = 0.0;
        havePrev   = false;
        haveLast   = false;
    }
}
