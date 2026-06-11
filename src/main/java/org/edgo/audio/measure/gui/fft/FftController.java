/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.fft;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.FftBinSnap;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * Controller of the FFT pane: owns the {@link FftAnalyzerWorker}'s
 * recording lifecycle, the generator frequency-lock loops (FLL / PID) that
 * publish {@link Events#GENERATOR_FREQ_TRIM} / {@code _2}, the dual-tone
 * IMD analysis of the displayed frame, and the {@code .fft} spectrum file
 * round-trip.
 *
 * <p>The worker is constructor-injected — the pane builds it (the worker's
 * result hand-off marshals through the SWT {@code Display}) and hands it
 * over; the controller itself never imports SWT.  The view calls
 * {@link #analyzeImd} and {@link #applyFrequencyLock} from its
 * result-display pipeline AFTER finalizing the displayed frame (mains
 * correction, recompute, .frc) so both see the finished spectrum; the
 * controller never references the view — everything it emits goes over
 * the bus.
 */
public final class FftController {

    /** FLL measurement plausibility bound, relative part: generator-vs-ADC
     *  clock drift is ppm-scale (observed ≤ ~130 ppm), so 500 ppm is a
     *  generous ceiling for a REAL mistune; anything beyond it is a
     *  mis-measurement that must not reach the loop. */
    private static final double FLL_MAX_ERROR_PPM  = 500;
    /** FLL measurement plausibility bound, absolute floor in FFT bins —
     *  keeps the gate permissive at low target frequencies where the
     *  ppm part collapses below the spectral resolution. */
    private static final double FLL_MAX_ERROR_BINS = 5;
    /** Minimum fundamental frequency a loaded spectrum's peak search
     *  considers (Hz) — skips the DC/mains foot. */
    private static final double LOADED_FUND_MIN_HZ = 10.0;

    /** Analysis engine (capture + FFT + averaging on a daemon thread). */
    private final FftAnalyzerWorker worker;
    /** Calibration entries appended to saved spectra as provenance. */
    private final FreqRespCorrectionStore correctionStore;
    /** One analyzer instance for the controller's lifetime — its internal
     *  scratch buffer (noise-floor quickselect) is only reused this way. */
    private final ImdAnalyzer imdAnalyzer = new ImdAnalyzer();
    /** Frequency-lock loops: {@link #fll} tracks the SINE / dual-tone
     *  tone-1 correction; {@link #fll2} tracks dual-tone tone-2.  Created
     *  lazily when an alignment mode is active. */
    private FrequencyAligner fll;
    private FrequencyAligner fll2;

    /** A loaded {@code .fft} spectrum: the reconstructed result plus the
     *  re-measured IMD products when the file was captured in IMD mode
     *  ({@code null} for THD captures — the view stays in THD mode). */
    public record LoadedSpectrum(FftResult result, ImdResult imd) {}

    public FftController(FftAnalyzerWorker worker,
                         FreqRespCorrectionStore correctionStore) {
        this.worker          = worker;
        this.correctionStore = correctionStore;
    }

    // -------------------------------------------------------------------------
    // Recording lifecycle (delegates to the worker)
    // -------------------------------------------------------------------------

    /** Starts the analyser on its daemon worker thread (idempotent); the
     *  worker acquires its own shared-capture reference. */
    public void startRecording() {
        worker.start();
    }

    /** Stops the analyser and interrupts the worker (idempotent); the
     *  worker releases its shared-capture reference.  Also clears the
     *  FLL — the locked clock-drift estimate is only meaningful while
     *  the capture is live; on the next Record start it ramps up from
     *  zero again. */
    public void stopRecording() {
        worker.stop();
        resetFrequencyLock();
    }

    /** True while the analyser worker is running. */
    public boolean isRecording() {
        return worker.isRunning();
    }

    /** Number of analyses completed since the last reset. */
    public int completedAnalyses() {
        return worker.getCompletedAnalyses();
    }

    /** Fraction (0..1) of the data needed for the current FFT frame that
     *  has already been captured; negative on a capture overrun. */
    public double nextFrameProgress() {
        return worker.getNextFrameProgress();
    }

    /** True when the audio generator is currently producing a signal
     *  (bus-resolved {@link Events#GENERATOR_RUNNING} responder). */
    public boolean isGeneratorActive() {
        return worker.isGeneratorActive();
    }

    /** Clears the worker's completed-analyses counter and accumulator and
     *  resumes a stop-after-N pause.  Deliberately does NOT touch the
     *  FLL — see {@link #resetFrequencyLock()}. */
    public void resetStatistics() {
        worker.resetStatistics();
    }

    /** The UI consumed the published result — the worker's result-throttle
     *  handshake. */
    public void resultConsumed() {
        worker.uiGotResult();
    }

    /** Resets the frequency-lock loop(s) so alignment converges fresh from
     *  zero.  Called when the user turns alignment ON and on Record stop —
     *  kept separate from {@link #resetStatistics()} so a manual
     *  statistics reset keeps the converged alignment intact. */
    public void resetFrequencyLock() {
        if (fll != null) fll.reset();
        if (fll2 != null) fll2.reset();
    }

    /** Stops the worker — called from the pane's dispose listener. */
    public void shutdown() {
        worker.stop();
    }

    // -------------------------------------------------------------------------
    // Result-driven engine reactions (called by the view's result pipeline)
    // -------------------------------------------------------------------------

    /** Measures the dual-tone IMD products of the (finalized) displayed
     *  frame against the configured tone frequencies. */
    public ImdResult analyzeImd(FftResult slot) {
        Preferences prefs = Preferences.instance();
        return imdAnalyzer.analyze(slot,
                prefs.getGenDualToneFreq1Hz(),
                prefs.getGenDualToneFreq2Hz(),
                prefs.getDbvOffsetDb());
    }

    /** Feeds the latest FFT result into the closed-loop integrator and
     *  publishes the next generator frequency as a trim event.  Gated on
     *  the three prerequisite prefs (snap-to-bin, fund-from-gen,
     *  align-gen-to-freq-diff) AND the generator actively emitting.
     *  {@code imd} carries the per-tone detected frequencies for the
     *  dual-tone branch ({@code null} in single-tone mode). */
    public void applyFrequencyLock(FftResult slot, ImdResult imd) {
        if (slot == null) return;
        Preferences prefs = Preferences.instance();
        if (!prefs.isGenSnapToFftBin())       return;
        if (!prefs.isFftFundFromGenerator())  return;
        AlignGenerator mode = prefs.getFftAlignGenerator();
        if (mode == AlignGenerator.NONE)      return;
        if (!isGeneratorActive())             return;
        // Swap the loop type to match the selected mode (a change resets both loops).
        if (fll == null || fll.getMode() != mode) {
            FrequencyAlignerFactory factory = FrequencyAlignerFactory.instance();
            fll  = factory.create(mode);
            fll2 = factory.create(mode);
        }
        MessageBus bus = MessageBus.instance();
        // Branch on generator form: single-tone runs one loop, dual-tone two
        // independent loops driven by the per-tone detected frequencies in imd.
        boolean dualTone = prefs.getGenSignalForm() == GenSignalForm.DUAL_TONE;
        if (dualTone) {
            if (imd == null) return;
            double t1 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                    slot.sampleRate, prefs.getGenDualToneFreq1Hz());
            double t2 = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.DUAL_TONE,
                    slot.sampleRate, prefs.getGenDualToneFreq2Hz());
            if (t1 > 0 && Double.isFinite(imd.f1Hz)
                    && plausibleFllMeasurement(t1, imd.f1Hz, slot.sampleRate, slot.fftSize)) {
                fll.update(t1, imd.f1Hz, slot.samplesAbsStart, slot.writePos, slot.sampleRate, slot.fftSize);
                bus.publish(Events.GENERATOR_FREQ_TRIM,
                        t1 + fll.getCorrection());
            }
            if (t2 > 0 && Double.isFinite(imd.f2Hz)
                    && plausibleFllMeasurement(t2, imd.f2Hz, slot.sampleRate, slot.fftSize)) {
                fll2.update(t2, imd.f2Hz, slot.samplesAbsStart, slot.writePos, slot.sampleRate, slot.fftSize);
                bus.publish(Events.GENERATOR_FREQ_TRIM_2,
                        t2 + fll2.getCorrection());
            }
        } else {
            if (!Double.isFinite(slot.fundamentalHzRefined)) return;
            double target = FftBinSnap.snapIfEnabled(prefs, GenSignalForm.SINE,
                    slot.sampleRate, prefs.getGenFrequencyHz());
            if (!(target > 0)) return;
            if (!plausibleFllMeasurement(target, slot.fundamentalHzRefined,
                    slot.sampleRate, slot.fftSize)) {
                return;
            }
            fll.update(target, slot.fundamentalHzRefined, slot.samplesAbsStart, slot.writePos, slot.sampleRate, slot.fftSize);
            bus.publish(Events.GENERATOR_FREQ_TRIM,
                    target + fll.getCorrection());
        }
    }

    /** True when the measured frequency is close enough to the target to
     *  plausibly be the generator's own tone — clock drift is ppm-scale.
     *  A larger mismatch is a mis-measurement (a window still containing
     *  the OLD signal draining through the output buffer after a form /
     *  frequency switch, a harmonic mis-lock, a capture glitch); feeding
     *  it to the FLL published corrections like "19 kHz target − 1 kHz
     *  measured = +18 kHz" that trimmed the live generator to 37 kHz. */
    private boolean plausibleFllMeasurement(double targetHz, double measuredHz,
                                            int sampleRate, int fftSize) {
        double binHz = sampleRate / (double) fftSize;
        double maxErrHz = Math.max(FLL_MAX_ERROR_BINS * binHz,
                targetHz * FLL_MAX_ERROR_PPM * 1e-6);
        return Math.abs(measuredHz - targetHz) <= maxErrHz;
    }

    // -------------------------------------------------------------------------
    // .fft spectrum file round-trip
    // -------------------------------------------------------------------------

    /** Loads a spectrum {@code .fft} file (as written by
     *  {@link #saveSpectrum} — {@code frequency_hz;magnitude_dBV;phase_deg}
     *  rows under {@code # mode=} / {@code # tone*_hz=} header comments),
     *  reconstructs an {@link FftResult} and recomputes its fundamental /
     *  harmonic / THD / SNR metrics (and the IMD products when the file
     *  was captured in IMD mode) from the loaded bins.
     *
     *  @throws IOException              on a read / number-parse problem
     *  @throws IllegalArgumentException when the file holds no usable rows */
    public LoadedSpectrum loadSpectrum(String path) throws IOException {
        List<double[]> rows = new ArrayList<>();
        boolean modeImd = false;
        double tone1Hz = Double.NaN;
        double tone2Hz = Double.NaN;
        double binBwHz = Double.NaN;
        try {
            for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                // Capture-mode / tone header comments (see saveSpectrum).
                if (s.startsWith("# mode=")) {
                    modeImd = s.substring(s.indexOf('=') + 1).trim().equalsIgnoreCase("IMD");
                    continue;
                }
                if (s.startsWith("# tone1_hz=")) { tone1Hz = parseHeaderHz(s); continue; }
                if (s.startsWith("# tone2_hz=")) { tone2Hz = parseHeaderHz(s); continue; }
                if (s.startsWith("# bin_bw_hz=")) { binBwHz = parseHeaderHz(s); continue; }
                char c0 = s.charAt(0);
                if (c0 != '-' && c0 != '+' && c0 != '.' && !Character.isDigit(c0)) continue; // header / comment
                String[] p = s.split("[;,]");
                if (p.length < 2) continue;
                double f   = Double.parseDouble(p[0].trim());
                double dbv = Double.parseDouble(p[1].trim());
                double ph  = (p.length >= 3) ? Double.parseDouble(p[2].trim()) : 0.0;
                rows.add(new double[]{ f, dbv, ph });
            }
        } catch (NumberFormatException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        int n = rows.size();
        double freqRes = (n >= 2) ? (rows.get(n - 1)[0] - rows.get(0)[0]) / (n - 1) : 0.0;
        if (n < 4 || !(freqRes > 0)) {
            throw new IllegalArgumentException("not a spectrum file");
        }

        FftAnalyzer analyzer = new FftAnalyzer();
        FftResult r = new FftResult();
        Preferences prefs = Preferences.instance();
        int harmCount = Math.max(9, prefs.getFftCalcMaxHarmonic());
        r.ensureArrays(n, harmCount);
        r.freqResolution   = freqRes;
        // Old files lack the bin-bandwidth header; there the row spacing IS the
        // bandwidth the file was captured with.
        r.binBwSqrt        = Math.sqrt((binBwHz > 0) ? binBwHz : freqRes);
        r.fftSize          = 2 * (n - 1);
        r.sampleRate       = (int) Math.round(freqRes * r.fftSize);
        r.harmonicCount    = harmCount;
        r.fundamentalTrueDbFs = Double.NaN;
        r.snrFreqMin = 0; r.snrFreqMax = 0;
        // The file stores dBV; dBFS is the result's base scale, so subtract the
        // global ADC offset once on load (the writer adds it back on save, so a
        // round-trip is exact).
        double dbvOffsetDb = prefs.getDbvOffsetDb();
        for (int k = 0; k < n; k++) {
            double dbv = rows.get(k)[1], ph = rows.get(k)[2];
            double dbFs = dbv - dbvOffsetDb;
            r.amplitudeDbFs[k] = dbFs;
            r.phaseDeg[k]      = ph;
            double lin = Math.pow(10.0, dbFs / 20.0);
            double rad = Math.toRadians(ph);
            r.re[k] = lin * Math.cos(rad);
            r.im[k] = lin * Math.sin(rad);
        }
        // Fundamental = strongest bin above ~10 Hz, refined to sub-bin.
        int halfSize = n - 1;
        int minBin = Math.min(halfSize, Math.max(1,
                (int) Math.ceil(LOADED_FUND_MIN_HZ / freqRes)));
        int fb = minBin;
        for (int k = minBin; k <= halfSize; k++) if (r.amplitudeDbFs[k] > r.amplitudeDbFs[fb]) fb = k;
        r.fundamentalBin       = fb;
        r.fundamentalHz        = fb * freqRes;
        r.fundamentalHzRefined = MathUtil.parabolicBinInterp(r.re, r.im, fb, r.fftSize) * freqRes;
        // Theoretical harmonic positions for recomputeStats to measure at.
        for (int h = 0; h < harmCount; h++) {
            double hz = (h + 2) * r.fundamentalHzRefined;
            int hb = (int) Math.round(hz / freqRes);
            r.harmonicHz[h]   = hz;
            r.harmonicBins[h] = (hb >= 1 && hb <= halfSize) ? hb : -1;
        }
        analyzer.recomputeStats(r);

        // Recompute the IMD products when the file was captured in IMD mode and
        // carries its two tone frequencies: pin the refined tone positions
        // (F1 = lower) so the result reports the saved frequencies, then measure
        // the products from the loaded spectrum.  A non-null result switches the
        // view to the IMD table; otherwise it stays in single-tone THD mode.
        ImdResult imd = null;
        if (modeImd && Double.isFinite(tone1Hz) && Double.isFinite(tone2Hz)
                && tone1Hz > 0 && tone2Hz > 0) {
            r.fundamentalHzRefined  = Math.min(tone1Hz, tone2Hz);
            r.fundamental2HzRefined = Math.max(tone1Hz, tone2Hz);
            imd = imdAnalyzer.analyze(r, tone1Hz, tone2Hz, dbvOffsetDb);
        }
        return new LoadedSpectrum(r, imd);
    }

    /** Parses the value of a {@code # key=value} spectrum-header comment as a
     *  frequency in Hz, returning {@code NaN} when absent or malformed. */
    private double parseHeaderHz(String line) {
        try {
            return Double.parseDouble(line.substring(line.indexOf('=') + 1).trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    /** Writes {@code r} as a {@code .fft} spectrum file —
     *  {@code frequency_hz;magnitude_dBV;phase_deg} rows under provenance
     *  header comments (capture mode, tones, analysis parameters, applied
     *  calibrations). */
    public void saveSpectrum(Path file, FftResult r, boolean imd,
                             double tone1Hz, double tone2Hz) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            // Header comments (skipped by the loader's value parser): the capture
            // mode restores the THD or IMD table on re-open, the two tone
            // frequencies let IMD re-measure its products, and the analysis
            // parameters + applied calibrations are informational provenance.
            Preferences prefs = Preferences.instance();
            double dbvOffset = prefs.getDbvOffsetDb();   // dBV = dBFs + global ADC offset
            pw.println("# Phonalyser FFT spectrum");
            pw.println("# format_version=" + FileVersions.FFT_SPECTRUM);
            pw.println("# mode=" + (imd ? "IMD" : "THD"));
            pw.printf("# fft_size=%d%n", r.fftSize);
            pw.printf("# sample_rate_hz=%d%n", r.sampleRate);
            pw.printf("# window=%s%n", r.windowType != null ? r.windowType.name() : "n/a");
            pw.printf("# averaging=%s%n", r.coherentAveraging ? "coherent" : "incoherent");
            pw.printf("# averages=%d%n", r.frameCount);
            if (r.snrFreqMin > 0 || r.snrFreqMax > 0) {
                pw.printf("# snr_freq_min_hz=%.3f%n", r.snrFreqMin);
                pw.printf("# snr_freq_max_hz=%.3f%n", r.snrFreqMax);
            }
            if (Double.isFinite(r.fundamentalTrueDbFs)) {
                pw.printf("# manual_fund_dbv=%.4f%n", r.fundamentalTrueDbFs + dbvOffset);
            }
            pw.printf("# thd_max_harmonic=%d%n", r.harmonicCount);
            if (correctionStore != null) {
                for (FreqRespCorrectionStore.Entry e : correctionStore.getEntries()) {
                    pw.printf("# calibration=%s%s%n",
                            e.getPath(), e.isWithNoise() ? " (withNoise)" : "");
                }
            }
            // Bin bandwidth (Hz) the spectrum was captured with — informational,
            // and the loader derives the V/√Hz scale from it so a re-opened file
            // keeps its own scale instead of the then-live config's.  Live
            // results carry null and write the live-config cache.
            double bwSqrt = (r.binBwSqrt != null) ? r.binBwSqrt : prefs.getBinBwSqrt();
            pw.printf("# bin_bw_hz=%.9f%n", bwSqrt * bwSqrt);
            if (imd) {
                pw.printf("# tone1_hz=%.6f%n", tone1Hz);
                pw.printf("# tone2_hz=%.6f%n", tone2Hz);
            }
            pw.println("frequency_hz;magnitude_dBV;phase_deg");
            for (int k = 0; k < r.amplitudeDbFs.length; k++) {
                double f   = k * r.freqResolution;
                double dbv = r.amplitudeDbFs[k] + dbvOffset;
                double ph  = (r.phaseDeg != null && k < r.phaseDeg.length) ? r.phaseDeg[k] : 0;
                pw.printf("%.6f;%.6f;%.6f%n", f, dbv, ph);
            }
        }
    }
}
