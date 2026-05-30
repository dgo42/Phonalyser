package org.edgo.audio.measure.gui.scope;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.AudioFileFormat;
import org.edgo.audio.measure.gui.interfaces.PcmSink;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.wav.AiffWriter;
import org.edgo.audio.measure.wav.FlacWriter;
import org.edgo.audio.measure.wav.WavWriter;

/**
 * Stereo-PCM ↔ normalised-float file I/O for the scope, shared by both
 * directions:
 * <ul>
 *   <li><b>save</b> — {@link #packStereo} quantises {@code float [-1,+1]} to
 *       signed little-endian PCM and {@link #openSink} picks the format writer
 *       ({@link ScopeFileSaver} dumps the ring; {@link #saveStreaming} records
 *       a live capture of any length straight to disk);</li>
 *   <li><b>load</b> — {@link #decodeStereo} turns a decoder's PCM bytes back
 *       into {@code float [-1,+1]} ({@code ScopeOpenSignal} fills a buffer).</li>
 * </ul>
 *
 * <p>One home for the encode/decode so the two sides can't drift apart.
 */
@Log4j2
public final class StereoPcmIo {

    static final int CHANNELS = 2;

    private StereoPcmIo() {}

    // ─── Format ─────────────────────────────────────────────────────────────

    /** Picks the file format from the name's extension (default WAV). */
    static AudioFileFormat formatForName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".flac")                          ? AudioFileFormat.FLAC
             : lower.endsWith(".aiff") || lower.endsWith(".aif") ? AudioFileFormat.AIFF
             :                                                     AudioFileFormat.WAV;
    }

    /** Opens a format-appropriate {@link PcmSink} for {@code outFile}. */
    static PcmSink openSink(AudioFileFormat fmt, File outFile, int sampleRate, int bitDepth)
            throws IOException {
        switch (fmt) {
            case FLAC: {
                FlacWriter w = new FlacWriter(outFile, sampleRate, CHANNELS, bitDepth);
                return new PcmSink() {
                    @Override public void writeRaw(byte[] b, int n) throws IOException { w.writeRaw(b, n); }
                    @Override public void close()                    throws IOException { w.close(); }
                };
            }
            case AIFF: {
                AiffWriter w = new AiffWriter(outFile, sampleRate, CHANNELS, bitDepth);
                return new PcmSink() {
                    @Override public void writeRaw(byte[] b, int n) throws IOException { w.writeRaw(b, n); }
                    @Override public void close()                    throws IOException { w.close(); }
                };
            }
            default: {
                WavWriter w = new WavWriter(outFile, sampleRate, CHANNELS, bitDepth, false);
                return new PcmSink() {
                    @Override public void writeRaw(byte[] b, int n) throws IOException { w.writeRaw(b, n); }
                    @Override public void close()                    throws IOException { w.close(); }
                };
            }
        }
    }

    // ─── Encode (save) ──────────────────────────────────────────────────────

    /**
     * Converts {@code count} stereo float samples (range {@code [-1, +1]})
     * starting at {@code offset} into little-endian signed PCM bytes at the
     * requested bit depth.
     */
    static void packStereo(float[] left, float[] right, int offset, int count,
                           byte[] buf, int bitDepth) {
        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * CHANNELS;
        if (bitDepth == 8) {
            for (int i = 0; i < count; i++) {
                int bufOff = i * bytesPerFrame;
                buf[bufOff]     = (byte) ((int) Math.round(clamp(left [offset + i]) * 127.0) + 128 & 0xFF);
                buf[bufOff + 1] = (byte) ((int) Math.round(clamp(right[offset + i]) * 127.0) + 128 & 0xFF);
            }
            return;
        }
        long maxVal = (1L << (bitDepth - 1)) - 1;
        for (int i = 0; i < count; i++) {
            long pcmL = (long) Math.round(clamp(left [offset + i]) * maxVal);
            long pcmR = (long) Math.round(clamp(right[offset + i]) * maxVal);
            int  bufOff = i * bytesPerFrame;
            for (int b = 0; b < bytesPerSample; b++) {
                buf[bufOff + b]                  = (byte) (pcmL >> (8 * b));
                buf[bufOff + bytesPerSample + b] = (byte) (pcmR >> (8 * b));
            }
        }
    }

    private static double clamp(double v) {
        if (v >  1.0) return  1.0;
        if (v < -1.0) return -1.0;
        return v;
    }

    // ─── Decode (load) ──────────────────────────────────────────────────────

    /**
     * Converts {@code frames} of interleaved PCM bytes into normalised
     * {@code float [-1,+1]} L/R.  A mono source ({@code channels == 1}) is
     * mirrored to both outputs.  Handles 8/16/24/32-bit, signed/unsigned,
     * big/little-endian — the inverse of {@link #packStereo} for the common
     * (signed little-endian) case, and tolerant of the others a decoder yields.
     */
    static void decodeStereo(byte[] pcm, int frames, int channels, int bytesPerSample,
                             boolean signed, boolean bigEndian,
                             float[] left, float[] right) {
        int frameSize   = bytesPerSample * channels;
        double midpoint = 1L << (bytesPerSample * 8 - 1);
        for (int f = 0; f < frames; f++) {
            int off = f * frameSize;
            int sL = readSample(pcm, off, bytesPerSample, signed, bigEndian);
            int sR = (channels >= 2)
                    ? readSample(pcm, off + bytesPerSample, bytesPerSample, signed, bigEndian)
                    : sL;
            left [f] = (float) (sL / midpoint);
            right[f] = (float) (sR / midpoint);
        }
    }

    private static int readSample(byte[] pcm, int off, int bytesPerSample, boolean signed, boolean bigEndian) {
        switch (bytesPerSample) {
            case 1: {
                int v = pcm[off] & 0xFF;
                return signed ? (byte) v : v - 128;
            }
            case 2: {
                int v = bigEndian
                        ? ((pcm[off] & 0xFF) << 8) | (pcm[off + 1] & 0xFF)
                        : ((pcm[off + 1] & 0xFF) << 8) | (pcm[off] & 0xFF);
                return signed ? (short) v : v - 0x8000;
            }
            case 3: {
                int v = bigEndian
                        ? ((pcm[off] << 16) | ((pcm[off + 1] & 0xFF) << 8) | (pcm[off + 2] & 0xFF))
                        : ((pcm[off + 2] << 16) | ((pcm[off + 1] & 0xFF) << 8) | (pcm[off] & 0xFF));
                v = (v << 8) >> 8;        // sign-extend 24-bit
                return signed ? v : v - 0x800000;
            }
            case 4: {
                int v = bigEndian
                        ? ((pcm[off] << 24) | ((pcm[off + 1] & 0xFF) << 16)
                            | ((pcm[off + 2] & 0xFF) << 8) | (pcm[off + 3] & 0xFF))
                        : ((pcm[off + 3] << 24) | ((pcm[off + 2] & 0xFF) << 16)
                            | ((pcm[off + 1] & 0xFF) << 8) | (pcm[off] & 0xFF));
                return signed ? v : (int) (v - 0x80000000L);
            }
            default:
                throw new IllegalStateException("Unsupported bytesPerSample: " + bytesPerSample);
        }
    }

    // ─── Streaming save (record-to-disk) ────────────────────────────────────

    /**
     * Streams up to {@code totalFrames} of LIVE capture from {@code reader}'s
     * contiguous cursor straight to {@code outFile} in real time — for captures
     * longer than the ring buffer.  Unlike {@link ScopeFileSaver#save}, which
     * dumps the already-captured ring, this records <em>forward</em>: it reads
     * the cursor as fresh samples arrive and writes them, so it takes about
     * {@code totalFrames / sampleRate} seconds of wall-clock to complete.
     *
     * <p>Runs SYNCHRONOUSLY — call from a background thread.  {@code cancelled}
     * is polled to stop early (the partial file is still finalised);
     * {@code progress} is notified with the running frame count for a UI bar.
     * Returns the number of frames actually written.
     *
     * <p>The caller owns the capture reference (acquire before, release after).
     * A sequential disk write easily keeps up with capture, but if the writer
     * ever falls a full ring behind (disk stall), the cursor is re-anchored and
     * a one-time gap is logged rather than tearing.
     */
    public static long saveStreaming(SignalBufferReader reader, File outFile,
                                     int sampleRate, int bitDepth, long totalFrames,
                                     BooleanSupplier cancelled, LongConsumer progress)
            throws IOException {
        if (reader == null) {
            throw new IOException("No live capture to record (start the scope recording first).");
        }
        AudioFileFormat fmt = formatForName(outFile.getName());
        int bytesPerFrame = (bitDepth / 8) * CHANNELS;
        int chunkFrames   = Math.max(4096, sampleRate / 10);   // ~100 ms per write
        float[] left  = new float[chunkFrames];
        float[] right = new float[chunkFrames];
        byte[]  buf   = new byte[chunkFrames * bytesPerFrame];

        reader.seekToLatest();
        long written = 0;
        long gaps    = 0;
        try (PcmSink sink = openSink(fmt, outFile, sampleRate, bitDepth)) {
            while (written < totalFrames && (cancelled == null || !cancelled.getAsBoolean())) {
                int want = (int) Math.min(chunkFrames, totalFrames - written);
                int n = reader.read(want, left, right);
                if (n == SignalBufferReader.OVERRUN) {
                    reader.seekToLatest();   // writer stalled a full ring behind — re-anchor
                    gaps++;
                    continue;
                }
                if (n <= 0) {
                    try { Thread.sleep(20); }   // caught up to the live tip — await fresh samples
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                packStereo(left, right, 0, n, buf, bitDepth);
                sink.writeRaw(buf, n * bytesPerFrame);
                written += n;
                if (progress != null) progress.accept(written);
            }
        }
        if (gaps > 0) {
            log.warn("Scope stream-record: {} ring-overrun gap(s) writing {}", gaps, outFile.getName());
        }
        log.info("Scope stream-record: {} ({} frames, {} s, {} Hz / {} bit)",
                outFile.getAbsolutePath(), written,
                String.format(Locale.ROOT, "%.1f", written / (double) sampleRate),
                sampleRate, bitDepth);
        return written;
    }
}
