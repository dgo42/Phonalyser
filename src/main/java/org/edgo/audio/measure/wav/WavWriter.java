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

package org.edgo.audio.measure.wav;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.edgo.audio.measure.common.StereoSampleFloat;

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
     * @param floatFormat  true for 32-bit IEEE float (samples supplied via writeFloats),
     *                     false for integer PCM (samples supplied via writeRaw)
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
        writeHeader(0); // placeholder — finalized in close()
    }

    /** Writes raw little-endian PCM bytes (for integer PCM mode). */
    public void writeRaw(byte[] buf, int length) throws IOException {
        raf.write(buf, 0, length);
        dataBytes += length;
    }

    /** Writes integer PCM samples packed to the given bit depth (little-endian). */
    public void writeSamples(StereoSampleFloat[] samples, int bitsPerSample) throws IOException {
        int bytesPerSample = bitsPerSample / 8;
        int ch0, ch1;
        ByteBuffer bb = ByteBuffer.allocate(samples.length * (bytesPerSample + 1) * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (StereoSampleFloat s : samples) {
            switch (bytesPerSample) {
                case 1:
                    ch0 = (((int) Math.round(s.ch0 * 256.) + 0x8000) & 0xFF);
                    ch1 = (((int) Math.round(s.ch1 * 256.) + 0x8000) & 0xFF);
                    bb.putShort((short) ch0);
                    bb.putShort((short) ch1);
                    break;
                case 2:
                    ch0 = (((int) Math.round((double)s.ch0 * 256.) + 0x800000) & 0xFFFFFF);
                    ch1 = (((int) Math.round((double)s.ch1 * 256.) + 0x800000) & 0xFFFFFF);
                    bb.put((byte) ch0).put((byte)(ch0 >> 8)).put((byte)(ch0 >> 16));
                    bb.put((byte) ch1).put((byte)(ch1 >> 8)).put((byte)(ch1 >> 16));
                    break;
                case 3:
                    ch0 = (((int) Math.round((double)s.ch0 * 256.) + 0x80000000) & 0xFFFFFFFF);
                    ch1 = (((int) Math.round((double)s.ch1 * 256.) + 0x80000000) & 0xFFFFFFFF);
                    bb.putInt(ch0);
                    bb.putInt(ch1);
                    break;
            }
        }
        raf.write(bb.array());
        dataBytes += bb.capacity();
    }

    /** Writes 32-bit IEEE float samples (for float WAV mode). */
    public void writeFloats(float[] samples) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            bb.putFloat(s);
        }
        raf.write(bb.array());
        dataBytes += (long) samples.length * 4;
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
