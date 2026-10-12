/*
 * Phonalyser - precision audio measurement workbench.
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
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.CaptureEndReason;

import lombok.Getter;

/**
 * Controller of the FFT pane: owns the {@link FftAnalyzerWorker}'s
 * recording lifecycle and the {@code .fft} spectrum file round-trip.  The generator
 * frequency-lock loops that publish {@link Events#GENERATOR_FREQ_TRIM} /
 * {@code _2} run in the worker, on every analysed tick; the controller
 * only relays their reset ({@link #resetFrequencyLock}).
 *
 * <p>The worker is constructor-injected - the pane builds it (the worker's
 * result hand-off marshals through the SWT {@code Display}) and hands it
 * over; the controller itself never imports SWT.  The dual-tone IMD products
 * travel on the result itself ({@link FftResult#imd}), filled by the
 * statistics recompute of the finalized frame; the controller never
 * references the view - everything it emits goes over the bus.
 */
public final class FftController {

    /** Minimum fundamental frequency a loaded spectrum's peak search
     *  considers (Hz) - skips the DC/mains foot. */
    private static final double LOADED_FUND_MIN_HZ = 10.0;

    /** Analysis engine (capture + FFT + averaging on a daemon thread). */
    private final FftAnalyzerWorker worker;
    /** Calibration entries appended to saved spectra as provenance.  Owned by
     *  the controller; its mutations publish {@link Events#FFT_CALIBRATION_CHANGED},
     *  except on the offscreen screenshot copy, whose store is silent. */
    @Getter
    private final CorrectionStore correctionStore;

    /** A loaded {@code .fft} spectrum: the reconstructed result, carrying the
     *  re-measured IMD products in {@link FftResult#imd} when the file was
     *  captured in IMD mode ({@code null} for THD captures - the view stays in
     *  THD mode). */
    public record LoadedSpectrum(FftResult result) {}

    /** @param offScreen the offscreen screenshot copy's controller: its store
     *                   announces no change, so loading its rows reaches no
     *                   live view */
    public FftController(FftAnalyzerWorker worker, boolean offScreen) {
        this.worker          = worker;
        this.correctionStore = new CorrectionStore("FFT", offScreen ? null : Events.FFT_CALIBRATION_CHANGED);
    }

    // -------------------------------------------------------------------------
    // Recording lifecycle (delegates to the worker)
    // -------------------------------------------------------------------------

    /** Publishes a worker result still parked in the hand-off slot - the
     *  render heartbeat's pull half of the delivery, see
     *  {@link FftAnalyzerWorker#drainPendingResult()}.  UI thread only. */
    public void drainPendingResult() {
        worker.drainPendingResult();
    }

    /** Starts the analyser on its daemon worker thread (idempotent); the
     *  worker acquires its own shared-capture reference. */
    public void startRecording() {
        worker.start();
    }

    /** Stops the analyser and interrupts the worker (idempotent); the
     *  worker releases its shared-capture reference and resets its own
     *  frequency lock - the locked clock-drift estimate is only meaningful
     *  while the capture is live. */
    public void stopRecording() {
        worker.stop();
    }

    /** True while the analyser worker is running. */
    public boolean isRecording() {
        return worker.isRunning();
    }

    /** WHY the last recording ended from below ({@code null} for a user stop
     *  or a stop-after-N pause) - the pane consults this on the auto-stop
     *  event and localizes it. */
    public CaptureEndReason captureEndReason() {
        return worker.getCaptureEndReason();
    }

    /** Averages performed since the last reset, unbounded. */
    public int completedAnalyses() {
        return worker.getCompletedAnalyses();
    }

    /** Averages currently inside the average - the number every "N averages"
     *  readout states; the window length once a moving average has filled it
     *  (see {@link FftAnalyzerWorker#getAveragesInWindow()}). */
    public int averagesInWindow() {
        return worker.getAveragesInWindow();
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
     *  FLL - see {@link #resetFrequencyLock()}. */
    public void resetStatistics() {
        worker.resetStatistics();
    }

    /** {@link #resetStatistics()} variant for resets caused by a change of
     *  the GENERATED SIGNAL: the worker additionally skips the DAC-buffer
     *  drain after re-anchoring, so the first fresh window (and the FLL's
     *  first measurement) hold none of the old tone. */
    public void resetStatisticsAfterSignalChange() {
        worker.resetStatisticsAfterSignalChange();
    }

    /** The UI consumed the published result - the worker's result-throttle
     *  handshake. */
    public void resultConsumed() {
        worker.uiGotResult();
    }

    /** Relays a frequency-lock reset to the worker, which owns the loops -
     *  see {@link FftAnalyzerWorker#resetFrequencyLock()}.  Called when the
     *  user turns alignment ON, on a change of the generated signal and on
     *  Record stop; kept separate from {@link #resetStatistics()} so a manual
     *  statistics reset keeps the converged alignment intact. */
    public void resetFrequencyLock() {
        worker.resetFrequencyLock();
    }

    /** Stops the worker - called by {@code UIEngines} at application exit. */
    public void shutdown() {
        worker.stop();
    }

    /** The dBV↔dBFS offset of the channel the FFT analyses - the input
     *  device's calibration in force for that channel. */
    private double analysedDbvOffsetDb() {
        return AudioBackend.instance().analysedChannelDbvOffsetDb();
    }

    // -------------------------------------------------------------------------
    // .fft spectrum file round-trip
    // -------------------------------------------------------------------------

    /** Loads a spectrum {@code .fft} file (as written by
     *  {@link #saveSpectrum} - {@code frequency_hz;magnitude_dBV;phase_deg}
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
        double fundHz  = Double.NaN;
        double binBwHz = Double.NaN;
        FftOverlap overlap = null;
        int stepSamples = 0;
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
                if (s.startsWith("# fundamental_hz=")) { fundHz = parseHeaderHz(s); continue; }
                if (s.startsWith("# bin_bw_hz=")) { binBwHz = parseHeaderHz(s); continue; }
                if (s.startsWith("# overlap=")) {
                    // Known enum names only; anything else (old files write
                    // nothing, null results wrote "n/a") stays null.
                    try {
                        overlap = FftOverlap.valueOf(s.substring(s.indexOf('=') + 1).trim());
                    } catch (IllegalArgumentException ignored) { }
                    continue;
                }
                if (s.startsWith("# step_samples=")) {
                    double v = parseHeaderHz(s);
                    if (v > 0) stepSamples = (int) Math.round(v);
                    continue;
                }
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
        // Restored capture geometry: files without the headers (or with an
        // unknown overlap token) keep null / 0, and the skirt walk degrades
        // to its no-overlap look-ahead exactly as before these headers.
        r.overlap          = overlap;
        r.stepSamples      = stepSamples;
        r.harmonicCount    = harmCount;
        // The file stores dBV; dBFS is the result's base scale, so subtract the
        // analysed channel's ADC offset once on load (the writer adds it back
        // on save, so a round-trip is exact).
        double dbvOffsetDb = analysedDbvOffsetDb();
        for (int k = 0; k < n; k++) {
            r.amplitudeDbFs[k] = rows.get(k)[1] - dbvOffsetDb;
            r.phaseDeg[k]      = rows.get(k)[2];
        }
        // From here the spectrum is measured, not parsed: the analyser bound to
        // it rebuilds the bin phasors from the stored magnitude and phase.
        FftAnalyzer storedAnalyzer = new FftAnalyzer(r);
        storedAnalyzer.rebuildPhasors();
        // A file captured in IMD mode stands on its two tones: the saved tone
        // frequencies are pinned (F1 = the lower, which is then the stated
        // fundamental, F2 = the upper) so the result reports them.  The IMD
        // products then come out of the recompute below with the rest of the
        // statistics; a THD capture carries no second tone and gets no IMD
        // result, which keeps the view in single-tone THD mode.
        boolean tonesStated = modeImd && tone1Hz > 0 && tone2Hz > 0;
        if (tonesStated) {
            fundHz = Math.min(tone1Hz, tone2Hz);
        }
        // The fundamental as the capture stated it; only a file that carries
        // none is searched, which is what the header exists to avoid - a search
        // can settle on a different bin than the capture measured.
        if (fundHz > 0.0) {
            r.fundamentalBin       = (int) Math.round(fundHz / freqRes);
            r.fundamentalHz        = r.fundamentalBin * freqRes;
            r.fundamentalHzRefined = fundHz;
        } else {
            storedAnalyzer.findFundamental(LOADED_FUND_MIN_HZ);
        }
        // An IMD-mode file without tone headers has its second tone searched
        // in the stored spectrum, partnered with the fundamental just set.
        if (tonesStated) {
            r.fundamental2HzRefined = Math.max(tone1Hz, tone2Hz);
        } else if (modeImd) {
            storedAnalyzer.findSecondTone();
        }
        // Derive harmonic positions, apply the current THD settings and recompute
        // - shared with the dynamic recompute that runs when a THD setting changes
        // while this static spectrum is displayed.
        recomputeStaticResult(r);
        return new LoadedSpectrum(r);
    }

    /**
     * Re-applies the current THD settings to an already-built static spectrum
     * and recomputes its harmonics / THD / SNR: derives harmonic positions for
     * the max-harmonics count (resizing the harmonic arrays if it changed - the
     * stored spectrum is preserved), sets the manual-fundamental amplitude anchor
     * (Vrms &rarr; dBFS) and the high-pass / low-pass distortion band, then runs
     * {@link FftAnalyzer#recomputeStats}.  Called at load and whenever a THD
     * setting changes while a static (non-live) spectrum is displayed - the live
     * worker re-reads these settings every tick, so only the static case needs it.
     */
    public void recomputeStaticResult(FftResult r) {
        Preferences prefs = Preferences.instance();
        double manualVrms = prefs.getFftManualFundVrms();
        double fundRefDbFs = (prefs.isFftManualFundEnabled() && manualVrms > 0)
                ? 20.0 * Math.log10(manualVrms) - analysedDbvOffsetDb()
                : Double.NaN;
        // H(N) counted from the 2nd is N - 1 harmonics - the live worker's rule.
        new FftAnalyzer(r).applyDistortionSettings(
                Math.max(9, prefs.getFftCalcMaxHarmonic()),
                Math.max(1, prefs.getFftThdMaxHarmonic() - 1),
                fundRefDbFs,
                prefs.isFftDistMinEnabled() ? prefs.getFftDistMinHz() : 0,
                prefs.isFftDistMaxEnabled() ? prefs.getFftDistMaxHz() : 0);
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

    /** Writes {@code r} as a {@code .fft} spectrum file -
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
            double dbvOffset = analysedDbvOffsetDb();   // dBV = dBFs + the analysed channel's ADC offset
            pw.println("# Phonalyser FFT spectrum");
            pw.println("# format_version=" + FileVersions.FFT_SPECTRUM);
            pw.println("# mode=" + (imd ? "IMD" : "THD"));
            pw.printf("# fft_size=%d%n", r.fftSize);
            pw.printf("# sample_rate_hz=%d%n", r.sampleRate);
            pw.printf("# window=%s%n", r.windowType != null ? r.windowType.name() : "n/a");
            // Overlap as the stable enum NAME (the loader restores it; unknown
            // tokens stay null) plus the actual frame spacing - under the
            // random overlap the enum alone carries no geometry, and the
            // spacing is what the reloaded skirt walk keys on.
            pw.printf("# overlap=%s%n", r.overlap != null ? r.overlap.name() : "n/a");
            if (r.stepSamples > 0) {
                pw.printf("# step_samples=%d%n", r.stepSamples);
            }
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
            // The fundamental as this capture measured it, so a re-opened file
            // stands on the same tone instead of a peak search of its own.
            if (r.fundamentalHzRefined > 0) {
                pw.printf("# fundamental_hz=%.6f%n", r.fundamentalHzRefined);
            }
            if (correctionStore != null) {
                for (CorrectionStore.Entry e : correctionStore.getEntries()) {
                    pw.printf("# calibration=%s%s%n",
                            e.getPath(), e.isWithNoise() ? " (withNoise)" : "");
                }
            }
            // Bin bandwidth (Hz) the spectrum was captured with - informational,
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
