package org.edgo.audio.measure.gui.scope;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import lombok.extern.log4j.Log4j2;

import org.edgo.audio.measure.enums.AudioFileFormat;
import org.edgo.audio.measure.gui.interfaces.PcmSink;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;

/**
 * Writes the latest {@code durationSeconds} of capture from a
 * {@link SignalBufferReader} into the user-chosen file.  Format is picked
 * from the file extension ({@code .wav} / {@code .flac} /
 * {@code .aiff} / {@code .aif}); chunks of float samples are
 * quantised to signed-PCM at the supplied bit depth.
 *
 * <p>The buffer is the scope's existing ring of normalised
 * {@code float} samples in {@code [-1, +1]}.  No live capture
 * coordination needed — the writer just reads whatever the buffer
 * currently holds.
 */
@Log4j2
public final class ScopeFileSaver {

    private ScopeFileSaver() {}

    /** Result of {@link #findFullPeriodWindow}: start/length, both 0 = no snap. */
    private static final class Window {
        final int start;
        final int length;
        Window(int s, int l) { this.start = s; this.length = l; }
    }

    /**
     * Locates the largest {@code (start, length)} sub-range of the
     * read snapshot whose endpoints land on rising zero crossings of
     * the left channel — both inside one signal period of the buffer
     * ends.  That guarantees the saved file:
     *
     * <ul>
     *   <li>starts at amplitude ≈ 0 (rising),</li>
     *   <li>ends at amplitude ≈ 0 (rising), and</li>
     *   <li>spans exactly N signal periods, so a looping player
     *       transitions seamlessly with no click at the seam.</li>
     * </ul>
     *
     * <p>Returns the trivial {@code (0, actual)} window when frequency
     * detection isn't usable or no suitable zero crossings exist.
     */
    private static Window findFullPeriodWindow(float[] left, int actual,
                                               int sampleRate, double freqHz) {
        if (!(freqHz > 0.0) || Double.isInfinite(freqHz) || sampleRate <= 0
                || actual < 2) {
            return new Window(0, actual);
        }
        int periodSamples = Math.max(2, (int) Math.round(sampleRate / freqHz));
        if (actual < 2 * periodSamples) return new Window(0, actual);

        int startSnap = firstRisingZeroCross(left, 1, Math.min(actual, periodSamples + 1));
        if (startSnap < 0) return new Window(0, actual);
        int endSnap = lastRisingZeroCross(left,
                Math.max(startSnap + periodSamples, actual - periodSamples),
                actual);
        if (endSnap <= startSnap) return new Window(0, actual);

        // endSnap is the index *of* the zero-crossing sample; the
        // saved range [startSnap, endSnap) excludes that final sample
        // (which equals the first sample of the looped next copy).
        int length = endSnap - startSnap;
        if (length < periodSamples) return new Window(0, actual);
        return new Window(startSnap, length);
    }

    /** First index in [lo, hi) where {@code arr[i-1] < 0 && arr[i] >= 0}; -1 if none. */
    private static int firstRisingZeroCross(float[] arr, int lo, int hi) {
        for (int i = lo; i < hi; i++) {
            if (arr[i - 1] < 0f && arr[i] >= 0f) return i;
        }
        return -1;
    }

    /** Largest index in [lo, hi) where {@code arr[i-1] < 0 && arr[i] >= 0}; -1 if none. */
    private static int lastRisingZeroCross(float[] arr, int lo, int hi) {
        for (int i = hi - 1; i >= lo && i >= 1; i--) {
            if (arr[i - 1] < 0f && arr[i] >= 0f) return i;
        }
        return -1;
    }

    /**
     * @param signalFrequencyHz pass &gt; 0 to truncate the saved
     *        length to an integer multiple of the signal's period so
     *        the file loops cleanly without a click at the seam.
     *        Pass 0 or {@link Double#NaN} for forms without a fixed
     *        period (noise, sweep, no detection available).
     */
    public static long save(SignalBufferReader reader, File outFile,
                            int sampleRate, int bitDepth,
                            double durationSeconds,
                            double signalFrequencyHz) throws IOException {
        if (reader == null) {
            throw new IOException("No scope buffer to save (start the scope first).");
        }
        int requestedFrames = (int) Math.max(1, Math.round(durationSeconds * sampleRate));
        int frames = Math.min(requestedFrames, reader.getCapacity());

        float[] left  = new float[frames];
        float[] right = new float[frames];
        int actual = reader.readLatest(frames, left, right);
        if (actual <= 0) {
            throw new IOException("Scope buffer is empty — capture hasn't produced any samples yet.");
        }
        // Snap start + length to rising zero crossings one period
        // apart so the saved file begins and ends on (nearly) zero
        // amplitude and the in-between span is an integer number of
        // signal periods.  Falls back to (0, actual) when frequency
        // isn't detected or no usable crossings exist.
        Window w = findFullPeriodWindow(left, actual, sampleRate, signalFrequencyHz);
        int saveStart  = w.start;
        int saveLength = w.length;

        int bytesPerSample = bitDepth / 8;
        int bytesPerFrame  = bytesPerSample * StereoPcmIo.CHANNELS;

        AudioFileFormat fmt = StereoPcmIo.formatForName(outFile.getName());

        try (PcmSink sink = StereoPcmIo.openSink(fmt, outFile, sampleRate, bitDepth)) {
            int chunkFrames = 4096;
            byte[] buf = new byte[chunkFrames * bytesPerFrame];
            int written = 0;
            while (written < saveLength) {
                int n = Math.min(chunkFrames, saveLength - written);
                StereoPcmIo.packStereo(left, right, saveStart + written, n, buf, bitDepth);
                sink.writeRaw(buf, n * bytesPerFrame);
                written += n;
            }
            log.info("Scope save: {} ({}, {} frames, {} s, {} Hz / {} bit; window=[{}, {}) of {})",
                    outFile.getAbsolutePath(), fmt, saveLength,
                    String.format(Locale.ROOT, "%.4f", saveLength / (double) sampleRate),
                    sampleRate, bitDepth, saveStart, saveStart + saveLength, actual);
        }
        return outFile.length();
    }
}
