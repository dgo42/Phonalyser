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

package org.edgo.audio.measure.adc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.edgo.audio.measure.common.StereoSample;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Dense histogram over all 2^bitDepth ADC codes.
 *
 * For bitDepth ≤ 24: single int[2^bitDepth] array.
 * For bitDepth == 32: 8 chunked int[2^29] arrays (~16 GB total).
 *
 * Bin index for a sample:
 *   ≤ 24-bit  →  sample & (binCount - 1)   (masks to lower bitDepth bits)
 *   32-bit    →  unsigned chunk lookup
 */
@Log4j2
public class AdcHistogram {

    // 32-bit chunk constants
    private static final int CHUNK_BITS = 29;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS; // 536,870,912
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    @Getter
    private final int bitDepth;
    @Getter
    private final long binCount;

    // ≤ 24-bit storage
    private final int[] bins;

    // 32-bit storage
    private final int[][] chunks;

    @Getter
    private long totalCount;
    @Getter
    private long uniqueCodes;
    @Getter
    private long minCount = Long.MAX_VALUE;
    @Getter
    private long maxCount = Long.MIN_VALUE;

    public AdcHistogram(int bitDepth) {
        this.bitDepth = bitDepth;
        this.binCount = 1L << bitDepth;

        if (bitDepth < 32) {
            bins   = new int[(int) binCount];
            chunks = null;
        } else {
            bins   = null;
            chunks = new int[8][];
            for (int i = 0; i < 8; i++) {
                chunks[i] = new int[CHUNK_SIZE];
            }
        }
        log.info("AdcHistogram: {} bits, {} bins", bitDepth, String.format("%,d", binCount));
    }

    public void record(StereoSample code) {
        int currentBin;
        if (bitDepth < 32) {
            int idx = code.ch1 & (int)(binCount - 1);
            if (bins[idx]++ == 0) {
                uniqueCodes++;
            }
            currentBin = bins[idx];
        } else {
            if (chunks[code.ch1 >>> CHUNK_BITS][code.ch1 & CHUNK_MASK]++ == 0) {
                uniqueCodes++;
            }
            currentBin = chunks[code.ch1 >>> CHUNK_BITS][code.ch1 & CHUNK_MASK];
        }
        if (maxCount < currentBin) {
            maxCount = currentBin;
        }
        if (minCount > currentBin) {
            minCount = currentBin;
        }
        totalCount++;
    }

    public void record(StereoSample[] codes) {
        for (StereoSample c : codes) {
            record(c);
        }
    }

    public int getCount(long code) {
        if (bitDepth < 32) {
            return bins[(int)(code & (long)(binCount - 1))];
        } else {
            return chunks[(int)(code >>> CHUNK_BITS)][(int)(code & CHUNK_MASK)];
        }
    }

    /**
     * Loads a histogram from a CSV file previously written by {@link #exportCsv}
     * into this instance.  The CSV must have columns: code_unsigned, count.
     * Counts overwrite any existing bins; running totals are recalculated from
     * the loaded values.
     */
    public void loadCsv(String filePath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols         = line.split(";");
                long     codeUnsigned = Long.parseUnsignedLong(cols[0].trim());
                int      count        = Integer.parseInt(cols[1].trim());
                if (count <= 0) {
                    continue;
                }
                if (bitDepth < 32) {
                    bins[(int) codeUnsigned] = count;
                } else {
                    chunks[(int)(codeUnsigned >>> CHUNK_BITS)][(int)(codeUnsigned & CHUNK_MASK)] = count;
                }
                if (maxCount < count) {
                    maxCount = count;
                }
                if (minCount > count) {
                    minCount = count;
                }
                totalCount  += count;
                uniqueCodes++;
            }
        }
        log.info("Histogram loaded: {} bins, {} total samples, {} unique codes, min count: {}, max count: {}",
                String.format("%,d", binCount),
                String.format("%,d", totalCount),
                String.format("%,d", uniqueCodes),
                String.format("%,d", minCount),
                String.format("%,d", maxCount));
    }

    public String exportCsv(String directory) throws IOException {
        String filename = "histogram_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        File outFile = new File(directory, filename);

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("code_unsigned;count");
            if (bitDepth < 32) {
                for (int i = 0; i < (int) binCount; i++) {
                    if (bins[i] != 0) {
                        pw.printf("%d;%d%n", (long) i, bins[i]);
                    }
                }
            } else {
                for (int c = 0; c < 8; c++) {
                    int[] chunk = chunks[c];
                    for (int i = 0; i < CHUNK_SIZE; i++) {
                        if (chunk[i] != 0) {
                            long unsignedCode = (long) c * CHUNK_SIZE + i;
                            pw.printf("%d;%d%n", unsignedCode, chunk[i]);
                        }
                    }
                }
            }
        }

        log.info("Histogram exported: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }
}
