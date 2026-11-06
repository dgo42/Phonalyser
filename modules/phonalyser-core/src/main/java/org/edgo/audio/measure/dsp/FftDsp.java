package org.edgo.audio.measure.dsp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.Fft;
import org.edgo.audio.measure.fft.MathUtil;

public final class FftDsp {

    private FftDsp() {}

    /** Noise ceiling = median + {@code CEIL_K}·MAD of the flank band. */
    private static final double CEIL_K = 4.0;

    /** Width of each flank band {@link #pedestalFloor} reads the noise on, in
     *  Hz - converted to bins with the spectrum's own resolution, so the band
     *  covers the same stretch of spectrum at every sample rate and FFT size
     *  (about 983 bins at 384 kHz with a 2 M-point FFT). */
    private static final double FLOOR_SPAN_HZ = 180.0;

    /** Half-width of a distortion line's floor window, in Hz - converted to
     *  bins with the spectrum's own resolution, so the window covers the same
     *  spectral neighbourhood at every sample rate and FFT size. */
    private static final double LINE_FLOOR_SPAN_HZ = 25.0;

    /** The lobe exclusion at the line-floor window's centre, in equivalent
     *  noise bandwidths of the spectrum's window function: the main lobe
     *  widens with the window's NENBW, so the exclusion follows it instead of
     *  assuming one window type. */
    private static final double LINE_FLOOR_EXCLUDE_NENBW = 4.0;

    /** The window's bins stand for the local floor only over enough bins;
     *  fewer than this beyond the exclusion and the window is too narrow to
     *  read a floor from. */
    private static final int LINE_FLOOR_MIN_BINS = 16;

    // =========================================================================
    // Window functions
    // =========================================================================

    /**
     * Builds a normalized analysis window of length {@code N} for the given type.
     * All windows use (N−1) symmetric normalization except Flat-top which uses
     * periodic (N) normalization for optimal amplitude accuracy.
     */
    public static double[] buildWindow(int N, WindowType type) {
        switch (type) {
            case RECT: {
                double[] w = new double[N];
                Arrays.fill(w, RECT_COEFFS[0]);
                return w;
            }

            case HANN:
                return buildCosineSum(N, HANN_COEFFS, N - 1);

            case BH4:
                return buildCosineSum(N, BH4_COEFFS, N - 1);

            case BH7:
                return buildCosineSum(N, BH7_COEFFS, N - 1);

            case FT:
                return buildCosineSum(N, FT_COEFFS, N);

            case HFT144D:
                return buildHftWindow(N, HFT144D_COEFFS);

            case HFT248D:
                return buildHftWindow(N, HFT248D_COEFFS);

            case KB24:
                return buildKaiserWindow(N, KB24_BETA);

            case KB38:
                return buildKaiserWindow(N, KB38_BETA);

            case DC150:
                return buildChebyshevWindow(N, DC150_ATTEN_DB);

            case DC200:
                return buildChebyshevWindow(N, DC200_ATTEN_DB);

            case DC250:
                return buildChebyshevWindow(N, DC250_ATTEN_DB);

            case DC300:
                return buildChebyshevWindow(N, DC300_ATTEN_DB);

            default:
                throw new IllegalArgumentException("Unknown window type: " + type);
        }
    }

    /** Builds a cosine-sum window of length {@code N} from {@code coeffs}:
     *  {@code w[n] = Σ (−1)ᵏ·cₖ·cos(k·2πn/period)}, the sign alternating with
     *  the term index.  {@code period} is {@code N−1} for the symmetric
     *  windows and {@code N} for the periodic flat-top, the normalization
     *  each family is defined with. */
    private static double[] buildCosineSum(int N, double[] coeffs, int period) {
        double[] w = new double[N];
        for (int n = 0; n < N; n++) {
            double t   = 2.0 * Math.PI * n / period;
            double val = 0.0;
            for (int k = 0; k < coeffs.length; k++) {
                val += (k % 2 == 0 ? 1.0 : -1.0) * coeffs[k] * Math.cos(k * t);
            }
            w[n] = val;
        }
        return w;
    }

    /** Rectangular window: one constant term, no cosine at all. */
    private static final double[] RECT_COEFFS = { 1.0 };

    /** Hann coefficients - the raised cosine {@code ½(1 − cos t)}. */
    private static final double[] HANN_COEFFS = { 0.5, 0.5 };

    /** Minimum 4-term Blackman-Harris coefficients (Harris 1978). */
    private static final double[] BH4_COEFFS = { 0.35875, 0.48829, 0.14128, 0.01168 };

    /** 7-term Blackman-Harris coefficients (Nuttall 1981). */
    private static final double[] BH7_COEFFS = {
            0.2712203606, 0.4334446123, 0.2180041184, 0.0657853433,
            0.0107618673, 0.0007700127, 0.0000136547 };

    /** SRS SR785 5-term flat-top coefficients - periodic normalization (N). */
    private static final double[] FT_COEFFS = {
            0.21557895, 0.41663158, 0.27726316, 0.08357895, 0.00694737 };

    /** HFT144D cosine-sum coefficients (Heinzel/Rüdiger/Schilling 2002,
     *  "Spectrum and spectral density estimation by the DFT...") - flat-top,
     *  −144.1 dB highest sidelobe, ±0.0001 dB amplitude flatness,
     *  differentiable. */
    private static final double[] HFT144D_COEFFS = {
            1.0, -1.96760033, 1.57983607, -0.81123644,
            0.22583558, -0.02773848, 0.00090360 };

    /** HFT248D coefficients (same paper) - flat-top, −248.4 dB highest
     *  sidelobe, ±0.0001 dB amplitude flatness; sidelobes below anything a
     *  physical ADC produces. */
    private static final double[] HFT248D_COEFFS = {
            1.0, -1.985844164102, 1.791176438506, -1.282075284005,
            0.667777530266, -0.240160796576, 0.056656381764,
            -0.008134974479, 0.000624544650, -0.000019808998,
            0.000000132974 };

    /** Kaiser-Bessel shape parameters β of the two offered windows. */
    private static final double KB24_BETA = 24.0;
    private static final double KB38_BETA = 38.0;

    /** Dolph-Chebyshev sidelobe attenuations of the four offered windows, dB. */
    private static final double DC150_ATTEN_DB = 150.0;
    private static final double DC200_ATTEN_DB = 200.0;
    private static final double DC250_ATTEN_DB = 250.0;
    private static final double DC300_ATTEN_DB = 300.0;

    private final static Map<WindowType, Integer> windowSize = new HashMap<>();
    /**
     * Main-lobe half-width of a window in BINS - the first null of its own
     * transform, so two lines closer than this are one hump and neither can be
     * read.  Analytic, from the window's definition alone: it does not depend
     * on the transform size and no window table is built to obtain it.
     *
     * <ul>
     *   <li>cosine sums (incl. the HFT flat-tops): a tone sitting on a bin
     *       raises exactly the {@code coeffs.length − 1} bins each side and
     *       nothing beyond, so the first null is the coefficient count;</li>
     *   <li>Kaiser: the trigonometric branch of its transform,
     *       {@code sin(√(u² − β²))/√(u² − β²)} with {@code u = πd}, first
     *       vanishes at {@code d = √(β² + π²)/π};</li>
     *   <li>Dolph-Chebyshev: the first zero of {@code T_{N−1}(x₀·cos(πd/N))}
     *       sits at {@code d = N·acos(cos(π/(2(N−1)))/x₀)/π}, and since
     *       {@code x₀ = cosh(acosh(10^(A/20))/(N−1))} that equals
     *       {@code √((π/2)² + acosh(10^(A/20))²)/π} for every transform size
     *       in use, far inside the one bin the ceiling rounds to.</li>
     * </ul>
     * The hump the coherent average turns as one
     * ({@link org.edgo.audio.measure.fft.SpectrumAccumulator})
     * is this many bins to either side of a line.
     */
    public static int mainLobeBins(WindowType window) {
        synchronized(windowSize) {
            if (windowSize.containsKey(window)) {
                return windowSize.get(window);
            } else {
                int size = calculateWindowSize(window);
                windowSize.put(window, size);
                return size;
            }
        }
    }

    private static int calculateWindowSize(WindowType window) {
        switch (window) {
            case RECT:
                return RECT_COEFFS.length;
            case HANN:
                return HANN_COEFFS.length;
            case BH4:
                return BH4_COEFFS.length;
            case BH7:
                return BH7_COEFFS.length;
            case FT:
                return FT_COEFFS.length;
            case HFT144D:
                return HFT144D_COEFFS.length;
            case HFT248D:
                return HFT248D_COEFFS.length;
            case KB24:
                return kaiserLobeBins(KB24_BETA);
            case KB38:
                return kaiserLobeBins(KB38_BETA);
            case DC150:
                return chebyshevLobeBins(DC150_ATTEN_DB);
            case DC200:
                return chebyshevLobeBins(DC200_ATTEN_DB);
            case DC250:
                return chebyshevLobeBins(DC250_ATTEN_DB);
            case DC300:
                return chebyshevLobeBins(DC300_ATTEN_DB);
            default:
                throw new IllegalArgumentException("Unknown window type: " + window);
        }
    }

    /** Bins added to a main lobe before a clearance is reported, so that no
     *  distance is ever answered from inside the lobe itself. */
    public static final int LEAKAGE_GUARD_BINS = 2;

    /**
     * The smallest WHOLE-bin distance at which this window's own leakage from
     * a line sitting on a bin is {@code dbDown} or more below that line -
     * never less than {@link #mainLobeBins} plus {@value #LEAKAGE_GUARD_BINS}
     * bins.  What a caller needs that has to keep two generated lines far
     * enough apart for each to be read at its own bin without the other's
     * skirt turning the phase it reads.
     *
     * <p>Derivation for a cosine sum {@code w[n] = Σ aₘ·cos(2πmn/N)}: its
     * transform is the sum of Dirichlet kernels displaced by {@code ±m},
     * {@code W(d) = Σ aₘ/2 · (sinc(d − m) + sinc(d + m))} in the large-N
     * limit, worth {@code a₀} at {@code d = 0}.  At a WHOLE bin {@code d} the
     * sinc of every non-zero integer vanishes, so a single term survives,
     * {@code a_d / 2}, and the leakage is {@code |a_d| / (2·a₀)} of the peak -
     * a magnitude, so which of the two families carries the alternating sign
     * in its table does not enter.  Past the last coefficient the transform is
     * zero, which bounds the search.  Two lines on whole bins of one grid are
     * exactly the case this answers.
     *
     * <p>Kaiser and Dolph-Chebyshev are not cosine sums - their transforms
     * have no such closed form and do not vanish between bins - so for them
     * the answer is the main lobe plus the guard.
     */
    public static int leakageClearBins(WindowType type, double dbDown) {
        double[] coeffs;
        switch (type) {
            case RECT:
                coeffs = RECT_COEFFS;
                break;
            case HANN:
                coeffs = HANN_COEFFS;
                break;
            case BH4:
                coeffs = BH4_COEFFS;
                break;
            case BH7:
                coeffs = BH7_COEFFS;
                break;
            case FT:
                coeffs = FT_COEFFS;
                break;
            case HFT144D:
                coeffs = HFT144D_COEFFS;
                break;
            case HFT248D:
                coeffs = HFT248D_COEFFS;
                break;
            default:
                return mainLobeBins(type) + LEAKAGE_GUARD_BINS;
        }
        double limit = Math.pow(10.0, -dbDown / 20.0);
        int    d     = 1;
        while (d < coeffs.length
                && Math.abs(coeffs[d]) / (2.0 * Math.abs(coeffs[0])) > limit) {
            d++;
        }
        return Math.max(d, mainLobeBins(type) + LEAKAGE_GUARD_BINS);
    }

    /** First null of the Kaiser transform, {@code √(β² + π²)/π} bins. */
    private static int kaiserLobeBins(double beta) {
        return (int) Math.ceil(Math.sqrt(beta * beta + Math.PI * Math.PI) / Math.PI);
    }

    /** First null of the Dolph-Chebyshev transform for a sidelobe attenuation
     *  of {@code attenDb}, {@code √((π/2)² + acosh(10^(A/20))²)/π} bins. */
    private static int chebyshevLobeBins(double attenDb) {
        double ripple = MathUtil.acosh(Math.pow(10.0, attenDb / 20.0));
        double halfPi = Math.PI / 2.0;
        return (int) Math.ceil(Math.sqrt(halfPi * halfPi + ripple * ripple) / Math.PI);
    }

    /** Builds a symmetric Kaiser-Bessel window,
     *  {@code w[n] = I₀(β·√(1−x²)) / I₀(β)} with {@code x = 2n/(N−1) − 1}.
     *  Sidelobe suppression follows Kaiser's {@code A ≈ 8.7 + β/0.1102} dB
     *  relation - β=24 ≈ −226 dB with a narrower main lobe than equally
     *  suppressing cosine windows; β=38 sits below the double-precision
     *  FFT floor (like DC300). */
    private static double[] buildKaiserWindow(int N, double beta) {
        double[] w = new double[N];
        double denom = besselI0(beta);
        for (int n = 0; n < N; n++) {
            double x = 2.0 * n / (N - 1) - 1.0;
            w[n] = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - x * x))) / denom;
        }
        return w;
    }

    /** Modified Bessel function of the first kind, order 0 - power series
     *  {@code Σ ((x/2)ᵏ/k!)²}; converges to double precision well within
     *  the iteration cap for the β range used here. */
    private static double besselI0(double x) {
        double sum  = 1.0;
        double term = 1.0;
        double half = x / 2.0;
        for (int k = 1; k < 200; k++) {
            double f = half / k;
            term *= f * f;
            sum  += term;
            if (term < sum * 1e-17) break;
        }
        return sum;
    }

    /** Builds an HFT-family flat-top window: a PERIODIC cosine sum
     *  {@code w(z) = Σ cₖ·cos(k·z)}, {@code z = 2πn/N} (signs live in the
     *  coefficients), normalized to peak 1.  The amplitude scaling
     *  self-normalizes via the coherent gain - peak-1 just keeps the
     *  window family consistent. */
    private static double[] buildHftWindow(int N, double[] coeffs) {
        double[] w = new double[N];
        double max = 0.0;
        for (int n = 0; n < N; n++) {
            double z = 2.0 * Math.PI * n / N;
            double val = 0.0;
            for (int k = 0; k < coeffs.length; k++) {
                val += coeffs[k] * Math.cos(k * z);
            }
            w[n] = val;
            if (val > max) max = val;
        }
        for (int n = 0; n < N; n++) {
            w[n] /= max;
        }
        return w;
    }

    /**
     * Builds a Dolph-Chebyshev window via FFT-based IDFT.
     *
     * <ol>
     *   <li>Compute x0 = cosh(acosh(10^(atten/20)) / (N−1))</li>
     *   <li>For k = 0..N−1: W[k] = T_{N−1}(x0 · cos(πk/N)), symmetrically filled</li>
     *   <li>IFFT(W) / N gives the time-domain window</li>
     *   <li>fftshift by N/2 centers the main lobe</li>
     *   <li>Normalize to max = 1</li>
     * </ol>
     */
    public static double[] buildChebyshevWindow(int N, double attenDb) {
        double beta = Math.pow(10.0, attenDb / 20.0);
        double x0   = Math.cosh(MathUtil.acosh(beta) / (N - 1));

        double[] wRe = new double[N];
        double[] wIm = new double[N];

        // Compute W[k] = T_{N-1}(x0 * cos(π*k/N)) for k=0..N/2, symmetric fill
        for (int k = 0; k <= N / 2; k++) {
            double val = MathUtil.chebyshevT(N - 1, x0 * Math.cos(Math.PI * k / N));
            wRe[k] = val;
            if (k > 0 && k < N - k) {
                wRe[N - k] = val;
            }
        }

        // IFFT via FFT conjugate: IFFT(W) = conj(FFT(conj(W))) / N
        // Since W is real, conj(W) = W, so IFFT = FFT / N
        Fft.forward(wRe, wIm);
        for (int i = 0; i < N; i++) {
            wRe[i] /= N;
        }

        // fftshift: rotate by N/2 to center the main lobe
        double[] window = new double[N];
        int half = N / 2;
        for (int i = 0; i < N; i++) {
            window[(i + half) % N] = wRe[i];
        }

        // Normalize to max = 1
        double max = 0.0;
        for (double v : window) {
            if (v > max) {
                max = v;
            }
        }
        if (max > 0.0) {
            for (int i = 0; i < N; i++) {
                window[i] /= max;
            }
        }
        return window;
    }

    // =========================================================================
    // Order statistics
    // =========================================================================

    /**
     * The entry of rank {@code rank} among the first {@code length} entries
     * of {@code values} - the one an ascending sort would put at index
     * {@code rank}.  In-place Hoare quickselect: O(length) on average against
     * the O(length log length) of a full sort, which at FFT lengths of
     * millions of bins dominated every noise-floor read that needs only one
     * rank.  The median-of-three pivot keeps the sorted and constant runs a
     * quiet noise floor produces from degrading it.  The entries are
     * reordered, so the caller hands in a working array.
     *
     * @param values the entries, reordered in place
     * @param length how many leading entries take part
     * @param rank   the rank asked for, 0 for the smallest
     * @return the entry of that rank
     */
    public static double selectKth(double[] values, int length, int rank) {
        int low  = 0;
        int high = length - 1;
        while (low < high) {
            double first  = values[low];
            double middle = values[(low + high) >>> 1];
            double last   = values[high];
            double pivot  = Math.max(Math.min(first, middle),
                                     Math.min(Math.max(first, middle), last));
            int left  = low;
            int right = high;
            while (left <= right) {
                while (values[left] < pivot) left++;
                while (values[right] > pivot) right--;
                if (left <= right) {
                    double swap   = values[left];
                    values[left]  = values[right];
                    values[right] = swap;
                    left++;
                    right--;
                }
            }
            if (rank <= right)     high = right;
            else if (rank >= left) low  = left;
            else                   return values[rank];
        }
        return values[rank];
    }

    /** Inclusive {@code [lo, hi]} bin span of the main lobe: the contiguous bins
     *  around {@code peak} that stand above the noise floor {@code floor}.  The
     *  peak itself is always included (so a sub-floor tone still yields its one
     *  peak bin); the walk runs out to where the lobe drops to the noise, at
     *  most to the outer end of the flank band {@link #pedestalFloor} reads the
     *  floor on - a lobe still above the floor there was itself inside that
     *  band.
     *
     * @param mag    a bin's magnitude, by bin index
     * @param peak   the tone's peak bin
     * @param maxBin highest bin index {@code mag} may be read at
     * @param floor  the level a lobe bin must stand above
     * @param binHz  the spectrum's bin width in Hz
     * @param window the spectrum's window function
     * @return {@code [lo, hi]}, the lobe's first and last bin
     */
    public static int[] lobeBins(IntToDoubleFunction mag, int peak, int maxBin, double floor,
            double binHz, WindowType window) {
        int maxLobeBins = FftDsp.mainLobeBins(window) + FftDsp.LEAKAGE_GUARD_BINS
                + (int) Math.round(FLOOR_SPAN_HZ / binHz);
        return new int[]{ edge(mag, peak, -1, maxBin, floor, maxLobeBins),
                          edge(mag, peak, +1, maxBin, floor, maxLobeBins) };
    }

    /** One side of {@link #lobeBins}: the last bin out from {@code peak} in
     *  direction {@code dir} that still stands above {@code floor}.
     *
     * @param mag         a bin's magnitude, by bin index
     * @param peak        the tone's peak bin
     * @param dir         -1 for the lower side, +1 for the upper
     * @param maxBin      highest bin index {@code mag} may be read at
     * @param floor       the level a lobe bin must stand above
     * @param maxLobeBins the walk's reach, bins
     * @return the side's last lobe bin; {@code peak} when its neighbour is
     *         already at or below the floor */
    private static int edge(IntToDoubleFunction mag, int peak, int dir, int maxBin, double floor,
            int maxLobeBins) {
        int foot = peak;
        for (int step = 1; step <= maxLobeBins; step++) {
            int k = peak + dir * step;
            if (k < 1 || k > maxBin) break;
            if (mag.applyAsDouble(k) <= floor) break;   // dropped to the noise - lobe ends
            foot = k;
        }
        return foot;
    }

    /** Noise-floor <b>ceiling</b> (same units as {@code mag}) near {@code peak}:
     *  {@code median + CEIL_K·MAD} of the bins flanking the tone.  Robust - a
     *  wide main lobe, harmonics, mains lines or spurs falling inside the band
     *  are outliers the median/MAD ignore.  Returns 0 when no flanks are in
     *  range.  (Named "floor" because it is the level a bin must rise ABOVE to
     *  count as part of the lobe, and the level the lobe's feet taper back to.)
     *  Each flank starts past the window's main lobe and the leakage guard -
     *  a width in bins, the same at every FFT size - and runs
     *  {@link #FLOOR_SPAN_HZ} on.
     *
     * @param mag    a bin's magnitude, by bin index
     * @param peak   the tone's peak bin
     * @param maxBin highest bin index {@code mag} may be read at
     * @param binHz  the spectrum's bin width in Hz
     * @param window the spectrum's window function
     * @return the band-top of the flanks; 0 when no flank bin is in range
     */
    public static double pedestalFloor(IntToDoubleFunction mag, int peak, int maxBin,
            double binHz, WindowType window) {
        int gap  = FftDsp.mainLobeBins(window) + FftDsp.LEAKAGE_GUARD_BINS;
        int span = (int) Math.round(FLOOR_SPAN_HZ / binHz);
        double[] buf = new double[2 * span];
        int m = 0;
        for (int k = peak - gap - span; k < peak - gap; k++) {
            if (k >= 1 && k <= maxBin) buf[m++] = mag.applyAsDouble(k);
        }
        for (int k = peak + gap + 1; k <= peak + gap + span; k++) {
            if (k >= 1 && k <= maxBin) buf[m++] = mag.applyAsDouble(k);
        }
        if (m == 0) return 0.0;
        return bandTop(buf, m);
    }

    /** A distortion line's local noise floor (same units as {@code mag}):
     *  the upper border of the noise band beside the line,
     *  {@code median + CEIL_K·MAD} of the bins within
     *  {@link #LINE_FLOOR_SPAN_HZ} of {@code bin}, the line's own lobe at the
     *  centre excluded over {@link #LINE_FLOOR_EXCLUDE_NENBW} noise bandwidths
     *  of the window.  The window is half the mains spacing wide to either
     *  side, so it always holds one mains harmonic, which the median and MAD
     *  ignore as an outlier. Unlike {@link #pedestalFloor}, whose flank band
     *  runs {@link #FLOOR_SPAN_HZ} out past the window's main lobe for the
     *  lobe walk, this reads the floor in the line's own neighbourhood.  A bin
     *  whose magnitude is not finite is left out.  Returns 0 when the window
     *  leaves too few floor bins to mean anything.
     *
     * @param mag       a bin's magnitude, by bin index
     * @param bin       the line's bin
     * @param maxBin    highest bin index {@code mag} may be read at
     * @param binHz     the spectrum's bin width in Hz
     * @param nenbwBins the window's equivalent noise bandwidth in bins
     * @return the band-top beside the line; 0 when too few bins remain
     */
    public static double lineFloor(IntToDoubleFunction mag, int bin, int maxBin,
            double binHz, double nenbwBins) {
        if (!(binHz > 0.0)) return 0.0;
        int exclude = (int) Math.ceil(LINE_FLOOR_EXCLUDE_NENBW * Math.max(1.0, nenbwBins));
        int span = Math.max((int) Math.round(LINE_FLOOR_SPAN_HZ / binHz),
                exclude + LINE_FLOOR_MIN_BINS);
        int lo = Math.max(1, bin - span);
        int hi = Math.min(maxBin, bin + span);
        double[] buf = new double[Math.max(0, hi - lo + 1)];
        int m = 0;
        for (int k = lo; k <= hi; k++) {
            if (Math.abs(k - bin) <= exclude) continue;
            double v = mag.applyAsDouble(k);
            if (Double.isFinite(v)) buf[m++] = v;
        }
        if (m < LINE_FLOOR_MIN_BINS) return 0.0;
        return bandTop(buf, m);
    }

    /** The band-top of a noise band: {@code median + CEIL_K·MAD} of the first
     *  {@code m} values of {@code buf} - the upper edge of the band's noise.
     *
     * @param buf the band's magnitudes; left as given
     * @param m   how many leading values take part, at least 1
     * @return the band-top, in the unit of {@code buf} */
    public static double bandTop(double[] buf, int m) {
        double[] c = Arrays.copyOf(buf, m);
        double median = selectKth(c, m, m / 2);
        for (int i = 0; i < m; i++) c[i] = Math.abs(buf[i] - median);
        double mad = selectKth(c, m, m / 2);
        return median + CEIL_K * mad;
    }

    /** The band-top ({@link #bandTop}) of a set of levels given in dB - the
     *  floor over the bands a line test left free.  The levels are taken as
     *  magnitudes; a level that is not finite is left out.  When every band
     *  is skipped, all of them take part.
     *
     * @param levelsDb the bands' levels, dB
     * @param skip     the bands to leave out (lines), by band
     * @param n        how many leading bands take part
     * @return the band-top, dB; NaN when no band has a finite level */
    public static double bandTopOfLevelsDb(double[] levelsDb, boolean[] skip, int n) {
        double[] mags = new double[n];
        int m = 0;
        for (int b = 0; b < n; b++) {
            if (!skip[b] && Double.isFinite(levelsDb[b])) mags[m++] = Math.pow(10.0, levelsDb[b] / 20.0);
        }
        if (m == 0) {
            for (int b = 0; b < n; b++) {
                if (Double.isFinite(levelsDb[b])) mags[m++] = Math.pow(10.0, levelsDb[b] / 20.0);
            }
        }
        if (m == 0) return Double.NaN;
        return 20.0 * Math.log10(bandTop(mags, m) + 1e-300);
    }


}
