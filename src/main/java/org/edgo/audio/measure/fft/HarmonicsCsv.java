package org.edgo.audio.measure.fft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lombok.extern.log4j.Log4j2;

/**
 * Reads and writes the harmonics-summary CSV format produced by
 * {@link FftAnalyzer}.  Two related operations:
 *
 * <ul>
 *   <li>{@link #export}      — writes a {@link FftResult} as
 *       {@code fft_harmonics_*.csv} (or a caller-named file), one row
 *       per harmonic plus THD/SNR footer rows.</li>
 *   <li>{@link #subtract}    — parses an exported CSV and subtracts the
 *       reconstructed sinusoids (H2 and above; fundamental skipped)
 *       from a sample buffer in-place.</li>
 * </ul>
 *
 * <p>Extracted from {@link FftAnalyzer} so the analyser stays focused on
 * spectral analysis and the on-disk format lives in one place.
 */
@Log4j2
public final class HarmonicsCsv {

    private HarmonicsCsv() {}

    /**
     * Writes {@code r}'s harmonics + summary metrics to a CSV file.
     * Filename is {@code <filePrefix>.csv} when {@code filePrefix} is
     * non-empty, otherwise {@code fft_harmonics_<timestamp>.csv}.
     *
     * @return absolute path of the saved file
     */
    public static String export(FftResult r, String directory,
                                String filePrefix) throws IOException {
        File outFile = (filePrefix != null && !filePrefix.isEmpty())
                ? new File(directory, filePrefix + ".csv")
                : new File(directory, "fft_harmonics_" + timestamp() + ".csv");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;%.4f;%.10e;%.10e%n",
                    r.fundamentalHz, r.fundamentalDbFs,
                    r.phaseDeg[r.fundamentalBin],
                    r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
            for (int h = 0; h < r.harmonicCount; h++) {
                int bin = r.harmonicBins[h];
                double phase = bin > 0 ? r.phaseDeg[bin] : 0.0;
                double re    = bin > 0 ? r.re[bin]        : 0.0;
                double im    = bin > 0 ? r.im[bin]        : 0.0;
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        h + 2, r.harmonicHz[h], r.harmonicDbFs[h], r.harmonicPct[h],
                        phase, re, im);
            }
            pw.println();
            pw.printf(Locale.GERMAN, "THD;;%.6f%%;%.4f dB%n",  r.thdPct,  r.thdDb);
            pw.printf(Locale.GERMAN, "THD+N (A-weighted);;;%.4f dB%n",     r.thdNDb);
            pw.printf(Locale.GERMAN, "SNR;;;%.4f dB%n",                     r.snrDb);
        }
        log.info("FFT harmonics CSV saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    /** Convenience overload — saves under the timestamped default name. */
    public static String export(FftResult r, String directory) throws IOException {
        return export(r, directory, null);
    }

    /**
     * Loads a harmonics CSV file (produced by {@link #export}) and
     * subtracts the reconstructed sinusoids (H2 and above) from
     * {@code samples} in-place.  The fundamental (H1) is always skipped.
     *
     * <p>Two phase-source modes:
     * <ul>
     *   <li>{@code useReIm=false}: amplitude from {@code amplitude_dbfs},
     *       phase from {@code phase_deg} column (4 decimal places).</li>
     *   <li>{@code useReIm=true}: amplitude from {@code amplitude_dbfs},
     *       phase derived via {@code atan2(im, re)} — full double precision.</li>
     * </ul>
     */
    public static void subtract(float[] samples, int sampleRate,
                                String csvFile, boolean useReIm) throws IOException {
        // Parse rows: skip header, skip H1 (fundamental), skip footers
        // Stored as [freqHz, ampLinear, cos(phase), sin(phase)]
        List<double[]> harmonics = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            br.readLine();   // header
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                    continue;   // blank line or footer (THD, SNR, …)
                }
                String[] cols = line.split(";");
                if (cols.length < 5) continue;
                int harmonicIndex = Integer.parseInt(cols[0].trim());
                if (harmonicIndex == 1) {
                    continue;   // skip fundamental
                }
                double freqHz        = Double.parseDouble(cols[1].trim().replace(',', '.'));
                double amplitudeDbFs = Double.parseDouble(cols[2].trim().replace(',', '.'));
                double ampLinear     = Math.pow(10.0, amplitudeDbFs / 20.0);
                double cosPhase, sinPhase;
                if (useReIm && cols.length >= 7) {
                    double re = Double.parseDouble(cols[5].trim().replace(',', '.'));
                    double im = Double.parseDouble(cols[6].trim().replace(',', '.'));
                    double mag = Math.sqrt(re * re + im * im);
                    if (mag > 0) {
                        cosPhase = re / mag;
                        sinPhase = im / mag;
                    } else {
                        cosPhase = 1.0;
                        sinPhase = 0.0;
                    }
                } else {
                    double phaseRad = Math.toRadians(
                            Double.parseDouble(cols[4].trim().replace(',', '.')));
                    cosPhase = Math.cos(phaseRad);
                    sinPhase = Math.sin(phaseRad);
                }
                harmonics.add(new double[]{ freqHz, ampLinear, cosPhase, sinPhase });
            }
        }
        log.info("Subtracting {} harmonic(s) (H2+) using {} from: {}",
                harmonics.size(), useReIm ? "re/im" : "amp+phase", csvFile);

        for (double[] h : harmonics) {
            double freqHz    = h[0];
            double ampLinear = h[1];
            double omega     = 2.0 * Math.PI * freqHz / sampleRate;

            double cosOmega = Math.cos(omega);
            double sinOmega = Math.sin(omega);
            // Start phasor at n=0: exp(j·phase) = cosPhase + j·sinPhase
            double curRe = h[2];   // cos(phase)
            double curIm = h[3];   // sin(phase)

            for (int n = 0; n < samples.length; n++) {
                // A·cos(ω·n + φ) = A · Re(exp(j·(ω·n+φ))) = A · curRe
                samples[n] -= (float) (ampLinear * curRe);
                double nextRe = curRe * cosOmega - curIm * sinOmega;
                curIm         = curRe * sinOmega + curIm * cosOmega;
                curRe         = nextRe;
            }
            log.debug("Subtracted H at {} Hz  {} dBFS", freqHz,
                    String.format(Locale.US, "%.4f", 20.0 * Math.log10(ampLinear)));
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
