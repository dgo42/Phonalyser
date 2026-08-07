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

package org.edgo.audio.measure.generator;

import java.io.File;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.wav.PcmFileLoader;

/**
 * A decoded audio file presented as a {@link SignalGenerator}, so file playback
 * runs through the SAME path as the DDS tone: the device the preferences name,
 * opened through {@code AudioBackend}.
 *
 * <p>Before this existed, "Play from..." opened a raw JavaSound output line of its
 * own, which meant it ignored the selected backend entirely - a QA40x user's file
 * came out of the computer's sound card.  Everything downstream (dither, the
 * per-lane full-scale ratio, the Left/Right output gate) is applied by the backend
 * exactly as it is for the tone; this class only supplies samples.
 *
 * <p>Samples are delivered MONO because that is the contract the playback path
 * pulls on: one value per frame, written to both lanes with their own scale
 * factors.  A multi-channel file is averaged down rather than having a channel
 * dropped.
 */
@Log4j2
public final class FilePlaybackGenerator extends SignalGenerator {

    /** How far {@link #openStream()} promises to read before a {@link #rewind()}
     *  reset - see the comment there for why one byte is the RIGHT number and
     *  not a placeholder: any stream that must buffer to honour a mark is one we
     *  want to reopen rather than buffer. */
    private static final int MARK_LIMIT_BYTES = 1;

    private final File file;
    /** Re-read at every end of stream, so the loop toggle takes effect on the
     *  next lap rather than only on the next start. */
    @Setter
    @Getter
    private volatile boolean loop;

    /** The file's own format - the caller opens the playback line with these, so
     *  the stream is played at its native rate and depth, not resampled. */
    @Getter
    private final int fileSampleRate;
    @Getter
    private final int fileBitDepth;

    private AudioInputStream in;
    private AudioFormat fmt;
    private int channels;
    private int bytesPerSample;
    private boolean bigEndian;
    private boolean signed;
    /** Full-scale magnitude for this depth, used to normalise to ±1.0. */
    private double fullScale;

    private byte[] buf = new byte[0];
    private int bufLen;
    private int bufPos;
    /** Set once the stream has ended and looping is off; the caller stops on it. */
    @Getter
    private volatile boolean finished;
    /** How many loop boundaries were crossed by REOPENING rather than by
     *  {@code reset()}.  Package-private and for the test alone: which of the two
     *  paths a format takes is the whole point of the tiny mark limit, and
     *  nothing outside can otherwise tell them apart. */
    volatile int reopens;

    /**
     * @param file a WAV / AIFF / FLAC file that {@link PcmFileLoader} can decode
     */
    public FilePlaybackGenerator(File file, boolean loop) throws Exception {
        // The inherited DDS state is unused - nextSample() is overridden - but the
        // base still needs a legal form and a non-zero full scale to construct.
        super(GenSignalForm.SINE, 1000.0, 48_000, 1.0, 1.0);
        this.file = file;
        this.loop = loop;
        openStream();
        this.fileSampleRate = (int) Math.round(fmt.getSampleRate());
        this.fileBitDepth   = fmt.getSampleSizeInBits();
    }

    private void openStream() throws Exception {
        in  = PcmFileLoader.instance().openAsPcm(file);
        fmt = in.getFormat();
        channels       = Math.max(1, fmt.getChannels());
        bytesPerSample = Math.max(1, fmt.getSampleSizeInBits() / 8);
        bigEndian      = fmt.isBigEndian();
        signed         = fmt.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
        fullScale      = Math.pow(2.0, fmt.getSampleSizeInBits() - 1);
        int frameBytes = bytesPerSample * channels;
        // A whole number of frames, so a refill never splits one.
        if (buf.length < frameBytes * 1024) {
            buf = new byte[frameBytes * 1024];
        }
        bufLen = 0;
        bufPos = 0;
        // Marked at the start so a loop can rewind by reset() instead of by
        // re-decoding - see rewind().  The limit is deliberately TINY, and that
        // is the whole point: the only stream this helps is one that is already
        // entirely in memory (a decoded FLAC), and such a stream ignores the
        // limit and resets however far it has been read.  A stream that would
        // have to BUFFER to honour a mark must invalidate it instead - a large
        // limit makes BufferedInputStream, which is what AudioSystem hands back
        // for WAV/AIFF, grow its buffer towards the whole file as playback
        // advances.  That would turn every single play of a large file into a
        // steadily growing heap; this class reads 1024 frames at a time and must
        // keep doing so.  An invalidated mark makes reset() throw, and rewind()
        // reopens instead - a file open, not a decode.
        if (in.markSupported()) {
            in.mark(MARK_LIMIT_BYTES);
        }
    }

    /**
     * The next mono sample in ±1.0, averaged across the file's channels.  Yields
     * silence once the file has ended and looping is off - the playback loop is
     * stopped by the caller watching {@link #isFinished()}.
     */
    @Override
    public double nextSample() {
        int frameBytes = bytesPerSample * channels;
        if (bufPos + frameBytes > bufLen && !refill(frameBytes)) {
            return 0.0;
        }
        double sum = 0.0;
        for (int c = 0; c < channels; c++) {
            sum += decodeSample(bufPos + c * bytesPerSample);
        }
        bufPos += frameBytes;
        return sum / channels;
    }

    /** Reads the next block, reopening the file at end of stream while looping.
     *  @return false when nothing more can be read */
    private boolean refill(int frameBytes) {
        while (true) {
            // Read into a local: close() nulls the field, and every stop joins
            // the render thread with a 2 s bound and then gives up - so a thread
            // wedged in a native write can wake after the close and land here.
            // A bare dereference would raise an NPE, which is not an IOException
            // and would escape as a spurious device error.
            AudioInputStream open = in;
            if (open == null) {
                finished = true;
                return false;
            }
            try {
                int carry = bufLen - bufPos;                 // partial frame, if any
                if (carry > 0) {
                    System.arraycopy(buf, bufPos, buf, 0, carry);
                }
                bufPos = 0;
                bufLen = carry;
                int n = open.read(buf, bufLen, buf.length - bufLen);
                if (n > 0) {
                    bufLen += n;
                    if (bufLen >= frameBytes) return true;
                    continue;
                }
            } catch (IOException ex) {
                log.warn("File playback read failed: {}", ex.getMessage());
                finished = true;
                return false;
            }
            // End of stream.  The flag is read HERE, at the boundary, so a loop
            // toggled mid-play takes effect on the lap that is ending rather than
            // only on the next start.
            if (!loop) {
                finished = true;
                return false;
            }
            if (!rewind()) {
                finished = true;
                return false;
            }
        }
    }

    /**
     * Back to sample 0 for the next lap, WITHOUT re-decoding where that can be
     * avoided.
     *
     * <p>This runs on the render pull thread, inside {@code nextSample()} - every
     * millisecond spent here is a millisecond the output line is not fed.  The
     * old path always called {@link #openStream()}, which for FLAC re-runs a
     * whole-file eager decode: on a 50 MB file that is seconds, and the playback
     * watchdog (which gives the device about a second before it calls the lane
     * dead) fired at the first loop boundary and reported a healthy device as
     * "stopped draining".
     *
     * <p>A decoded FLAC is already a byte array in memory, so its stream marks
     * and resets for free; only a format that cannot rewind pays for a reopen,
     * and that reopen is a file open rather than a decode.
     *
     * @return false when the stream could not be rewound at all
     */
    private boolean rewind() {
        // Same reason as refill()'s local: a close can land between the two.
        AudioInputStream open = in;
        if (open == null) {
            return false;
        }
        if (open.markSupported()) {
            try {
                open.reset();
                bufLen = 0;
                bufPos = 0;
                return true;
            } catch (IOException resetFailed) {
                log.debug("File playback could not reset, reopening: {}",
                        resetFailed.getMessage());
            }
        }
        Closeables.closeQuietly(open);
        reopens++;
        try {
            openStream();
            return true;
        } catch (Exception ex) {
            log.warn("File playback could not loop: {}", ex.getMessage());
            return false;
        }
    }

    /** One PCM sample at {@code off}, normalised to ±1.0. */
    private double decodeSample(int off) {
        long v = 0;
        if (bigEndian) {
            for (int i = 0; i < bytesPerSample; i++) {
                v = (v << 8) | (buf[off + i] & 0xFF);
            }
        } else {
            for (int i = bytesPerSample - 1; i >= 0; i--) {
                v = (v << 8) | (buf[off + i] & 0xFF);
            }
        }
        int bits = bytesPerSample * 8;
        if (signed) {
            // Sign-extend from the sample's own width.
            long signBit = 1L << (bits - 1);
            if ((v & signBit) != 0) {
                v -= (1L << bits);
            }
        } else {
            v -= (1L << (bits - 1));
        }
        return v / fullScale;
    }

    /** The backends skip their JIT warmup for a file source - see
     *  {@link SignalGenerator#needsJitWarmup()}: decode is I/O-bound, and the
     *  warmup consumed a non-looping stream to EOF, which the EOF watcher
     *  observed BEFORE the rewind below could undo it (the session then
     *  stopped before the first audible sample). */
    @Override
    public boolean needsJitWarmup() {
        return false;
    }

    /**
     * The warmup-rewind contract every playback path relies on: the JIT
     * warmup consumes REAL samples and then calls this to start from zero.
     * For a file that means reopening the stream and clearing
     * {@link #finished} - kept correct even though the backends no longer
     * warm up against a file source (see {@link #needsJitWarmup()}), so any
     * OTHER pre-play consumption resets cleanly too.
     */
    @Override
    public void resetSweepPosition() {
        super.resetSweepPosition();
        Closeables.closeQuietly(in);
        try {
            openStream();
            bufLen = 0;
            bufPos = 0;
            finished = false;
        } catch (Exception ex) {
            log.warn("File playback could not rewind after warmup: {}", ex.getMessage());
            finished = true;
        }
    }

    /** Releases the decoder; the generator yields silence afterwards. */
    public void close() {
        Closeables.closeQuietly(in);
        in = null;
        finished = true;
    }
}
