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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.edgo.audio.measure.fft.FftResult;

/**
 * Pins the {@code .fft} spectrum file format via
 * {@link FftController#saveSpectrum} / {@link FftController#loadSpectrum}
 * round-trips — headless (the controller's file I/O never touches the
 * injected worker, so it is constructed with {@code null} collaborators).
 *
 * <p>The writer emits dBV ({@code dBFS + dbvOffsetDb}); the loader
 * subtracts the same offset, so amplitudes must round-trip to the
 * writer's 1e-6 print precision regardless of the calibration value.
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

    @TempDir
    Path tmp;

    private FftController io() {
        // saveSpectrum/loadSpectrum use neither the worker nor (with no
        // calibration entries to list) the correction store.
        return new FftController(null);
    }

    /** Synthetic half-spectrum: −150 dBFS floor, one −10 dBFS peak at
     *  {@code peakBin}, deterministic phase ramp. */
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
        r.fundamentalTrueDbFs = Double.NaN;
        for (int k = 0; k < BINS; k++) {
            r.amplitudeDbFs[k] = -150.0;
            r.phaseDeg[k]      = (k % 360) - 180.0;
        }
        r.amplitudeDbFs[peakBin] = -10.0;
        return r;
    }

    @Test
    void thdRoundTrip_preservesSpectrumAndGeometry() throws IOException {
        FftResult original = syntheticResult(100);
        Path file = tmp.resolve("thd.fft");
        io().saveSpectrum(file, original, false, Double.NaN, Double.NaN);

        FftController.LoadedSpectrum loaded = io().loadSpectrum(file.toString());
        FftResult r = loaded.result();
        assertNull(loaded.imd(), "THD capture must not switch the view to IMD");
        assertEquals(original.fftSize,       r.fftSize);
        assertEquals(original.sampleRate,    r.sampleRate);
        assertEquals(FREQ_RES_HZ,            r.freqResolution, PRINT_EPS);
        assertEquals(0.5, r.binBwSqrt * r.binBwSqrt, 1e-9,
                "bin_bw_hz header must restore the captured bandwidth");
        for (int k = 0; k < BINS; k++) {
            assertEquals(original.amplitudeDbFs[k], r.amplitudeDbFs[k], PRINT_EPS,
                    "amplitude round-trip at bin " + k);
            assertEquals(original.phaseDeg[k], r.phaseDeg[k], PRINT_EPS,
                    "phase round-trip at bin " + k);
        }
        assertEquals(100, r.fundamentalBin, "peak rediscovered as the fundamental");
        assertEquals(100 * FREQ_RES_HZ, r.fundamentalHz, PRINT_EPS);
    }

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
        assertNotNull(loaded.imd(), "IMD capture must restore the IMD table mode");
        assertEquals(tone1, loaded.result().fundamentalHzRefined,  PRINT_EPS,
                "lower tone pinned as F1");
        assertEquals(tone2, loaded.result().fundamental2HzRefined, PRINT_EPS,
                "upper tone pinned as F2");
    }

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

    @Test
    void load_rejectsGarbageAndMissingFiles() throws IOException {
        Path garbage = tmp.resolve("garbage.fft");
        Files.writeString(garbage, "this is not a spectrum\nat all\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> io().loadSpectrum(garbage.toString()),
                "no usable rows → format error");
        assertThrows(IOException.class,
                () -> io().loadSpectrum(tmp.resolve("missing.fft").toString()),
                "unreadable file → IO error");

        Path numeric = tmp.resolve("badnumber.fft");
        Files.writeString(numeric,
                "frequency_hz;magnitude_dBV;phase_deg\n0.0;abc;0.0\n", StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> io().loadSpectrum(numeric.toString()),
                "unparseable number → IO error");
    }

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
