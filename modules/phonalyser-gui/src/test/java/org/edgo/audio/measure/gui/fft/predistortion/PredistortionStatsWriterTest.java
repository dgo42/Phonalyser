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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Record formatting of the predistortion diagnostic file: synthetic values in,
 * the expected CSV lines out.
 */
class PredistortionStatsWriterTest {

    private static final String HEADER =
            "type;round;averages;hop_samples;noise_dbr;f1_hz;f1_mag_db;f1_phase_deg"
            + ";h2_hz;h2_mag_db;h2_phase_deg;h3_hz;h3_mag_db;h3_phase_deg"
            + ";h4_hz;h4_mag_db;h4_phase_deg;h5_hz;h5_mag_db;h5_phase_deg"
            + ";h6_hz;h6_mag_db;h6_phase_deg;h7_hz;h7_mag_db;h7_phase_deg"
            + ";h8_hz;h8_mag_db;h8_phase_deg;h9_hz;h9_mag_db;h9_phase_deg"
            + ";prod_a;prod_b;prod_hz;prod_mag_db;prod_phase_deg";

    /** An empty component triple - a harmonic the record has no value for. */
    private static final String NONE = ";;;";
    /** The five trailing product columns, empty - every record but the
     *  per-product probe leaves them so. */
    private static final String NO_PRODUCT = ";;;;;";

    /** Where the writer puts this run's file.  Not private: JUnit refuses to
     *  inject a temporary directory into a private field. */
    @TempDir
    Path logsDir;

    @Test
    void frameRecordIsFlushedAndWrittenOncePerAnalysis() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter(logsDir);
        FftResult r = syntheticResult();
        writer.frame(0, r);
        writer.frame(0, r);                 // same analysis window - already recorded

        // Read while the writer is still open: every record is flushed as it
        // is written, so an aborted run keeps its tail.
        List<String> live = recordLines();
        assertEquals(2, live.size());
        assertEquals("FRAME;0;7;4096;-80,0000"
                + ";1000,000000;-20,0000;0,0000"
                + ";2000,000000;-70,0000;0,0000"
                + ";3000,000000;-90,0000;90,0000"
                + NONE.repeat(6)                        // H4..H9 were not measured
                + NO_PRODUCT,
                live.get(1));

        r.samplesAbsStart = 99_999;                     // the next analysis window
        writer.frame(1, r);
        writer.close();
        List<String> lines = recordLines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).startsWith("FRAME;1;7;4096;-80,0000;"), lines.get(2));
    }

    @Test
    void roundRecordCarriesTheAveragedMeasurement() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter(logsDir);
        writer.round(3, syntheticResult());
        writer.close();

        List<String> lines = recordLines();
        assertEquals(2, lines.size());
        assertEquals("ROUND;3;7;4096;-80,0000"
                + ";1000,000000;-20,0000;0,0000"
                + ";2000,000000;-70,0000;0,0000"
                + ";3000,000000;-90,0000;90,0000"
                + NONE.repeat(6)
                + NO_PRODUCT,
                lines.get(1));
    }

    @Test
    void productRoundRecordsResidualsAndTheSecondToneReference() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter(logsDir);
        FftResult r = syntheticResult();
        r.fundamental2HzRefined = 1100.0;
        // Raw product phasors: 1e-3 at 0 degrees, 5e-4 at +90 degrees, against
        // the frame's -20 dBFS fundamental reference.
        ProductPhasors raw = new ProductPhasors(
                new int[]{ 2, 1 }, new int[]{ 0, 1 },
                new double[]{ 1.0e-3, 0.0 }, new double[]{ 0.0, 5.0e-4 },
                new double[]{ 2000.0, 2100.0 });
        writer.productRound(4, r, raw, new double[]{ 0.0, -0.5 });   // F2: 0.5 at -90 degrees
        writer.close();

        List<String> lines = recordLines();
        assertEquals(4, lines.size());
        assertEquals(productRow("PRODROUND", 4, 2, 0, "2000,000000", "-40,0000", "0,0000"),
                lines.get(1));
        assertEquals(productRow("PRODROUND", 4, 1, 1, "2100,000000", "-46,0206", "90,0000"),
                lines.get(2));
        // The second tone itself - the reference arg(F2) the dual de-rotation
        // needs, beside arg(F1) in the ROUND record of the same round.
        assertEquals(productRow("PRODROUND", 4, 0, 1, "1100,000000", "13,9794", "-90,0000"),
                lines.get(3));
    }

    @Test
    void productCorrectionRecordsWhatWentToTheGenerator() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter(logsDir);
        writer.productCorrection(1, new IntermodCompensation.GeneratorCorrections(
                new double[]{ 0.01, 0.001 }, new int[]{ 2, 1 }, new int[]{ 0, 1 },
                new double[]{ Math.PI, -Math.PI / 2.0 }), 1000.0, 1100.0);
        writer.close();

        List<String> lines = recordLines();
        assertEquals(3, lines.size());
        assertEquals(productRow("PRODAPPLY", 1, 2, 0, "2000,000000", "-40,0000", "180,0000"),
                lines.get(1));
        assertEquals(productRow("PRODAPPLY", 1, 1, 1, "2100,000000", "-60,0000", "-90,0000"),
                lines.get(2));
    }

    @Test
    void singleToneRecordsAreUnchangedAndCarryNoProductRow() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter(logsDir);
        writer.frame(0, syntheticResult());
        writer.round(0, syntheticResult());
        writer.correction(0, new GeneratorCorrections(
                new double[]{ 0.01 }, new int[]{ 2 }, new double[]{ 0.0 }));
        writer.close();

        List<String> lines = recordLines();
        assertEquals(4, lines.size());
        assertEquals(HEADER, lines.get(0));
        for (String line : lines) {
            assertFalse(line.startsWith("PROD"), line);
            // Every row still spans the same 37 columns as before the dual
            // records existed.
            assertEquals(36L, line.chars().filter(c -> c == ';').count(), line);
        }
    }

    @Test
    void disabledWriterCreatesNoFile() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter();
        writer.frame(0, syntheticResult());
        writer.round(0, syntheticResult());
        writer.correction(0, new GeneratorCorrections(
                new double[]{ 0.01 }, new int[]{ 2 }, new double[]{ 0.0 }));
        writer.close();

        try (Stream<Path> files = Files.list(logsDir)) {
            assertEquals(0, files.count(), "the disabled record writes nothing");
        }
    }

    @Test
    void correctionRecordPlacesEachHarmonicInItsColumn() throws IOException {
        PredistortionStatsWriter writer = new PredistortionStatsWriter(logsDir);
        writer.correction(2, new GeneratorCorrections(
                new double[]{ 0.01, 0.001 },             // -40 dB and -60 dB
                new int[]{ 2, 5 },
                new double[]{ Math.PI, -Math.PI / 2.0 }));
        writer.close();

        List<String> lines = recordLines();
        assertEquals(2, lines.size());
        assertEquals("APPLY;2;0;;"
                + NONE                                   // no fundamental correction
                + ";;-40,0000;180,0000"                  // H2
                + NONE + NONE                            // H3, H4 - not corrected
                + ";;-60,0000;-90,0000"                  // H5
                + NONE.repeat(4)                         // H6..H9
                + NO_PRODUCT,
                lines.get(1));
    }

    /**
     * A frame with a fundamental at 1000 Hz (-20 dBFS, phase 0), H2 at
     * -90 dBFS / 0 degrees, H3 at -110 dBFS / +90 degrees and a flat -100 dBFS
     * noise grass - so the noise floor is -80 dBr and the harmonics -70 and
     * -90 dBr against the fundamental.
     */
    private FftResult syntheticResult() {
        FftResult r = new FftResult();
        r.freqResolution = 100.0;
        r.frameCount     = 7;
        r.stepSamples    = 4_096;
        r.samplesAbsStart = 12_345L;
        r.amplitudeDbFs  = new double[40];
        Arrays.fill(r.amplitudeDbFs, -100.0);
        r.fundamentalBin            = 10;
        r.fundamentalHzRefined      = 1000.0;
        r.fundamentalDbFs           = -20.0;
        r.fundamentalDynExclusionHz = 250.0;
        r.harmonicCount = 2;
        r.harmonicBins  = new int[]{ 20, 30 };
        r.harmonicHz    = new double[]{ 2000.0, 3000.0 };
        r.harmonicDbFs  = new double[]{ -90.0, -110.0 };
        r.rawFundRe = 1.0;
        r.rawFundIm = 0.0;
        r.rawPeakRe = new double[]{ 1.0, 0.0 };
        r.rawPeakIm = new double[]{ 0.0, 2.0 };
        return r;
    }

    /** A product row: the component block empty, the product in the five
     *  trailing columns. */
    private String productRow(String type, int round, int a, int b,
                              String hz, String magDb, String phaseDeg) {
        return type + ";" + round + ";0;;" + NONE.repeat(9)
                + ";" + a + ";" + b + ";" + hz + ";" + magDb + ";" + phaseDeg;
    }

    /** The single file the writer created, line by line. */
    private List<String> recordLines() throws IOException {
        Path file;
        try (Stream<Path> files = Files.list(logsDir)) {
            file = files.findFirst().orElseThrow();
        }
        assertTrue(file.getFileName().toString().startsWith("dpd-stats"),
                file.getFileName().toString());
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
