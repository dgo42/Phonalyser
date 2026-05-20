package org.edgo.audio.measure.fft;

/**
 * Cooley-Tukey radix-2 decimation-in-time FFT, in-place.  Size must be
 * a power of two.  Pure function on {@code double[]} arrays — no state,
 * no allocation past the input buffers, trivially unit-testable.
 *
 * <p>Extracted from {@link FftAnalyzer} so the spectral primitive can
 * be reused (and tested in isolation) without dragging in the analyser's
 * full averaging + windowing pipeline.
 */
public final class Fft {

    private Fft() {}

    /**
     * Forward FFT.  Mutates {@code re} and {@code im} in-place to hold
     * the complex spectrum on return.  Both arrays must have the same
     * length and that length must be a power of two.
     */
    public static void forward(double[] re, double[] im) {
        int n = re.length;

        // Bit-reversal permutation
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double t;
                t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }

        // Butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int    p  = i + k;
                    int    q  = i + k + len / 2;
                    double uR = re[p];
                    double uI = im[p];
                    double vR = re[q] * curRe - im[q] * curIm;
                    double vI = re[q] * curIm + im[q] * curRe;
                    re[p] = uR + vR;
                    im[p] = uI + vI;
                    re[q] = uR - vR;
                    im[q] = uI - vI;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm         = curRe * wIm + curIm * wRe;
                    curRe         = nextRe;
                }
            }
        }
    }
}
