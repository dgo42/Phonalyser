package org.edgo.audio.measure.gui.fft;

/**
 * A closed-loop frequency aligner that steers the generator's commanded
 * frequency onto the FFT bin grid by feeding back the per-frame error between
 * the commanded {@code target} and the FFT's refined {@code detected} estimate.
 *
 * <p>Two implementations: {@link FrequencyPid} (PID) and {@link FrequencyFll}
 * (gain-ramped integrator).  The view holds one per tone of the selected type
 * and adds {@link #getCorrection()} to the snap target before publishing the
 * trim.  Not synchronized — called from the SWT UI thread.
 */
public interface FrequencyAligner {

    /** Feeds one FFT measurement in and updates the correction.
     *  {@code absStartSamples}/{@code sampleRate} give the window's absolute
     *  position so the real {@code dt} between corrections is known; {@code fftSize}
     *  is the analysis window length (the measurement's transport delay).  Non-finite
     *  inputs and the first call after a reset are ignored. */
    void update(double target, double detected, long absStartSamples, int sampleRate, int fftSize);

    /** Current correction in Hz — add to the snap target before publishing the trim. */
    double getCorrection();

    /** Zeroes the loop state.  Call on Record stop and on user-initiated
     *  generator-frequency / FFT-length changes (both invalidate the lock). */
    void reset();
}
