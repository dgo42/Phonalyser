package org.edgo.audio.measure.wav;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import org.edgo.audio.measure.common.StereoSample;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Reads a PCM WAV file and delivers decoded channel-0 samples via a listener.
 * Supports 8, 16, 24 and 32-bit little-endian signed PCM (format code 1).
 */
@Log4j2
public class WavReader {

    @Getter private final int  sampleRate;
    @Getter private final int  channels;
    @Getter private final int  bitsPerSample;
    @Getter private final long frameCount;

    private final long dataOffset;  // byte offset in file where PCM data starts
    private final File file;

    public WavReader(String filePath) throws IOException {
        this.file = new File(filePath);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // RIFF header
            expectTag(raf, "RIFF");
            readLE32(raf);          // file size − 8 (ignored)
            expectTag(raf, "WAVE");

            // Scan chunks
            int  tmpSampleRate = 0;
            int  tmpChannels   = 0;
            int  tmpBits       = 0;
            long tmpDataOffset = 0;
            long tmpDataSize   = 0;

            while (raf.getFilePointer() < raf.length() - 8) {
                byte[] tag  = new byte[4];
                raf.readFully(tag);
                long chunkSize = Integer.toUnsignedLong(readLE32(raf));
                String tagStr = new String(tag);

                if ("fmt ".equals(tagStr)) {
                    int audioFormat = readLE16(raf);
                    if (audioFormat != 1) {
                        throw new IOException("Only PCM (format 1) WAV files are supported, got: " + audioFormat);
                    }
                    tmpChannels   = readLE16(raf);
                    tmpSampleRate = readLE32(raf);
                    readLE32(raf);          // byteRate
                    readLE16(raf);          // blockAlign
                    tmpBits       = readLE16(raf);
                    long remaining = chunkSize - 16;
                    if (remaining > 0) {
                        raf.skipBytes((int) remaining);
                    }

                } else if ("data".equals(tagStr)) {
                    tmpDataOffset = raf.getFilePointer();
                    tmpDataSize   = chunkSize;
                    break;

                } else {
                    raf.skipBytes((int) chunkSize);
                }
            }

            if (tmpBits == 0) {
                throw new IOException("fmt  chunk not found in: " + filePath);
            }
            if (tmpDataOffset == 0) {
                throw new IOException("data chunk not found in: " + filePath);
            }

            this.sampleRate    = tmpSampleRate;
            this.channels      = tmpChannels;
            this.bitsPerSample = tmpBits;
            this.dataOffset    = tmpDataOffset;
            this.frameCount    = tmpDataSize / ((long) tmpChannels * (tmpBits / 8));
        }

        log.info("WAV: {} Hz  {} ch  {} bit  {} frames ({})",
                sampleRate, channels, bitsPerSample,
                String.format("%,d", frameCount),
                file.getName());
    }

    /**
     * Reads all frames and delivers stereo samples to the listener in blocks.
     * Samples are offset-binary (same convention as CsjsoundRecorder): unsigned 0..2^bitsPerSample-1.
     * For mono files ch1 equals ch0.
     */
    public void process(Consumer<StereoSample[]> listener) throws IOException {
        int sampleBytes       = bitsPerSample / 8;
        int effectiveFameSize = sampleBytes * channels;
        int blockFrames = Math.max(1, 65536 / effectiveFameSize);
        byte[] buf      = new byte[blockFrames * effectiveFameSize];

        try (FileInputStream fis = new FileInputStream(file)) {
            fis.skip(dataOffset);
            long framesLeft = frameCount;
            while (framesLeft > 0) {
                int toRead      = (int) Math.min(blockFrames, framesLeft);
                int bytesWanted = toRead * effectiveFameSize;
                int bytesRead   = fis.read(buf, 0, bytesWanted);
                if (bytesRead <= 0) {
                    break;
                }
                int frames = bytesRead / effectiveFameSize;
                StereoSample[] out = new StereoSample[frames];
                for (int f = 0; f < frames; f++) {
                    int base   = f * effectiveFameSize;
                    out[f]     = new StereoSample();
                    out[f].ch0 = readSample(buf, base, sampleBytes);
                    out[f].ch1 = channels >= 2
                            ? readSample(buf, base + sampleBytes, sampleBytes)
                            : out[f].ch0;
                }
                listener.accept(out);
                framesLeft -= frames;
            }
        }
    }

    // -------------------------------------------------------------------------

    /** Reads one little-endian signed sample and converts to offset-binary unsigned. */
    private static int readSample(byte[] buf, int offset, int sampleBytes) {
        switch (sampleBytes) {
            case 1:
                return (buf[offset] & 0xFF) ^ 0x80;   // WAV 8-bit is unsigned; re-center
            case 2: {
                int s = (short)((buf[offset + 1] & 0xFF) << 8 | (buf[offset] & 0xFF));
                return (s - 0x8000) & 0xFFFF;
            }
            case 3: {
                int s = (buf[offset + 2] << 16) | ((buf[offset + 1] & 0xFF) << 8) | (buf[offset] & 0xFF);
                return (s - 0x800000) & 0xFFFFFF;
            }
            case 4:
                return ((buf[offset + 3] << 24)
                      | ((buf[offset + 2] & 0xFF) << 16)
                      | ((buf[offset + 1] & 0xFF) << 8)
                      |  (buf[offset]     & 0xFF))
                       - 0x80000000;
            default:
                throw new IllegalArgumentException("Unsupported bytes per sample: " + sampleBytes);
        }
    }

    private static void expectTag(RandomAccessFile raf, String tag) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        String got = new String(b);
        if (!tag.equals(got)) {
            throw new IOException("Expected '" + tag + "' but got '" + got + "'");
        }
    }

    private static int readLE32(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int readLE16(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[2];
        raf.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }
}
