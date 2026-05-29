package org.edgo.audio.measure.dsp;

import java.util.Arrays;

/**
 * Mains-hum rejection via a frequency-tracked IIR comb.
 *
 * <p>A single feedback comb
 * <pre>
 *   H(z) = (1 − z^−N) / (1 − α · z^−N),     N = sampleRate / f₀
 * </pre>
 * places a notch at DC and at every harmonic of {@code f₀}
 * (f₀, 2f₀, 3f₀, … up to Nyquist) in one structure — the natural fit
 * for mains interference, whose energy sits at 50/60 Hz and its integer
 * harmonics.  {@code α} (0 &lt; α &lt; 1) sets how sharp each notch is;
 * the −3 dB width is uniform across all harmonics and is derived from a
 * caller-supplied bandwidth in Hz.
 *
 * <h2>Frequency tracking</h2>
 * <p>Mains drifts (±0.1–0.5 Hz), and the k-th harmonic drifts k× as far
 * in Hz, so a fixed comb loses depth on the high harmonics.  {@link
 * #track(float[], int)} measures the true mains fundamental from a
 * reference block — auto-detecting whether the source is 50 Hz or 60 Hz
 * — and re-tunes the whole comb by adjusting {@code N}.  Because every
 * notch is locked to {@code k·f₀}, one estimate repositions the entire
 * comb: tracking {@code f₀} to a few mHz keeps even the high harmonics
 * in their notches.
 *
 * <p>{@code N} is generally fractional, so the {@code z^−N} delays use
 * linear interpolation between the two straddling integer taps.  Linear
 * interpolation slightly reduces notch depth at the highest harmonics
 * (its mild treble droop mistunes them a touch); for mains — where the
 * significant harmonics are below ~1 kHz — this is negligible.  Swap in
 * a first-order all-pass fractional delay if deep rejection near Nyquist
 * is ever needed.
 *
 * <h2>Usage</h2>
 * <pre>
 *   MainsCombFilter f = new MainsCombFilter(48000, 2.0);   // 2 Hz notches
 *   f.track(referenceBlock, refLen);                       // lock to 50/60
 *   f.process(audioBlock, len);                            // filter in place
 * </pre>
 * Typical streaming use re-tracks occasionally (mains drift is slow) and
 * processes every block; the comb's delay-line state persists across
 * {@code process} calls.  Not thread-safe — drive it from one thread.
 */
public final class MainsCombFilter {

    /** Lowest fundamental the comb will tune to (Hz) — also sizes the
     *  delay line.  Below the 50 Hz detection band with margin. */
    private static final double MIN_MAINS_HZ = 45.0;
    /** Highest fundamental the comb will tune to (Hz). */
    private static final double MAX_MAINS_HZ = 65.0;
    /** Half-width (Hz) of each detection band scanned around 50 / 60. */
    private static final double DETECT_SPAN_HZ = 2.0;
    /** Coarse scan step (Hz) inside each detection band. */
    private static final double DETECT_STEP_HZ = 0.2;
    /** A mains line is accepted only when its Goertzel power beats the
     *  scanned-baseline median by this factor (≈ 6 dB). */
    private static final double LOCK_RATIO = 4.0;

    private final int    sampleRate;
    /** Per-sample-delay pole radius ρ = exp(−π·BW/fs); α = ρ^N. */
    private final double rho;

    /** x and y delay lines (circular).  Length covers the largest N
     *  (lowest f₀) plus the two interpolation taps. */
    private final double[] xBuf;
    private final double[] yBuf;
    private final int      bufLen;
    private int            pos;          // next write index

    private double mainsHz = Double.NaN; // current tuning (NaN until tuned)
    private int    nInt;                 // floor(N)
    private double nFrac;                // N − floor(N)
    private double alpha;                // ρ^N

    /** Exponentially-smoothed lock frequency.  Each detection is blended
     *  in (mains drifts only ±0.1–0.5 Hz, so a stable average resists the
     *  per-window jitter / occasional mis-lock that was dropping the lock);
     *  detections that jump more than {@link #MAX_LOCK_DRIFT_HZ} from the
     *  current lock are rejected as outliers, and a window with no detected
     *  line holds the existing lock rather than dropping it.  Survives
     *  {@link #reset} (only the delay lines are cleared there). */
    private double lockHz = Double.NaN;
    /** EWMA weight on the existing lock (vs. a fresh detection). */
    private static final double LOCK_SMOOTH = 0.85;
    /** A detection farther than this from the current lock is an outlier. */
    private static final double MAX_LOCK_DRIFT_HZ = 1.5;

    /** Scratch for the windowed reference inside {@link #track}; grown
     *  on demand to the largest reference length seen. */
    private double[] windowScratch = new double[0];

    /**
     * @param sampleRate  capture sample rate (Hz)
     * @param notchBandwidthHz  −3 dB width of each harmonic notch (Hz);
     *        smaller = deeper/narrower (less damage to nearby signal,
     *        but relies on accurate tracking).  Typical 1–4 Hz.
     */
    public MainsCombFilter(int sampleRate, double notchBandwidthHz) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        if (!(notchBandwidthHz > 0)) throw new IllegalArgumentException("bandwidth must be > 0");
        this.sampleRate  = sampleRate;
        this.rho = Math.exp(-Math.PI * notchBandwidthHz / sampleRate);
        // Largest N is at the lowest tunable mains frequency.
        int nMax = (int) Math.ceil(sampleRate / MIN_MAINS_HZ);
        this.bufLen = nMax + 4;
        this.xBuf = new double[bufLen];
        this.yBuf = new double[bufLen];
    }

    /** Current comb fundamental in Hz, or {@code NaN} before the first
     *  successful {@link #track}/{@link #retune}. */
    public double getMainsHz() {
        return mainsHz;
    }

    /** True once the comb has been tuned to a mains frequency. */
    public boolean isTuned() {
        return !Double.isNaN(mainsHz);
    }

    /** Directly tunes the comb fundamental, bypassing detection.  The
     *  frequency is clamped to {@code [MIN_MAINS_HZ, MAX_MAINS_HZ]}.
     *  Delay-line state is preserved (a re-tune is a smooth glide, not a
     *  reset). */
    public void retune(double f0Hz) {
        double f0 = Math.max(MIN_MAINS_HZ, Math.min(MAX_MAINS_HZ, f0Hz));
        double n  = sampleRate / f0;
        this.nInt   = (int) Math.floor(n);
        this.nFrac  = n - nInt;
        this.alpha  = Math.pow(rho, n);
        this.mainsHz = f0;
    }

    /** Clears the delay-line state (e.g. on a capture restart) without
     *  changing the current tuning. */
    public void reset() {
        Arrays.fill(xBuf, 0.0);
        Arrays.fill(yBuf, 0.0);
        pos = 0;
    }

    /**
     * Estimates the mains fundamental from {@code ref} — scanning the
     * 50 Hz and 60 Hz bands and locking to whichever carries more energy
     * — then re-tunes the comb.  Returns the detected frequency (Hz), or
     * {@code NaN} when no confident mains line is found (the comb keeps
     * its previous tuning in that case).
     *
     * @param ref reference samples (use the signal being filtered, or a
     *            dedicated mains pickup if available)
     * @param len number of valid samples in {@code ref}
     */
    public double track(float[] ref, int len) {
        if (ref == null) return Double.NaN;
        len = Math.min(len, ref.length);
        // Need a few mains cycles for a meaningful estimate.
        if (len < (int) (4 * sampleRate / MIN_MAINS_HZ)) return Double.NaN;

        if (windowScratch.length < len) windowScratch = new double[len];
        double[] w = windowScratch;
        // Hann window to suppress spectral leakage from the (usually
        // dominant) test signal into the mains bands.
        double norm = 2.0 * Math.PI / (len - 1);
        for (int i = 0; i < len; i++) {
            w[i] = ref[i] * 0.5 * (1.0 - Math.cos(norm * i));
        }

        // Score each mains candidate by its fundamental (H1) AND its second
        // harmonic (H2): a 50 Hz source has energy at 50 and 100, a 60 Hz one
        // at 60 and 120.  This both discriminates 50 vs 60 robustly and — key
        // for rectifier / diode-bridge loads, whose hum sits mostly in the
        // 2nd harmonic — lets us lock even when the 50/60 fundamental is
        // buried but 100/120 stands tall.  H2 bands use twice the span since
        // the harmonic drifts 2× as far in Hz as the fundamental.
        double[] h1a = scanBand(w, len, 50.0,  DETECT_SPAN_HZ);
        double[] h2a = scanBand(w, len, 100.0, 2 * DETECT_SPAN_HZ);
        double[] h1b = scanBand(w, len, 60.0,  DETECT_SPAN_HZ);
        double[] h2b = scanBand(w, len, 120.0, 2 * DETECT_SPAN_HZ);

        boolean is50 = (h1a[1] + h2a[1]) >= (h1b[1] + h2b[1]);
        double[] h1 = is50 ? h1a : h1b;
        double[] h2 = is50 ? h2a : h2b;

        // Lock when EITHER harmonic is a genuine line (≥ LOCK_RATIO over the
        // noise floor) — so a strong-H2 / weak-H1 rectifier hum still locks.
        // On a miss, HOLD the existing smoothed lock rather than dropping it.
        double baseline = scannedBaseline(w, len);
        if (baseline <= 0 || Math.max(h1[1], h2[1]) < LOCK_RATIO * baseline) return lockHz;

        // Derive f0 from the cleaner (stronger) harmonic: H2 refined / 2 when
        // the 2nd harmonic dominates (also halves the Hz error), else H1.
        double f0 = (h2[1] > h1[1]) ? h2[0] / 2.0 : h1[0];
        // Smooth the lock: snap on first detection, EWMA-blend thereafter,
        // reject outliers (keep the current lock).
        if (Double.isNaN(lockHz)) {
            lockHz = f0;
        } else if (Math.abs(f0 - lockHz) <= MAX_LOCK_DRIFT_HZ) {
            lockHz = LOCK_SMOOTH * lockHz + (1.0 - LOCK_SMOOTH) * f0;
        }
        retune(lockHz);
        return lockHz;
    }

    /**
     * Filters {@code data} in place (length {@code len}).  No-op until
     * the comb has been tuned (call {@link #track} or {@link #retune}
     * first) — an untuned filter passes the signal through unchanged.
     */
    public void process(float[] data, int len) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        final double a = alpha;
        final double g = nFrac;
        final double g1 = 1.0 - g;
        for (int i = 0; i < len; i++) {
            double in = data[i];
            int iA = pos - nInt;       // delay nInt
            int iB = iA - 1;           // delay nInt + 1
            if (iA < 0) iA += bufLen;
            if (iB < 0) iB += bufLen;
            double xN = g1 * xBuf[iA] + g * xBuf[iB];
            double yN = g1 * yBuf[iA] + g * yBuf[iB];
            double y  = in - xN + a * yN;
            xBuf[pos] = in;
            yBuf[pos] = y;
            pos++;
            if (pos >= bufLen) pos = 0;
            data[i] = (float) y;
        }
    }

    /**
     * Like {@link #process}, but preserves the block's DC level: the comb
     * inherently notches DC (its zero at k=0), which would re-centre a
     * DC-coupled trace and zero its mean.  This variant subtracts the
     * block mean, combs the zero-mean signal, then adds the mean back — so
     * only the mains hum (50/60 Hz + harmonics) is removed and the DC
     * operating point is left intact.  Used by the oscilloscope, where the
     * displayed amplitude and {@code Vmean} should reflect "hum removed",
     * not "hum and DC removed".  No-op until tuned.
     */
    public void processPreservingDc(float[] data, int len) {
        if (!isTuned()) return;
        len = Math.min(len, data.length);
        if (len <= 0) return;
        double sum = 0.0;
        for (int i = 0; i < len; i++) sum += data[i];
        float mean = (float) (sum / len);
        for (int i = 0; i < len; i++) data[i] -= mean;
        process(data, len);
        for (int i = 0; i < len; i++) data[i] += mean;
    }

    // ─── Detection helpers ───────────────────────────────────────────────────

    /** Scans {@code ±spanHz} around {@code centerHz}, returns
     *  {@code {refinedHz, peakPower}} with the peak refined to sub-step
     *  by parabolic interpolation on log-power. */
    private double[] scanBand(double[] w, int len, double centerHz, double spanHz) {
        double lo = centerHz - spanHz;
        int m = (int) Math.round(2 * spanHz / DETECT_STEP_HZ) + 1;
        double[] mag = new double[m];
        int bestK = 0;
        for (int k = 0; k < m; k++) {
            mag[k] = goertzelPower(w, len, lo + k * DETECT_STEP_HZ);
            if (mag[k] > mag[bestK]) bestK = k;
        }
        double refinedHz = lo + bestK * DETECT_STEP_HZ;
        if (bestK > 0 && bestK < m - 1) {
            double a = Math.log(mag[bestK - 1] + 1e-30);
            double b = Math.log(mag[bestK]     + 1e-30);
            double c = Math.log(mag[bestK + 1] + 1e-30);
            double denom = a - 2 * b + c;
            double delta = (Math.abs(denom) < 1e-15) ? 0.0 : 0.5 * (a - c) / denom;
            if (delta < -0.5) delta = -0.5;
            if (delta >  0.5) delta =  0.5;
            refinedHz = lo + (bestK + delta) * DETECT_STEP_HZ;
        }
        return new double[] { refinedHz, mag[bestK] };
    }

    /** Median Goertzel power across the four detection bands (50/60 Hz +
     *  100/120 Hz harmonics) — a robust floor estimate the strongest line
     *  must clear to count as real mains. */
    private double scannedBaseline(double[] w, int len) {
        int m = (int) Math.round(2 * DETECT_SPAN_HZ / DETECT_STEP_HZ) + 1;
        double[] all = new double[4 * m];
        double[] centers = { 50.0, 60.0, 100.0, 120.0 };
        int idx = 0;
        for (double c : centers) {
            double lo = c - DETECT_SPAN_HZ;
            for (int k = 0; k < m; k++) all[idx++] = goertzelPower(w, len, lo + k * DETECT_STEP_HZ);
        }
        Arrays.sort(all);
        return all[all.length / 2];
    }

    /** Goertzel single-frequency power |X(f)|² over a windowed block. */
    private double goertzelPower(double[] w, int len, double freqHz) {
        double omega = 2.0 * Math.PI * freqHz / sampleRate;
        double coeff = 2.0 * Math.cos(omega);
        double s1 = 0.0, s2 = 0.0;
        for (int i = 0; i < len; i++) {
            double s = w[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }
}
