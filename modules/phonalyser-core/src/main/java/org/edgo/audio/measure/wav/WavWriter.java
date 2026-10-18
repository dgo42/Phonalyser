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

package org.edgo.audio.measure.wav;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import lombok.extern.log4j.Log4j2;

/**
 * Writes a PCM or IEEE-float WAV file.
 * The header is written as a placeholder on open and finalized (with correct sizes) on close.
 */
@Log4j2
public class WavWriter implements AutoCloseable {

    private final File             file;
    private final int              sampleRate;
    private final int              channels;
    private final int              bitsPerSample;
    private final boolean          floatFormat;   // true = IEEE float (fmt code 3), false = PCM (fmt code 1)
    private final RandomAccessFile raf;
    private       long             dataBytes = 0;

    /**
     * @param floatFormat  true for a 32-bit IEEE-float header (format code 3),
     *                     false for integer PCM; the samples come through writeRaw
     */
    public WavWriter(File file, int sampleRate, int channels, int bitsPerSample, boolean floatFormat)
            throws IOException {
        this.file          = file;
        this.sampleRate    = sampleRate;
        this.channels      = channels;
        this.bitsPerSample = bitsPerSample;
        this.floatFormat   = floatFormat;
        file.getParentFile().mkdirs();
        this.raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        writeHeader(0); // placeholder - finalized in close()
    }

    /** Writes raw little-endian PCM bytes (for integer PCM mode). */
    public void writeRaw(byte[] buf, int length) throws IOException {
        raf.write(buf, 0, length);
        dataBytes += length;
    }

    @Override
    public void close() throws IOException {
        raf.seek(0);
        writeHeader(dataBytes);
        raf.close();
        log.info("WAV written: {}  ({} bytes PCM data)", file.getAbsolutePath(), String.format("%,d", dataBytes));
    }

    private void writeHeader(long dataSizeBytes) throws IOException {
        int blockAlign  = channels * (bitsPerSample / 8);
        int byteRate    = sampleRate * blockAlign;
        int audioFormat = floatFormat ? 3 : 1;

        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes());
        h.putInt((int)(36 + dataSizeBytes));   // RIFF chunk size
        h.put("WAVE".getBytes());
        h.put("fmt ".getBytes());
        h.putInt(16);                           // fmt chunk size
        h.putShort((short) audioFormat);
        h.putShort((short) channels);
        h.putInt(sampleRate);
        h.putInt(byteRate);
        h.putShort((short) blockAlign);
        h.putShort((short) bitsPerSample);
        h.put("data".getBytes());
        h.putInt((int) dataSizeBytes);
        raf.write(h.array());
    }
}
