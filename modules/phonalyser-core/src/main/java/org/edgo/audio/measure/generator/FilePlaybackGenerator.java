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
 * <p>Before this existed, "Play from…" opened a raw JavaSound output line of its
 * own, which meant it ignored the selected backend entirely — a QA40x user's file
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

    private final File file;
    /** Re-read at every end of stream, so the loop toggle takes effect on the
     *  next lap rather than only on the next start. */
    @Setter
    private volatile boolean loop;

    /** The file's own format — the caller opens the playback line with these, so
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

    /**
     * @param file a WAV / AIFF / FLAC file that {@link PcmFileLoader} can decode
     */
    public FilePlaybackGenerator(File file, boolean loop) throws Exception {
        // The inherited DDS state is unused — nextSample() is overridden — but the
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
    }

    /**
     * The next mono sample in ±1.0, averaged across the file's channels.  Yields
     * silence once the file has ended and looping is off — the playback loop is
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
            try {
                int carry = bufLen - bufPos;                 // partial frame, if any
                if (carry > 0) {
                    System.arraycopy(buf, bufPos, buf, 0, carry);
                }
                bufPos = 0;
                bufLen = carry;
                int n = in.read(buf, bufLen, buf.length - bufLen);
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
            // End of stream.
            if (!loop) {
                finished = true;
                return false;
            }
            Closeables.closeQuietly(in);
            try {
                openStream();
            } catch (Exception ex) {
                log.warn("File playback could not loop: {}", ex.getMessage());
                finished = true;
                return false;
            }
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

    /** Releases the decoder; the generator yields silence afterwards. */
    public void close() {
        Closeables.closeQuietly(in);
        in = null;
        finished = true;
    }
}
