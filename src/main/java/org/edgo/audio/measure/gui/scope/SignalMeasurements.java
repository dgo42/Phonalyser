package org.edgo.audio.measure.gui.scope;

import lombok.Getter;

/**
 * Basic oscilloscope-style signal measurements computed from a buffer of
 * normalised samples (range {@code [-1, +1]} in raw ADC units, scaled to
 * volts by {@code peakVolts}).
 *
 * <p>Detection strategy: a fast pass over the buffer computes min / max /
 * mean / RMS and counts rising-edge crossings of the DC mean.  The two
 * outermost crossings span an integer number of cycles, which gives a
 * coarse period estimate — that estimate is then refined by scanning a
 * narrow band of Goertzel DFTs (single-bin DFTs) around it for the peak
 * magnitude.  Goertzel is O(N) per probe and lets us pin the frequency to
 * a fraction of a Hz without an FFT.
 *
 * <p>Returned {@code period}, {@code frequency} and {@code dutyCycle} are
 * {@link Double#NaN} when the buffer doesn't contain at least one full
 * cycle (need ≥ 2 same-direction zero crossings to span a period).
 */
@Getter
final class SignalMeasurements {

    private final double vpp;        // volts peak-to-peak
    private final double vrms;       // volts RMS (including DC offset)
    private final double vmean;      // DC offset in volts
    private final double period;     // seconds (NaN if unknown)
    private final double riseTime;   // seconds, 10 % → 90 % on rising edges (NaN if unknown)
    private final double fallTime;   // seconds, 90 % → 10 % on falling edges (NaN if unknown)
    private final double frequency;  // Hz (NaN if unknown)
    private final double dutyCycle;  // fraction [0, 1] (NaN if unknown)

    private SignalMeasurements(double vpp, double vrms, double vmean,
                               double period, double riseTime, double fallTime,
                               double frequency, double dutyCycle) {
        this.vpp = vpp;
        this.vrms = vrms;
        this.vmean = vmean;
        this.period = period;
        this.riseTime = riseTime;
        this.fallTime = fallTime;
        this.frequency = frequency;
        this.dutyCycle = dutyCycle;
    }

    /**
     * Computes measurements over the first {@code n} samples of {@code data}.
     * {@code peakVolts} converts a normalised sample of ±1.0 into the full-scale
     * ADC voltage swing — i.e. {@code adcFsVoltageRms · √2}.
     */
    static SignalMeasurements compute(float[] data, int n, double sampleRate, double peakVolts) {
        if (n < 4) {
            return new SignalMeasurements(0, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double sum = 0;
        double sumSq = 0;
        float min = data[0];
        float max = data[0];
        for (int i = 0; i < n; i++) {
            float v = data[i];
            sum += v;
            sumSq += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / n;
        // AC RMS (= signal RMS after removing the DC bias).  Total RMS
        // including DC makes Vrms degenerate to ≈ Vmean when the signal
        // is a DC-biased noise, which is much less useful for an
        // oscilloscope readout.  variance = E[X²] − E[X]².
        double variance = sumSq / n - mean * mean;
        double rms = Math.sqrt(Math.max(0.0, variance));
        double vpp = (max - min) * peakVolts;
        double vmean = mean * peakVolts;
        double vrms = rms * peakVolts;

        // Half-amplitude midpoint of the signal — used as the crossing
        // threshold for both period detection and duty cycle.  Independent
        // of any DC bias on the input, so DC-coupled and AC-coupled inputs
        // yield the same answers.
        float threshold = (min + max) * 0.5f;
        int firstCross = -1;
        int lastCross  = -1;
        int crossCount = 0;
        int highCount  = 0;
        for (int i = 1; i < n; i++) {
            if (data[i] >= threshold) highCount++;
            if (data[i - 1] < threshold && data[i] >= threshold) {
                if (firstCross < 0) firstCross = i;
                lastCross = i;
                crossCount++;
            }
        }

        // Rise/fall time: average over all complete 10 %↔90 % transitions
        // in the buffer.  Bracket-based — track when the signal first
        // crosses the 10 % threshold going up and when it then crosses the
        // 90 % threshold (same direction), and vice versa for the falling
        // edge.  Linear interpolation gives sub-sample resolution.
        double riseTime = Double.NaN;
        double fallTime = Double.NaN;
        if (max - min > 1e-6f) {
            float lo = min + 0.1f * (max - min);
            float hi = min + 0.9f * (max - min);
            double pendingLo = -1;
            double pendingHi = -1;
            double riseSum = 0, fallSum = 0;
            int    riseCount = 0, fallCount = 0;
            for (int i = 1; i < n; i++) {
                float prev = data[i - 1];
                float v    = data[i];
                if (prev < lo && v >= lo) {
                    pendingLo = (i - 1) + (lo - prev) / (v - prev);
                }
                if (prev < hi && v >= hi && pendingLo >= 0) {
                    double hiT = (i - 1) + (hi - prev) / (v - prev);
                    riseSum += (hiT - pendingLo);
                    riseCount++;
                    pendingLo = -1;
                }
                if (prev > hi && v <= hi) {
                    pendingHi = (i - 1) + (prev - hi) / (prev - v);
                }
                if (prev > lo && v <= lo && pendingHi >= 0) {
                    double loT = (i - 1) + (prev - lo) / (prev - v);
                    fallSum += (loT - pendingHi);
                    fallCount++;
                    pendingHi = -1;
                }
            }
            if (riseCount > 0) riseTime = (riseSum / riseCount) / sampleRate;
            if (fallCount > 0) fallTime = (fallSum / fallCount) / sampleRate;
        }

        double period = Double.NaN;
        double frequency = Double.NaN;
        double duty = Double.NaN;
        if (crossCount >= 2) {
            // Primary: period from rising-edge spacing of the half-amplitude
            // threshold, then Goertzel refinement around that estimate.
            // Crossing-derived periods track the true fundamental directly,
            // so a narrow-duty rectangle yields the right answer instead of
            // locking onto whichever of its near-equal-amplitude harmonics
            // a pure DFT peak-pick happens to find strongest.
            double coarsePeriod = (double)(lastCross - firstCross) / (crossCount - 1);
            double coarseFreq = sampleRate / coarsePeriod;
            double refinedA = refineAroundEstimate(data, n, sampleRate, coarseFreq, mean,
                                                    coarseFreq * 0.25);
            double magA = goertzelMagnitude(data, n, sampleRate, refinedA, mean);
            double rmsAc = Math.sqrt(Math.max(0, variance));
            double qA = (rmsAc > 1e-12) ? magA / n / rmsAc : 0;

            double bestFreq = refinedA;
            double bestQ = qA;
            // Fallback: broad-band scan over the whole audio band, used only
            // when crossings look unreliable.  At SNRs below ~10 dB noise
            // randomises the crossing count and the crossing-derived period
            // is wildly off; the broad-band scan still finds the spectral
            // peak in that regime.
            if (qA < 0.1) {
                double refinedB = scanForFundamental(data, n, sampleRate, mean);
                if (refinedB > 0) {
                    double magB = goertzelMagnitude(data, n, sampleRate, refinedB, mean);
                    double qB = (rmsAc > 1e-12) ? magB / n / rmsAc : 0;
                    if (qB > qA) {
                        bestFreq = refinedB;
                        bestQ = qB;
                    }
                }
            }

            // Quality gate: pure sine ratio ≈ 1/√2 ≈ 0.707; pure white noise
            // drops to ~0.005 at n = 48000.  Narrow-duty rectangles
            // concentrate energy in harmonics, dropping the ratio to ~0.23
            // at D = 5 % / ~0.10 at D = 1 %.  Threshold of 0.1 admits
            // rectangles down to ~1 % / up to ~99 % duty.
            if (bestQ >= 0.1 && bestFreq > 0) {
                frequency = bestFreq;
                period = 1.0 / bestFreq;
                duty = (double) highCount / n;
            }
        }
        return new SignalMeasurements(vpp, vrms, vmean, period, riseTime, fallTime, frequency, duty);
    }

    /**
     * Three-stage Goertzel refinement centred on {@code estimate}: each of
     * three successive scans shrinks the step by ~20× from the previous, and
     * a final parabolic interpolation across three adjacent fine bins gives
     * sub-step precision (typically ≤ 0.005 Hz on a one-second buffer).
     * Total cost is a fixed ≈ 180 Goertzel evaluations regardless of the
     * starting {@code halfWidth}, so the call is cheap enough to invoke once
     * per measurement update.
     */
    private static double refineAroundEstimate(float[] data, int n, double sampleRate,
                                                double estimate, double mean,
                                                double halfWidth) {
        int stage1Probes = 100;
        double stage1Step = halfWidth * 2.0 / stage1Probes;
        double bestFreq = estimate;
        double bestMag  = goertzelMagnitude(data, n, sampleRate, estimate, mean);
        for (int k = -stage1Probes / 2; k <= stage1Probes / 2; k++) {
            if (k == 0) continue;
            double f = estimate + k * stage1Step;
            if (f <= 0 || f >= sampleRate / 2.0) continue;
            double mag = goertzelMagnitude(data, n, sampleRate, f, mean);
            if (mag > bestMag) { bestMag = mag; bestFreq = f; }
        }

        double stage2Step = stage1Step / 20.0;
        int stage2Probes = 40;
        for (int k = -stage2Probes / 2; k <= stage2Probes / 2; k++) {
            if (k == 0) continue;
            double f = bestFreq + k * stage2Step;
            if (f <= 0 || f >= sampleRate / 2.0) continue;
            double mag = goertzelMagnitude(data, n, sampleRate, f, mean);
            if (mag > bestMag) { bestMag = mag; bestFreq = f; }
        }

        double stage3Step = stage2Step / 20.0;
        int stage3Probes = 40;
        for (int k = -stage3Probes / 2; k <= stage3Probes / 2; k++) {
            if (k == 0) continue;
            double f = bestFreq + k * stage3Step;
            if (f <= 0 || f >= sampleRate / 2.0) continue;
            double mag = goertzelMagnitude(data, n, sampleRate, f, mean);
            if (mag > bestMag) { bestMag = mag; bestFreq = f; }
        }

        // Parabolic interpolation across three adjacent stage-3 bins.  For a
        // near-sinusoidal Goertzel response the spectral lobe is approximately
        // parabolic at its peak; skipped when the bracket lies outside the
        // band or the three samples don't form a downward-opening parabola.
        double leftF  = bestFreq - stage3Step;
        double rightF = bestFreq + stage3Step;
        if (leftF > 0 && rightF < sampleRate / 2.0) {
            double leftMag  = goertzelMagnitude(data, n, sampleRate, leftF,  mean);
            double rightMag = goertzelMagnitude(data, n, sampleRate, rightF, mean);
            double denom = leftMag - 2.0 * bestMag + rightMag;
            if (denom < 0) {
                double delta = 0.5 * (leftMag - rightMag) / denom;
                if (delta > -1.0 && delta < 1.0) {
                    return bestFreq + delta * stage3Step;
                }
            }
        }
        return bestFreq;
    }

    /**
     * Broad-band Goertzel scan over the full audio band as a fallback for
     * the crossing-based primary path: a coarse pass over a 0.1 s sub-window
     * sweeps the band at its natural FFT-bin resolution; the coarse peak is
     * then passed to {@link #refineAroundEstimate} for fine refinement and
     * sub-bin interpolation.  Used only at SNRs where crossings have been
     * corrupted by noise; for clean signals the crossing-based primary path
     * locks onto the true fundamental and this scan is skipped entirely.
     */
    private static double scanForFundamental(float[] data, int n, double sampleRate, double mean) {
        int coarseN = Math.min(n, (int) (sampleRate * 0.1));        // 0.1 s sub-window
        if (coarseN < 64) coarseN = n;
        double coarseBinHz = sampleRate / (double) coarseN;          // = 10 Hz at 48 kHz
        double maxScanFreq = sampleRate / 4.0;
        double minScanFreq = Math.max(5.0, coarseBinHz);

        double bestFreq = -1, bestMag = -1;
        for (double f = minScanFreq; f < maxScanFreq; f += coarseBinHz) {
            double mag = goertzelMagnitude(data, coarseN, sampleRate, f, mean);
            if (mag > bestMag) { bestMag = mag; bestFreq = f; }
        }
        if (bestFreq <= 0) return -1.0;

        return refineAroundEstimate(data, n, sampleRate, bestFreq, mean, coarseBinHz * 2.0);
    }

    /**
     * Goertzel single-bin DFT magnitude at {@code freq}.  Subtracts the DC
     * mean so the result reflects only the AC component at that frequency.
     */
    private static double goertzelMagnitude(float[] data, int n, double sampleRate,
                                             double freq, double mean) {
        double omega = 2 * Math.PI * freq / sampleRate;
        double coeff = 2 * Math.cos(omega);
        double q1 = 0, q2 = 0;
        for (int i = 0; i < n; i++) {
            double q0 = (data[i] - mean) + coeff * q1 - q2;
            q2 = q1;
            q1 = q0;
        }
        return Math.sqrt(q1 * q1 + q2 * q2 - q1 * q2 * coeff);
    }

    private SignalMeasurements() { this(0, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN); }

    /** Returns a copy of this measurement with every time-domain field
     *  set to {@link Double#NaN} (period, rise time, fall time,
     *  frequency, duty cycle).  Used by the scope worker for dual-tone
     *  signals — period / frequency / duty have no meaningful
     *  single-value answer for two simultaneous tones, so the readout
     *  table renders {@code ---} ({@link MeasurementRow}'s NaN
     *  formatting) instead of latching onto an arbitrary value.
     *  Vpp / Vrms / Vmean stay valid since they're meaningful in
     *  every signal mode. */
    SignalMeasurements withoutTimes() {
        return new SignalMeasurements(vpp, vrms, vmean,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }
}
