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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.edgo.audio.measure.common.SpectrumStatistics;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ReadoutSettings;

/**
 * Pins the {@code .fft} spectrum file format via
 * {@link FftController#saveSpectrum} / {@link FftController#loadSpectrum}
 * round-trips - headless (the controller's file I/O never touches the
 * injected worker, so it is constructed with {@code null} collaborators).
 *
 * <p>The writer emits dBV ({@code dBFS + dbvOffsetDb}); the loader
 * subtracts the same offset, so amplitudes must round-trip to the
 * writer's 1e-6 print precision regardless of the calibration value.
 * The writer works the phase out of each bin's phasor; the loader keeps
 * it as read.
 *
 * <p>The loader parses only; which table a loaded spectrum shows is decided
 * by the statistics the view calculates on it, so the mode checks run them.
 */
class FftControllerSpectrumIoTest {

    /** Half-spectrum bins of the synthetic result (power-of-2 + 1 so
     *  {@code fftSize = 2·(n−1)} is a clean 1024). */
    private static final int BINS = 513;
    /** Bin spacing chosen so every printed frequency is exact at the
     *  writer's 6-decimal precision: 384000 / 1024. */
    private static final double FREQ_RES_HZ = 375.0;
    /** Writer prints 6 decimals; allow one ULP of that. */
    private static final double PRINT_EPS = 1e-5;
    /** How far the synthetic power average stands above the magnitude - any
     *  offset that tells the two columns apart. */
    private static final double POWER_ABOVE_MEAN_DB = 12.0;
    /** The readout settings the mode checks calculate the statistics with:
     *  no harmonic ladder, no distortion band, no manual fundamental. */
    private static final ReadoutSettings SETTINGS = new ReadoutSettings(0, 0, 0.0, 0.0, Double.NaN);

    @TempDir
    Path tmp;

    /** A controller for the file round-trip.
     *
     * @return the controller */
    private FftController io() {
        // saveSpectrum/loadSpectrum use neither the pane, nor the worker
        // (built, never started), nor (with no calibration entries to list)
        // the correction store.
        return new FftController(null);
    }

    /** Synthetic half-spectrum: −150 dBFS floor, one −10 dBFS peak at
     *  {@code peakBin}, deterministic phase ramp on the phasors, analysed
     *  with Hann.
     *
     * @param peakBin the peak's bin
     * @return the spectrum */
    private FftResult syntheticResult(int peakBin) {
        FftResult r = new FftResult();
        r.ensureArrays(BINS, 9);
        r.freqResolution    = FREQ_RES_HZ;
        r.fftSize           = 2 * (BINS - 1);
        r.sampleRate        = (int) Math.round(FREQ_RES_HZ * r.fftSize);
        r.harmonicCount     = 9;
        r.frameCount        = 4;
        r.coherentAveraging = true;
        r.binBwSqrt         = Math.sqrt(0.5);
        r.windowType        = WindowType.HANN;
        r.fundamentalTrueDbFs = Double.NaN;
        for (int k = 0; k < BINS; k++) {
            double phaseRad = Math.toRadians((k % 360) - 180.0);
            r.amplitudeDbFs[k] = -150.0;
            r.re[k]            = Math.cos(phaseRad);
            r.im[k]            = Math.sin(phaseRad);
        }
        r.amplitudeDbFs[peakBin] = -10.0;
        return r;
    }

    /** The view's statistics on a loaded spectrum - what decides its table.
     *
     * @param r the loaded spectrum
     * @return {@code r}, its statistics calculated */
    private FftResult withStatistics(FftResult r) {
        new SpectrumStatistics().calculateStat(r, SETTINGS, null);
        return r;
    }

    /** A THD capture round-trips its spectrum, phase and geometry, and
     *  stands on its rediscovered peak.
     *
     * @throws IOException when the file cannot be written or read */
    @Test
    void thdRoundTrip_preservesSpectrumAndGeometry() throws IOException {
        FftResult original = syntheticResult(100);
        Path file = tmp.resolve("thd.fft");
        io().saveSpectrum(file, original, false, Double.NaN, Double.NaN);

        FftController.LoadedSpectrum loaded = io().loadSpectrum(file.toString());
        FftResult r = loaded.result();
        assertNull(withStatistics(r).imd, "THD capture must not switch the view to IMD");
        assertEquals(original.fftSize,       r.fftSize);
        assertEquals(original.sampleRate,    r.sampleRate);
        assertEquals(FREQ_RES_HZ,            r.freqResolution, PRINT_EPS);
        assertEquals(0.5, r.binBwSqrt * r.binBwSqrt, 1e-9,
                "bin_bw_hz header must restore the captured bandwidth");
        for (int k = 0; k < BINS; k++) {
            assertEquals(original.amplitudeDbFs[k], r.amplitudeDbFs[k], PRINT_EPS,
                    "amplitude round-trip at bin " + k);
            assertEquals(Math.toDegrees(Math.atan2(original.im[k], original.re[k])), r.phaseDeg[k],
                    PRINT_EPS, "phase round-trip at bin " + k);
        }
        assertEquals(100, r.fundamentalBin, "peak rediscovered as the fundamental");
        assertEquals(100 * FREQ_RES_HZ, r.fundamentalHz, PRINT_EPS);
    }

    /** An IMD capture comes back as a tone pair the statistics read in IMD
     *  mode, F1 the lower tone and F2 the upper.
     *
     * @throws IOException when the file cannot be written or read */
    @Test
    void imdRoundTrip_restoresModeAndToneFrequencies() throws IOException {
        FftResult original = syntheticResult(100);
        // Second tone for the dual-tone capture.
        original.amplitudeDbFs[110] = -10.0;
        double tone1 = 100 * FREQ_RES_HZ;   // 37500 Hz
        double tone2 = 110 * FREQ_RES_HZ;   // 41250 Hz
        Path file = tmp.resolve("imd.fft");
        io().saveSpectrum(file, original, true, tone1, tone2);

        FftController.LoadedSpectrum loaded = io().loadSpectrum(file.toString());
        assertNotNull(withStatistics(loaded.result()).imd, "IMD capture must restore the IMD table mode");
        assertEquals(tone1, loaded.result().fundamentalHzRefined,  PRINT_EPS,
                "lower tone pinned as F1");
        assertEquals(tone2, loaded.result().fundamental2HzRefined, PRINT_EPS,
                "upper tone pinned as F2");
    }

    /** A file without the bandwidth header reads its row spacing as the
     *  captured bandwidth.
     *
     * @throws IOException when the file cannot be written or read */
    @Test
    void load_oldFileWithoutBandwidthHeader_fallsBackToRowSpacing() throws IOException {
        FftResult original = syntheticResult(100);
        Path file = tmp.resolve("old.fft");
        io().saveSpectrum(file, original, false, Double.NaN, Double.NaN);
        // Strip the bin_bw_hz header the way pre-versioned files lack it.
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("# bin_bw_hz="))
                .collect(Collectors.toList());
        Files.write(file, lines, StandardCharsets.UTF_8);

        FftResult r = io().loadSpectrum(file.toString()).result();
        assertEquals(Math.sqrt(FREQ_RES_HZ), r.binBwSqrt, 1e-6,
                "old files: the row spacing IS the captured bandwidth");
    }

    /** A file with no usable rows, a missing file and an unparseable number
     *  are refused.
     *
     * @throws IOException when a fixture file cannot be written */
    @Test
    void load_rejectsGarbageAndMissingFiles() throws IOException {
        Path garbage = tmp.resolve("garbage.fft");
        Files.writeString(garbage, "this is not a spectrum\nat all\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> io().loadSpectrum(garbage.toString()),
                "no usable rows -> format error");
        assertThrows(IOException.class,
                () -> io().loadSpectrum(tmp.resolve("missing.fft").toString()),
                "unreadable file -> IO error");

        Path numeric = tmp.resolve("badnumber.fft");
        Files.writeString(numeric,
                "frequency_hz;magnitude_dBV;phase_deg\n0.0;abc;0.0\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> io().loadSpectrum(numeric.toString()),
                "unparseable number -> IO error");
    }

    /** A coherent average's power average round-trips in a fourth column.
     *
     * @throws IOException when the file cannot be written or read */
    @Test
    void aCoherentAveragesPowerAverageRoundTripsInItsOwnColumn() throws IOException {
        FftResult original = syntheticResult(100);
        original.powerAverageDbFs = new double[BINS];
        for (int k = 0; k < BINS; k++) {
            original.powerAverageDbFs[k] = original.amplitudeDbFs[k] + POWER_ABOVE_MEAN_DB;
        }
        original.hasPowerAverage = true;
        Path file = tmp.resolve("coherent.fft");
        io().saveSpectrum(file, original, false, Double.NaN, Double.NaN);

        assertTrue(Files.readString(file, StandardCharsets.UTF_8)
                        .contains("frequency_hz;magnitude_dBV;phase_deg;power_dBV"),
                "the power column is named in the column header");
        FftResult r = io().loadSpectrum(file.toString()).result();
        assertTrue(r.hasPowerAverage, "the loaded result carries the power average");
        for (int k = 0; k < BINS; k++) {
            assertEquals(original.powerAverageDbFs[k], r.powerAverageDbFs[k], PRINT_EPS,
                    "power round-trip at bin " + k);
        }
    }

    /** A single frame's file keeps three columns and reads no power average.
     *
     * @throws IOException when the file cannot be written or read */
    @Test
    void aResultWithoutPowerAverageWritesAndReadsNone() throws IOException {
        Path file = tmp.resolve("single.fft");
        io().saveSpectrum(file, syntheticResult(100), false, Double.NaN, Double.NaN);

        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("power_dBV"),
                "a single frame's file keeps three columns");
        assertFalse(io().loadSpectrum(file.toString()).result().hasPowerAverage,
                "its power is read off the magnitude");
    }

    /** The save writes the format version, the capture mode and the column
     *  header.
     *
     * @throws IOException when the file cannot be written or read */
    @Test
    void save_writesModeAndVersionHeaders() throws IOException {
        Path file = tmp.resolve("headers.fft");
        io().saveSpectrum(file, syntheticResult(50), false, Double.NaN, Double.NaN);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("# format_version="), "format version header");
        assertTrue(text.contains("# mode=THD"), "capture mode header");
        assertTrue(text.contains("frequency_hz;magnitude_dBV;phase_deg"), "column header");
    }
}
