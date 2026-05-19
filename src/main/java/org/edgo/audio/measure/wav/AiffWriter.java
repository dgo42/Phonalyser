package org.edgo.audio.measure.wav;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import lombok.extern.log4j.Log4j2;

/**
 * Writes a PCM AIFF file (Apple Audio Interchange).  Samples are
 * stored big-endian — caller still hands little-endian PCM, this
 * class byte-swaps on the fly so the generator's existing fillBuffer
 * code path (LE for the {@link WavWriter}) can be reused unchanged.
 *
 * <p>Header is written on open as a placeholder and rewritten on close
 * with the final chunk sizes.  Sample rate is stored as 80-bit IEEE
 * extended-precision; see {@link #toExtended80(double)}.
 */
@Log4j2
public class AiffWriter implements AutoCloseable {

    private final File             file;
    private final int              sampleRate;
    private final int              channels;
    private final int              bitsPerSample;
    private final RandomAccessFile raf;
    private       long             dataBytes = 0;

    public AiffWriter(File file, int sampleRate, int channels, int bitsPerSample) throws IOException {
        this.file          = file;
        this.sampleRate    = sampleRate;
        this.channels      = channels;
        this.bitsPerSample = bitsPerSample;
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        this.raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        writeHeader(0);
    }

    /**
     * Writes raw little-endian PCM bytes.  Internally byte-swaps each
     * sample to big-endian (AIFF convention).
     */
    public void writeRaw(byte[] buf, int length) throws IOException {
        int bytesPerSample = bitsPerSample / 8;
        byte[] swapped = new byte[length];
        for (int i = 0; i < length; i += bytesPerSample) {
            for (int b = 0; b < bytesPerSample; b++) {
                swapped[i + b] = buf[i + bytesPerSample - 1 - b];
            }
        }
        raf.write(swapped, 0, length);
        dataBytes += length;
    }

    @Override
    public void close() throws IOException {
        raf.seek(0);
        writeHeader(dataBytes);
        raf.close();
        log.info("AIFF written: {}  ({} bytes PCM data)",
                file.getAbsolutePath(), String.format("%,d", dataBytes));
    }

    private void writeHeader(long dataSizeBytes) throws IOException {
        int    bytesPerFrame = channels * (bitsPerSample / 8);
        long   numFrames     = dataSizeBytes / bytesPerFrame;
        // FORM chunk size = 4 (AIFF) + 8 + 18 (COMM) + 8 + 8 + dataBytes
        long   formChunkSize = 4 + 8 + 18 + 8 + 8 + dataSizeBytes;
        long   ssndChunkSize = 8 + dataSizeBytes;

        ByteBuffer h = ByteBuffer.allocate(54).order(ByteOrder.BIG_ENDIAN);
        h.put("FORM".getBytes());
        h.putInt((int) formChunkSize);
        h.put("AIFF".getBytes());

        // COMM chunk
        h.put("COMM".getBytes());
        h.putInt(18);                              // COMM chunk size
        h.putShort((short) channels);
        h.putInt((int) numFrames);
        h.putShort((short) bitsPerSample);
        h.put(toExtended80(sampleRate));           // 10 bytes

        // SSND chunk
        h.put("SSND".getBytes());
        h.putInt((int) ssndChunkSize);
        h.putInt(0);                               // offset
        h.putInt(0);                               // blockSize
        raf.write(h.array());
    }

    /**
     * Converts a non-negative {@code double} to a 10-byte IEEE 754
     * 80-bit extended-precision (sign + 15-bit biased exponent + 64-bit
     * mantissa with explicit integer bit at position 63).  Used for the
     * AIFF COMM chunk's sample rate.  Bias is 16383.
     *
     * <p>Computing {@code (long)(mantissa * 2^63)} directly would
     * overflow signed {@code long} for any mantissa &gt; 1 (which it
     * always is, in [1,2)) and saturate to {@code Long.MAX_VALUE} —
     * decoders then read the rate as ~0.9999 × 2^exp instead of the
     * intended value.  We instead split the mantissa into integer-bit
     * (always 1) and 63-bit fractional part, the latter fitting safely
     * in {@code long}.
     */
    private static byte[] toExtended80(double v) {
        byte[] out = new byte[10];
        if (v == 0.0) return out;
        int sign = (v < 0) ? 0x8000 : 0;
        double abs = Math.abs(v);
        int    expon = Math.getExponent(abs);
        double mantissa = Math.scalb(abs, -expon);   // in [1, 2)
        int    biasedExp = expon + 16383;
        long   fractionBits = (long) ((mantissa - 1.0) * 0x1p63);
        long   mantInt      = (1L << 63) | fractionBits;
        out[0] = (byte) (((sign | biasedExp) >> 8) & 0xFF);
        out[1] = (byte) ( (sign | biasedExp)       & 0xFF);
        for (int i = 0; i < 8; i++) {
            out[2 + i] = (byte) (mantInt >>> ((7 - i) * 8));
        }
        return out;
    }
}
